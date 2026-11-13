/*
 * Resona DSP engine v2: LADSPA plugin hosted by PipeWire's filter-chain.
 *
 * Two labels:
 *   resona           2 in -> 2 out   (headphones / stereo speakers)
 *   resona_surround  2 in -> 8 out   (FL FR FC LFE RL RR SL SR: HDMI, analog 5.1/7.1, S/PDIF AC3)
 *
 * Signal chain:
 *   preamp → Smart Leveler → Intelligent EQ → 10-band EQ → Adaptive Loudness → Voice Focus
 *   → Tube Warmth → DeepBass → Clarity → 3D Stage → { Crossfeed | HRTF Spatial } (stereo only)
 *   → Ambience → Correction EQ (AutoEq / DDC / EqualizerAPO) → output gain
 *   → Surround upmix (surround label) → linked lookahead limiter
 *
 * All parameters arrive through shared memory (resona_shm.h); run() is
 * allocation-free and lock-free.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#define _GNU_SOURCE
#include <fcntl.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#if defined(__SSE__)
#include <xmmintrin.h>
#endif

#include "ladspa_abi.h"
#include "resona_shm.h"

#define CTRL_BLOCK 32
#define NBANDS RESONA_NBANDS
#define NCORR RESONA_NCORR
#define LIM_DQ 4096
#define MAXCH 8

enum { CH_FL, CH_FR, CH_FC, CH_LFE, CH_RL, CH_RR, CH_SL, CH_SR };

static const float EQ_FREQS[NBANDS] = {32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};

/* Intelligent EQ targets: long-term band energy relative to the mean (dB) */
static const float IEQ_TARGETS[4][NBANDS] = {
    {3.0f, 3.0f, 2.0f, 1.0f, 0.0f, -1.0f, -2.0f, -3.0f, -5.0f, -8.0f},   /* balanced */
    {5.0f, 5.0f, 3.5f, 2.0f, 0.5f, -1.0f, -2.5f, -4.0f, -6.0f, -9.5f},   /* warm / rich */
    {1.0f, 1.5f, 1.5f, 1.0f, 1.0f, 0.5f, 0.0f, -2.0f, -4.5f, -8.5f},     /* focused (vocals) */
    {2.0f, 2.0f, 1.5f, 0.5f, 0.0f, -0.5f, -1.0f, -1.5f, -3.0f, -5.0f},   /* bright / open */
};

static const float DEFAULTS[P_COUNT] = {
    [P_ENABLE] = 1, [P_EQ_ON] = 1,
    [P_BASS_ON] = 1, [P_BASS_AMT] = 0.5f, [P_BASS_FREQ] = 100, [P_BASS_PUNCH] = 0.35f,
    [P_CLAR_ON] = 1, [P_CLAR_AMT] = 0.3f,
    [P_VOICE_AMT] = 0.5f,
    [P_STAGE_ON] = 1, [P_STAGE_WIDTH] = 1.3f, [P_STAGE_DEPTH] = 0.25f,
    [P_XFEED_AMT] = 0.5f,
    [P_AMB_MIX] = 0.2f, [P_AMB_SIZE] = 0.4f, [P_AMB_DAMP] = 0.5f,
    [P_LEV_AMT] = 0.6f, [P_LEV_TARGET] = -16,
    [P_LOUD_ON] = 1, [P_LOUD_AMT] = 0.6f,
    [P_LIM_ON] = 1, [P_LIM_CEIL] = -0.5f,
    [P_IEQ_AMT] = 0.5f, [P_TUBE_DRIVE] = 0.3f,
    [P_HRTF_ANGLE] = 30, [P_HRTF_AMT] = 1.0f,
    [P_UPMIX_ON] = 1, [P_UPMIX_CENTER] = 0.7f, [P_UPMIX_SURR] = 0.6f, [P_UPMIX_LFE] = 0.5f,
    [P_LAYOUT] = 0, [P_BASS_TYPE] = 0,
};

enum { BQ_PEAK, BQ_LSHELF, BQ_HSHELF, BQ_LP, BQ_HP, BQ_BP, BQ_AP };

/*
 * DeepBass voicings. Each one sets how real low end (filter) and psychoacoustic
 * harmonics are blended, and whether transients get extra kick.
 */
enum { BASS_BALANCED, BASS_SUB, BASS_PUNCHY, BASS_WARM, BASS_NTYPES };
typedef struct {
    int   filt;        /* BQ_LSHELF or BQ_PEAK */
    float f_mul, f_min, f_max, q, gain_db;  /* boost filter, relative to the bass frequency */
    float even, odd, harm;                  /* harmonic generator weights and level */
    float kick;                             /* transient emphasis on the low band */
    float mud_db;                           /* low-mid cleanup at ~280 Hz */
} bass_voice;
static const bass_voice BASS_VOICES[BASS_NTYPES] = {
    /* balanced: shelf plus even/odd harmonics, the original DeepBass */
    {BQ_LSHELF, 1.0f, 40, 250, 0.7071f, 9.0f, 0.65f, 0.45f, 1.0f, 0.0f, 0.0f},
    /* sub boost: real energy down at 35-60 Hz, few harmonics */
    {BQ_PEAK,   0.5f, 35, 90,  1.0f,   11.0f, 0.45f, 0.30f, 0.6f, 0.0f, 0.0f},
    /* punchy: tight mid-bass bump, odd harmonics for bite, transient kick, less mud */
    {BQ_PEAK,   0.9f, 60, 140, 1.2f,    7.0f, 0.45f, 0.70f, 1.0f, 1.0f, -3.0f},
    /* warm: broad low shelf reaching into the low mids, mostly 2nd harmonic */
    {BQ_LSHELF, 2.0f, 80, 320, 0.55f,   7.0f, 0.95f, 0.15f, 1.1f, 0.0f, 0.0f},
};

/* ------------------------------------------------------------------ */
/* building blocks                                                     */

typedef struct { float b0, b1, b2, a1, a2; } bq_c;
typedef struct { float z1, z2; } bq_s;

static inline float bq(const bq_c *c, bq_s *s, float x)
{
    float y = c->b0 * x + s->z1;
    s->z1 = c->b1 * x - c->a1 * y + s->z2;
    s->z2 = c->b2 * x - c->a2 * y;
    return y;
}


static void bq_design(bq_c *c, int type, double fs, double f, double q, double gdb)
{
    if (f > fs * 0.45) f = fs * 0.45;
    if (f < 5) f = 5;
    if (q < 0.05) q = 0.05;
    double A = pow(10.0, gdb / 40.0), w = 2.0 * M_PI * f / fs;
    double cw = cos(w), sw = sin(w), al = sw / (2.0 * q), sA = 2.0 * sqrt(A) * al;
    double b0 = 1, b1 = 0, b2 = 0, a0 = 1, a1 = 0, a2 = 0;
    switch (type) {
    case BQ_PEAK:
        b0 = 1 + al * A; b1 = -2 * cw; b2 = 1 - al * A;
        a0 = 1 + al / A; a1 = -2 * cw; a2 = 1 - al / A; break;
    case BQ_LSHELF:
        b0 = A * ((A + 1) - (A - 1) * cw + sA); b1 = 2 * A * ((A - 1) - (A + 1) * cw);
        b2 = A * ((A + 1) - (A - 1) * cw - sA); a0 = (A + 1) + (A - 1) * cw + sA;
        a1 = -2 * ((A - 1) + (A + 1) * cw); a2 = (A + 1) + (A - 1) * cw - sA; break;
    case BQ_HSHELF:
        b0 = A * ((A + 1) + (A - 1) * cw + sA); b1 = -2 * A * ((A - 1) + (A + 1) * cw);
        b2 = A * ((A + 1) + (A - 1) * cw - sA); a0 = (A + 1) - (A - 1) * cw + sA;
        a1 = 2 * ((A - 1) - (A + 1) * cw); a2 = (A + 1) - (A - 1) * cw - sA; break;
    case BQ_LP:
        b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = b0; a0 = 1 + al; a1 = -2 * cw; a2 = 1 - al; break;
    case BQ_HP:
        b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = b0; a0 = 1 + al; a1 = -2 * cw; a2 = 1 - al; break;
    case BQ_BP:
        b0 = al; b1 = 0; b2 = -al; a0 = 1 + al; a1 = -2 * cw; a2 = 1 - al; break;
    case BQ_AP:
        b0 = 1 - al; b1 = -2 * cw; b2 = 1 + al; a0 = 1 + al; a1 = -2 * cw; a2 = 1 - al; break;
    }
    c->b0 = (float)(b0 / a0); c->b1 = (float)(b1 / a0); c->b2 = (float)(b2 / a0);
    c->a1 = (float)(a1 / a0); c->a2 = (float)(a2 / a0);
}

static float op_coef(double fs, double f) { return (float)(1.0 - exp(-2.0 * M_PI * f / fs)); }
static float tc_coef(double fs, double t, double n) { return (float)(1.0 - exp(-n / (t * fs))); }
static inline float db2lin(float d) { return powf(10.0f, d * 0.05f); }
static inline float lin2db(float l) { return 20.0f * log10f(l > 1e-9f ? l : 1e-9f); }
static inline float clampf(float x, float a, float b) { return x < a ? a : (x > b ? b : x); }
static unsigned next_pow2(unsigned v) { unsigned p = 1; while (p < v) p <<= 1; return p; }

/* ------------------------------------------------------------------ */
/* HRTF: Brown & Duda structural model (head shadow + ITD + pinna)    */

#define HRTF_LEN 64     /* pinna FIR span (samples) */
#define HRTF_DLY 128    /* ITD + pinna delay line */

typedef struct {
    float hs_b0, hs_b1, hs_a1;         /* one-pole/one-zero head shadow */
    float delay;                       /* fractional ITD (samples) */
    int   tap[5]; float rho[5];        /* pinna echoes */
} hrtf_path;

static void hrtf_design_path(hrtf_path *p, double fs, double incidence_deg, double az_deg, double extra_delay)
{
    const double a = 0.0875, c = 343.0, w0 = c / a;
    const double amin = 0.1, thmin = 150.0;
    double alpha = (1 + amin / 2) + (1 - amin / 2) * cos(incidence_deg / thmin * M_PI);
    /* H(s) = (1 + alpha s / 2w0) / (1 + s / 2w0), bilinear transform */
    double K = 2.0 * fs, T = 1.0 / (2.0 * w0);
    double n0 = 1 + alpha * T * K, n1 = 1 - alpha * T * K;
    double d0 = 1 + T * K, d1 = 1 - T * K;
    p->hs_b0 = (float)(n0 / d0); p->hs_b1 = (float)(n1 / d0); p->hs_a1 = (float)(d1 / d0);
    p->delay = (float)extra_delay;
    static const double rho[5] = {0.5, -1.0, 0.5, -0.25, 0.25};
    static const double A[5] = {1, 5, 5, 5, 5}, B[5] = {2, 4, 7, 11, 13}, D[5] = {1, 0.5, 0.5, 0.5, 0.5};
    for (int k = 0; k < 5; k++) {
        double tau = A[k] * cos(az_deg * M_PI / 360.0) * sin(D[k] * M_PI / 2) + B[k]; /* elevation 0 */
        int t = (int)lround(tau * fs / 44100.0);
        if (t < 1) t = 1;
        if (t >= HRTF_LEN) t = HRTF_LEN - 1;
        p->tap[k] = t;
        p->rho[k] = (float)(rho[k] * 0.55); /* tamed: keeps colouration musical */
    }
}

/* Brown-Duda ITD for an incidence angle (radians) */
static double bd_delay(double inc)
{
    const double a = 0.0875, c = 343.0;
    inc = fabs(inc);
    return inc < M_PI / 2 ? -a / c * cos(inc) : a / c * (inc - M_PI / 2);
}

typedef struct { float hs_x1, hs_y1; float line[HRTF_DLY]; unsigned pos; } hrtf_state;

static inline float hrtf_run(const hrtf_path *p, hrtf_state *s, float x)
{
    float y = p->hs_b0 * x + p->hs_b1 * s->hs_x1 - p->hs_a1 * s->hs_y1;
    s->hs_x1 = x; s->hs_y1 = y;
    s->line[s->pos] = y;
    int di = (int)p->delay; float fr = p->delay - (float)di;
    float out = s->line[(s->pos - di) & (HRTF_DLY - 1)] * (1 - fr) + s->line[(s->pos - di - 1) & (HRTF_DLY - 1)] * fr;
    for (int k = 0; k < 5; k++)
        out += p->rho[k] * s->line[(s->pos - di - p->tap[k]) & (HRTF_DLY - 1)];
    s->pos = (s->pos + 1) & (HRTF_DLY - 1);
    return out;
}

/* ------------------------------------------------------------------ */

typedef struct {
    double fs;
    unsigned nout;
    LADSPA_Data *in[2], *out[MAXCH];

    resona_shm_t *shm;
    int shm_is_local;
    uint32_t last_seq;
    int first;

    float pt[P_COUNT], ps[P_COUNT];
    float c_param;

    /* EQ */
    bq_c eq_c[NBANDS]; bq_s eq_s[2][NBANDS]; float eq_applied[NBANDS];
    /* Intelligent EQ */
    bq_c ieq_bp[NBANDS]; bq_s ieq_bp_s[NBANDS]; float ieq_acc[NBANDS], ieq_ms[NBANDS];
    bq_c ieq_c[NBANDS]; bq_s ieq_s[2][NBANDS]; float ieq_gain[NBANDS], ieq_applied[NBANDS];
    /* leveler */
    float lev_hp_z[2], lev_hp_a, lev_acc; unsigned lev_cnt; float lev_ms, lev_gain_db, lev_gain_lin;
    /* loudness */
    bq_c ld_lo, ld_hi; bq_s ld_lo_s[2], ld_hi_s[2]; float ld_applied, ld_boost;
    /* voice */
    bq_c vo_c; bq_s vo_s; float vo_applied;
    /* tube */
    float tube_dc_x[2], tube_dc_y[2], tube_dc_a;
    /* bass */
    bq_c bs_lp, bs_hp, bs_lp5, bs_shelf; bq_s bs_lp_s[2], bs_hp_s[2], bs_lp5_s, bs_shelf_s[2];
    float bs_env, bs_att, bs_rel, bs_applied_f, bs_applied_p; int bs_applied_t;
    bq_c bs_mud; bq_s bs_mud_s[2], bs_kick_s; float bs_fast, bs_slow, bs_fa, bs_fr, bs_sa, bs_sr, bs_kick_g, bs_applied_m;
    /* clarity */
    bq_c cl_hp, cl_hp2, cl_air; bq_s cl_hp_s[2], cl_hp2_s[2], cl_air_s[2];
    float cl_env[2], cl_att, cl_rel, cl_applied;
    /* stage */
    float st_hp_a, st_hp_z, st_lp_a, st_lp_z[2];
    float *er_buf[2]; unsigned er_mask, er_pos;
    int er_tap[2][5]; int er_src[2][5]; float er_gain[2][5];
    /* crossfeed */
    float xf_a, xf_z[2]; float xf_buf[2][256]; unsigned xf_pos, xf_delay;
    /* HRTF */
    hrtf_path hr_ipsi, hr_contra; hrtf_state hr_s[4]; float hr_gain; bq_c hr_eq; bq_s hr_eq_s[2]; float hr_applied_angle;
    /* ambience */
    float *fdn_buf[4]; unsigned fdn_mask, fdn_pos; unsigned fdn_len[4];
    float fdn_g[4], fdn_lp_z[4], fdn_lp_a, fdn_in_hp_z, fdn_in_hp_a, fdn_applied_size, fdn_applied_damp;
    /* correction EQ */
    bq_c corr_c[NCORR]; bq_s corr_s[2][NCORR]; int corr_n;
    /* upmix */
    float um_pll, um_prr, um_plr, um_a;
    bq_c um_lfe_lp; bq_s um_lfe_s[2];
    bq_c um_s_hp, um_s_lp, um_r_lp; bq_s um_s_hp_s, um_s_lp_s, um_r_lp_s;
    bq_c um_ap[4]; bq_s um_ap_s[4];
    float *um_dly; unsigned um_mask, um_pos, um_d1, um_d2;
    /* limiter */
    float *lim_buf[MAXCH]; unsigned lim_mask, lim_pos, lim_la;
    unsigned dq_idx[LIM_DQ]; float dq_val[LIM_DQ]; unsigned dq_head, dq_tail, lim_n;
    float lim_env, lim_att, lim_rel;
    float meter_in[2], meter_out[2];
} Resona;

_Static_assert(__atomic_always_lock_free(4, 0), "control snapshots require lock-free 32-bit atomics");

/* ------------------------------------------------------------------ */
/* shared memory                                                       */

static void shm_defaults(resona_shm_t *m)
{
    memset(m->params, 0, sizeof(m->params));
    memcpy(m->params, DEFAULTS, sizeof(DEFAULTS));
    m->corr_count = 0;
    m->control_seq = 0;
    m->version = RESONA_VERSION;
    __atomic_store_n(&m->magic, RESONA_MAGIC, __ATOMIC_RELEASE);
}

static void shm_open_or_local(Resona *r)
{
    char path[256];
    const char *env = getenv("RESONA_SHM");
    if (env && *env) snprintf(path, sizeof path, "%s", env);
    else snprintf(path, sizeof path, "/dev/shm/resona-%u", (unsigned)getuid());

    int fd = open(path, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
    if (fd >= 0) {
        struct stat st;
        if (fstat(fd, &st) == 0 && (size_t)st.st_size < sizeof(resona_shm_t))
            if (ftruncate(fd, sizeof(resona_shm_t)) != 0) { close(fd); fd = -1; }
    }
    if (fd >= 0) {
        void *p = mmap(NULL, sizeof(resona_shm_t), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        close(fd);
        if (p != MAP_FAILED) {
            r->shm = p;
            r->shm_is_local = 0;
            if (r->shm->magic != RESONA_MAGIC || r->shm->version != RESONA_VERSION)
                shm_defaults(r->shm);
            return;
        }
    }
    r->shm = calloc(1, sizeof(resona_shm_t));
    r->shm_is_local = 1;
    shm_defaults(r->shm);
}

/* ------------------------------------------------------------------ */
/* control-rate updates                                                */

static void update_fdn_geometry(Resona *r, float size, float damp)
{
    static const float base_ms[4] = {31.7f, 37.9f, 43.3f, 49.1f};
    float scale = 0.45f + size * 0.95f, rt60 = 0.35f + size * 2.4f;
    for (int i = 0; i < 4; i++) {
        unsigned len = (unsigned)(base_ms[i] * scale * 0.001 * r->fs);
        if (len >= r->fdn_mask) len = r->fdn_mask - 1;
        if (len < 8) len = 8;
        r->fdn_len[i] = len;
        r->fdn_g[i] = powf(10.0f, -3.0f * (float)len / (rt60 * (float)r->fs));
    }
    r->fdn_lp_a = op_coef(r->fs, 14000.0 - damp * 11500.0);
    r->fdn_applied_size = size;
    r->fdn_applied_damp = damp;
}

static double hrtf_path_power(const hrtf_path *p, double w)
{
    int di = (int)p->delay;
    double cw = cos(w), fr = p->delay - di;
    double d0 = w * (di & (HRTF_DLY - 1)), d1 = w * ((di + 1) & (HRTF_DLY - 1));
    double re = (1.0 - fr) * cos(d0) + fr * cos(d1);
    double im = -(1.0 - fr) * sin(d0) - fr * sin(d1);
    for (int k = 0; k < 5; k++) {
        double ph = w * ((di + p->tap[k]) & (HRTF_DLY - 1));
        re += p->rho[k] * cos(ph);
        im -= p->rho[k] * sin(ph);
    }
    double b0 = p->hs_b0, b1 = p->hs_b1, a1 = p->hs_a1;
    double num = b0 * b0 + b1 * b1 + 2.0 * b0 * b1 * cw;
    double den = 1.0 + a1 * a1 + 2.0 * a1 * cw;
    return num / den * (re * re + im * im);
}

/* Evaluate the transfer function directly; angle changes must stay cheap in run(). */
static double hrtf_power(const hrtf_path *a, const hrtf_path *b, double fs, double f)
{
    double w = 2 * M_PI * f / fs;
    return hrtf_path_power(a, w) + hrtf_path_power(b, w);
}

static void update_hrtf(Resona *r, float angle)
{
    angle = clampf(angle, 10, 70);
    double inc_ipsi = 90.0 - angle, inc_contra = 90.0 + angle;
    double itd = (bd_delay(inc_contra * M_PI / 180) - bd_delay(inc_ipsi * M_PI / 180)) * r->fs;
    hrtf_design_path(&r->hr_ipsi, r->fs, inc_ipsi, angle, 0);
    hrtf_design_path(&r->hr_contra, r->fs, inc_contra, 180 - angle, itd);
    /* equalise: unity power at 1 kHz, shelf away the head-shadow HF tilt */
    double p1k = hrtf_power(&r->hr_ipsi, &r->hr_contra, r->fs, 1000);
    double phf = 0; const double hf[4] = {4000, 6000, 9000, 12000};
    for (int i = 0; i < 4; i++) phf += hrtf_power(&r->hr_ipsi, &r->hr_contra, r->fs, hf[i]);
    phf /= 4;
    r->hr_gain = (float)(1.0 / sqrt(p1k));
    double shelf_db = -10.0 * log10(phf / p1k);
    bq_design(&r->hr_eq, BQ_HSHELF, r->fs, 3000, 0.7071, clampf((float)shelf_db, -12, 12));
    r->hr_applied_angle = angle;
}

static void update_corr(Resona *r, const resona_corr_t *sections, unsigned count)
{
    unsigned n = count > NCORR ? NCORR : count;
    int k = 0;
    for (unsigned i = 0; i < n; i++) {
        resona_corr_t s = sections[i];
        if (!isfinite(s.type) || s.type < CORR_PK || s.type > CORR_RAW || s.type != (int)s.type) continue;
        if (s.type != CORR_RAW && (!isfinite(s.freq) || s.freq <= 0 ||
            !isfinite(s.q) || s.q <= 0 || !isfinite(s.gain))) continue;
        bq_c *c = &r->corr_c[k];
        switch ((int)s.type) {
        case CORR_PK: bq_design(c, BQ_PEAK, r->fs, s.freq, s.q, clampf(s.gain, -24, 24)); break;
        case CORR_LSC: bq_design(c, BQ_LSHELF, r->fs, s.freq, s.q, clampf(s.gain, -24, 24)); break;
        case CORR_HSC: bq_design(c, BQ_HSHELF, r->fs, s.freq, s.q, clampf(s.gain, -24, 24)); break;
        case CORR_LP: bq_design(c, BQ_LP, r->fs, s.freq, s.q, 0); break;
        case CORR_HP: bq_design(c, BQ_HP, r->fs, s.freq, s.q, 0); break;
        case CORR_RAW:
            if (!isfinite(s.b0) || !isfinite(s.b1) || !isfinite(s.b2) || !isfinite(s.a1) || !isfinite(s.a2)) continue;
            if (fabsf(s.a2) >= 1.0f || fabsf(s.a1) >= 1.0f + s.a2) continue; /* unstable section: skip */
            c->b0 = s.b0; c->b1 = s.b1; c->b2 = s.b2; c->a1 = s.a1; c->a2 = s.a2; break;
        default: continue;
        }
        if (!isfinite(c->b0) || !isfinite(c->b1) || !isfinite(c->b2) ||
            !isfinite(c->a1) || !isfinite(c->a2)) continue;
        k++;
    }
    if (k != r->corr_n) memset(r->corr_s, 0, sizeof r->corr_s);
    r->corr_n = k;
}

static void read_controls(Resona *r)
{
    resona_shm_t *m = r->shm;
    uint32_t guard = __atomic_load_n(&m->control_seq, __ATOMIC_SEQ_CST);
    if (guard & 1u) return;
    uint32_t seq = __atomic_load_n(&m->param_seq, __ATOMIC_RELAXED);
    if (seq == r->last_seq && !r->first) return;
    float params[P_COUNT];
    resona_corr_t sections[NCORR];
    unsigned count = __atomic_load_n(&m->corr_count, __ATOMIC_RELAXED);
    if (count > NCORR) count = NCORR;
    for (int i = 0; i < P_COUNT; i++) __atomic_load(&m->params[i], &params[i], __ATOMIC_RELAXED);
    for (unsigned i = 0; i < count; i++) {
#define READ_FIELD(field) __atomic_load(&m->corr[i].field, &sections[i].field, __ATOMIC_RELAXED)
        READ_FIELD(type); READ_FIELD(freq); READ_FIELD(gain); READ_FIELD(q);
        READ_FIELD(b0); READ_FIELD(b1); READ_FIELD(b2); READ_FIELD(a1); READ_FIELD(a2);
#undef READ_FIELD
        sections[i].pad = 0;
    }
    __atomic_thread_fence(__ATOMIC_ACQUIRE);
    if (__atomic_load_n(&m->control_seq, __ATOMIC_SEQ_CST) != guard) return;
    r->last_seq = seq;
    for (int i = 0; i < P_COUNT; i++) if (isfinite(params[i])) r->pt[i] = params[i];
    update_corr(r, sections, count);
}

static void control(Resona *r, unsigned len)
{
    resona_shm_t *m = r->shm;
    read_controls(r);
    float c = r->c_param * (float)len / CTRL_BLOCK;
    for (int i = 0; i < P_COUNT; i++) {
        float d = r->pt[i] - r->ps[i];
        r->ps[i] = (r->first || fabsf(d) < 1e-5f) ? r->pt[i] : r->ps[i] + d * c;
    }
    r->first = 0;
    /* values that must never lag or glide */
    static const int snap[] = {P_LIM_CEIL, P_SYSVOL_DB, P_BASS_FREQ, P_AMB_SIZE, P_AMB_DAMP,
                               P_HRTF_ANGLE, P_IEQ_TARGET, P_LAYOUT};
    for (unsigned i = 0; i < sizeof snap / sizeof *snap; i++) r->ps[snap[i]] = r->pt[snap[i]];
    r->ps[P_BASS_FREQ] = clampf(r->ps[P_BASS_FREQ], 40, 250);
    r->ps[P_AMB_SIZE] = clampf(r->ps[P_AMB_SIZE], 0, 1);
    r->ps[P_AMB_DAMP] = clampf(r->ps[P_AMB_DAMP], 0, 1);

    const float *p = r->ps;

    for (int b = 0; b < NBANDS; b++) {
        float g = clampf(p[P_EQ0 + b], -15, 15) * clampf(p[P_EQ_ON], 0, 1);
        if (fabsf(g - r->eq_applied[b]) > 0.01f) {
            bq_design(&r->eq_c[b], BQ_PEAK, r->fs, EQ_FREQS[b], 1.1, g);
            r->eq_applied[b] = g;
        }
    }

    /* Smart Leveler (volume-aware: PipeWire applies sink volume before us) */
    if (r->lev_cnt > 0) {
        float blk = r->lev_acc / (float)r->lev_cnt;
        r->lev_ms += (blk - r->lev_ms) * tc_coef(r->fs, 0.4, r->lev_cnt);
    }
    float level_db = 10.0f * log10f(r->lev_ms + 1e-12f) + 3.0f;
    float vol_db = clampf(p[P_SYSVOL_DB], -120, 0);
    float content_db = level_db - vol_db;
    m->content_db = content_db;
    float lev_amt = clampf(p[P_LEV_ON], 0, 1) * clampf(p[P_LEV_AMT], 0, 1);
    float lev_tgt = clampf(p[P_LEV_TARGET], -30, -6);
    float tgt_db = r->lev_gain_db;
    if (content_db > -55.0f) tgt_db = clampf(lev_tgt - content_db, -12.0f, 12.0f) * lev_amt;
    else if (lev_amt < 0.01f) tgt_db = 0;
    float tc = tgt_db < r->lev_gain_db ? (content_db + r->lev_gain_db > lev_tgt + 6.0f ? 0.05f : 0.3f) : 2.5f;
    r->lev_gain_db += (tgt_db - r->lev_gain_db) * tc_coef(r->fs, tc, len);
    m->leveler_db = r->lev_gain_db;

    /* Intelligent EQ: steer the long-term tonal balance toward a target */
    {
        float amt = clampf(p[P_IEQ_ON], 0, 1) * clampf(p[P_IEQ_AMT], 0, 1);
        int target = (int)clampf(p[P_IEQ_TARGET], 0, 3);
        if (r->lev_cnt > 0) {
            float k = tc_coef(r->fs, 2.5, r->lev_cnt);
            for (int b = 0; b < NBANDS; b++) {
                r->ieq_ms[b] += (r->ieq_acc[b] / (float)r->lev_cnt - r->ieq_ms[b]) * k;
                r->ieq_acc[b] = 0;
            }
        }
        float db[NBANDS], mean = 0;
        for (int b = 0; b < NBANDS; b++) {
            db[b] = 10.0f * log10f(r->ieq_ms[b] + 1e-14f);
            if (b >= 2 && b <= 7) mean += db[b];
        }
        mean /= 6.0f;
        int active = content_db > -50.0f && amt > 0.001f;
        float k = tc_coef(r->fs, 3.0, len);
        for (int b = 0; b < NBANDS; b++) {
            float want = 0;
            if (active) {
                float dev = (db[b] - mean) - IEQ_TARGETS[target][b];
                want = clampf(-dev * 0.6f, -6.0f, 4.5f) * amt;
                if (db[b] < mean - 45.0f) want = 0;   /* band basically empty: don't chase it */
            } else if (amt > 0.001f) {
                want = r->ieq_gain[b];                /* silence: hold */
            }
            r->ieq_gain[b] += (want - r->ieq_gain[b]) * k;
            if (fabsf(r->ieq_gain[b] - r->ieq_applied[b]) > 0.05f) {
                bq_design(&r->ieq_c[b], BQ_PEAK, r->fs, EQ_FREQS[b], 1.1, r->ieq_gain[b]);
                r->ieq_applied[b] = r->ieq_gain[b];
            }
            m->ieq_db[b] = r->ieq_gain[b];
        }
    }
    r->lev_acc = 0;
    r->lev_cnt = 0;

    /* Adaptive Loudness */
    float listen_db = level_db + r->lev_gain_db + 18.0f;
    float deficit = clampf(-6.0f - listen_db, 0.0f, 40.0f);
    float ld_amt = clampf(p[P_LOUD_ON], 0, 1) * clampf(p[P_LOUD_AMT], 0, 1);
    r->ld_boost += (deficit * ld_amt - r->ld_boost) * tc_coef(r->fs, 1.0, len);
    if (fabsf(r->ld_boost - r->ld_applied) > 0.05f) {
        bq_design(&r->ld_lo, BQ_LSHELF, r->fs, 90, 0.7071, clampf(r->ld_boost * 0.32f, 0, 10));
        bq_design(&r->ld_hi, BQ_HSHELF, r->fs, 9000, 0.7071, clampf(r->ld_boost * 0.12f, 0, 4));
        r->ld_applied = r->ld_boost;
    }
    m->loudness_db = clampf(r->ld_boost * 0.32f, 0, 10);

    float vo = clampf(p[P_VOICE_ON], 0, 1) * clampf(p[P_VOICE_AMT], 0, 1);
    if (fabsf(vo - r->vo_applied) > 0.002f) {
        bq_design(&r->vo_c, BQ_PEAK, r->fs, 2400, 0.8, vo * 8.0);
        r->vo_applied = vo;
    }

    float bf = p[P_BASS_FREQ];
    float punch = clampf(p[P_BASS_ON], 0, 1) * clampf(p[P_BASS_PUNCH], 0, 1);
    int bt = (int)clampf(p[P_BASS_TYPE], 0, BASS_NTYPES - 1);
    const bass_voice *bv = &BASS_VOICES[bt];
    if (bt != r->bs_applied_t) { r->bs_applied_t = bt; r->bs_applied_p = -1; r->bs_applied_m = -1; }
    if (bf != r->bs_applied_f) {
        bq_design(&r->bs_lp, BQ_LP, r->fs, bf, 0.7071, 0);
        bq_design(&r->bs_hp, BQ_HP, r->fs, bf, 0.7071, 0);
        bq_design(&r->bs_lp5, BQ_LP, r->fs, bf * 5.0, 0.7071, 0);
        r->bs_applied_f = bf;
        r->bs_applied_p = -1;
    }
    if (fabsf(punch - r->bs_applied_p) > 0.002f) {
        bq_design(&r->bs_shelf, bv->filt, r->fs, clampf(bf * bv->f_mul, bv->f_min, bv->f_max), bv->q,
                  punch * bv->gain_db);
        r->bs_applied_p = punch;
    }
    float mud = clampf(p[P_BASS_ON], 0, 1) * clampf(p[P_BASS_AMT], 0, 1) * bv->mud_db;
    if (fabsf(mud - r->bs_applied_m) > 0.01f) {
        bq_design(&r->bs_mud, BQ_PEAK, r->fs, 280, 1.0, mud);
        r->bs_applied_m = mud;
    }

    float cl = clampf(p[P_CLAR_ON], 0, 1) * clampf(p[P_CLAR_AMT], 0, 1);
    if (fabsf(cl - r->cl_applied) > 0.002f) {
        bq_design(&r->cl_air, BQ_HSHELF, r->fs, 10000, 0.7071, cl * 4.0);
        r->cl_applied = cl;
    }

    if (fabsf(p[P_AMB_SIZE] - r->fdn_applied_size) > 0.01f || fabsf(p[P_AMB_DAMP] - r->fdn_applied_damp) > 0.01f)
        update_fdn_geometry(r, p[P_AMB_SIZE], p[P_AMB_DAMP]);
    if (fabsf(clampf(p[P_HRTF_ANGLE], 10, 70) - r->hr_applied_angle) > 0.5f)
        update_hrtf(r, p[P_HRTF_ANGLE]);
}

/* ------------------------------------------------------------------ */

static void reset_state(Resona *r)
{
    memset(r->eq_s, 0, sizeof r->eq_s);
    memset(r->ieq_s, 0, sizeof r->ieq_s); memset(r->ieq_bp_s, 0, sizeof r->ieq_bp_s);
    memset(r->ieq_acc, 0, sizeof r->ieq_acc); memset(r->ieq_ms, 0, sizeof r->ieq_ms);
    r->lev_acc = r->lev_ms = r->lev_gain_db = 0;
    r->lev_cnt = 0; r->lev_gain_lin = 1;
    memset(r->ld_lo_s, 0, sizeof r->ld_lo_s); memset(r->ld_hi_s, 0, sizeof r->ld_hi_s);
    memset(&r->vo_s, 0, sizeof r->vo_s);
    memset(r->tube_dc_x, 0, sizeof r->tube_dc_x); memset(r->tube_dc_y, 0, sizeof r->tube_dc_y);
    memset(r->bs_lp_s, 0, sizeof r->bs_lp_s); memset(r->bs_hp_s, 0, sizeof r->bs_hp_s);
    memset(&r->bs_lp5_s, 0, sizeof r->bs_lp5_s); memset(r->bs_shelf_s, 0, sizeof r->bs_shelf_s);
    memset(r->bs_mud_s, 0, sizeof r->bs_mud_s); memset(&r->bs_kick_s, 0, sizeof r->bs_kick_s);
    r->bs_fast = r->bs_slow = 0; r->bs_kick_g = 0;
    memset(r->cl_hp_s, 0, sizeof r->cl_hp_s); memset(r->cl_hp2_s, 0, sizeof r->cl_hp2_s);
    memset(r->cl_air_s, 0, sizeof r->cl_air_s);
    memset(r->hr_s, 0, sizeof r->hr_s); memset(r->hr_eq_s, 0, sizeof r->hr_eq_s);
    memset(r->corr_s, 0, sizeof r->corr_s);
    memset(r->um_lfe_s, 0, sizeof r->um_lfe_s);
    memset(&r->um_s_hp_s, 0, sizeof r->um_s_hp_s); memset(&r->um_s_lp_s, 0, sizeof r->um_s_lp_s);
    memset(&r->um_r_lp_s, 0, sizeof r->um_r_lp_s); memset(r->um_ap_s, 0, sizeof r->um_ap_s);
    r->um_pll = r->um_prr = r->um_plr = 0;
    r->bs_env = 0; r->cl_env[0] = r->cl_env[1] = 0;
    r->lev_hp_z[0] = r->lev_hp_z[1] = 0;
    r->st_hp_z = 0; r->st_lp_z[0] = r->st_lp_z[1] = 0;
    r->xf_z[0] = r->xf_z[1] = 0; memset(r->xf_buf, 0, sizeof r->xf_buf);
    for (int i = 0; i < 2; i++) memset(r->er_buf[i], 0, (r->er_mask + 1) * sizeof(float));
    for (int i = 0; i < MAXCH; i++) memset(r->lim_buf[i], 0, (r->lim_mask + 1) * sizeof(float));
    for (int i = 0; i < 4; i++) { memset(r->fdn_buf[i], 0, (r->fdn_mask + 1) * sizeof(float)); r->fdn_lp_z[i] = 0; }
    memset(r->um_dly, 0, (r->um_mask + 1) * sizeof(float));
    r->fdn_in_hp_z = 0;
    r->dq_head = r->dq_tail = 0;
    r->lim_env = 1;
}

static LADSPA_Handle instantiate_n(unsigned long rate, unsigned nout)
{
    Resona *r = calloc(1, sizeof(Resona));
    if (!r) return NULL;
    r->fs = (double)rate;
    r->nout = nout;
    shm_open_or_local(r);
    r->shm->sample_rate = (uint32_t)rate;
    r->shm->out_channels = nout;

    r->er_mask = next_pow2((unsigned)(0.05 * r->fs)) - 1;
    r->fdn_mask = next_pow2((unsigned)(0.1 * r->fs)) - 1;
    r->um_mask = next_pow2((unsigned)(0.04 * r->fs)) - 1;
    r->lim_la = (unsigned)(0.0015 * r->fs);
    if (r->lim_la < 8) r->lim_la = 8;
    if (r->lim_la > LIM_DQ / 2) r->lim_la = LIM_DQ / 2;
    r->lim_mask = next_pow2(r->lim_la + 1) - 1;
    for (int i = 0; i < 2; i++) r->er_buf[i] = calloc(r->er_mask + 1, sizeof(float));
    for (int i = 0; i < MAXCH; i++) r->lim_buf[i] = calloc(r->lim_mask + 1, sizeof(float));
    for (int i = 0; i < 4; i++) r->fdn_buf[i] = calloc(r->fdn_mask + 1, sizeof(float));
    r->um_dly = calloc(r->um_mask + 1, sizeof(float));

    for (int i = 0; i < P_COUNT; i++) r->pt[i] = r->ps[i] = DEFAULTS[i];
    r->first = 1;
    r->c_param = tc_coef(r->fs, 0.03, CTRL_BLOCK);

    r->lev_hp_a = op_coef(r->fs, 120);
    r->lev_ms = 1e-4f; r->lev_gain_lin = 1;
    for (int b = 0; b < NBANDS; b++) {
        bq_design(&r->ieq_bp[b], BQ_BP, r->fs, EQ_FREQS[b], 1.4, 0);
        r->ieq_ms[b] = 1e-6f;
        r->ieq_applied[b] = 0;
        r->eq_applied[b] = 999;
    }
    r->tube_dc_a = 1.0f - op_coef(r->fs, 8);
    r->bs_att = tc_coef(r->fs, 0.002, 1); r->bs_rel = tc_coef(r->fs, 0.06, 1);
    r->bs_fa = tc_coef(r->fs, 0.0008, 1); r->bs_fr = tc_coef(r->fs, 0.03, 1);
    r->bs_sa = tc_coef(r->fs, 0.04, 1); r->bs_sr = tc_coef(r->fs, 0.25, 1);
    r->cl_att = tc_coef(r->fs, 0.001, 1); r->cl_rel = tc_coef(r->fs, 0.03, 1);
    bq_design(&r->cl_hp, BQ_HP, r->fs, 3000, 0.7071, 0);
    bq_design(&r->cl_hp2, BQ_HP, r->fs, 4500, 0.7071, 0);
    r->st_hp_a = op_coef(r->fs, 180);
    r->st_lp_a = op_coef(r->fs, 5500);
    r->xf_a = op_coef(r->fs, 700);
    r->xf_delay = (unsigned)(0.00028 * r->fs);
    if (r->xf_delay > 255) r->xf_delay = 255;
    if (r->xf_delay < 1) r->xf_delay = 1;
    r->fdn_in_hp_a = op_coef(r->fs, 220);

    static const float er_ms[2][5] = {{5.3f, 9.7f, 14.1f, 19.3f, 27.2f}, {6.1f, 8.9f, 15.7f, 21.1f, 25.6f}};
    static const int er_opp[5] = {1, 0, 1, 0, 1};
    static const float er_g[5] = {0.42f, 0.30f, 0.24f, 0.17f, 0.12f};
    for (int c = 0; c < 2; c++)
        for (int k = 0; k < 5; k++) {
            int t = (int)(er_ms[c][k] * 0.001 * r->fs);
            if (t > (int)r->er_mask) t = r->er_mask;
            r->er_tap[c][k] = t;
            r->er_src[c][k] = er_opp[k] ? 1 - c : c;
            r->er_gain[c][k] = er_g[k] * (er_opp[k] ? 1.0f : -1.0f);
        }

    r->um_a = tc_coef(r->fs, 0.02, 1);
    bq_design(&r->um_lfe_lp, BQ_LP, r->fs, 120, 0.7071, 0);
    bq_design(&r->um_s_hp, BQ_HP, r->fs, 120, 0.7071, 0);
    bq_design(&r->um_s_lp, BQ_LP, r->fs, 7000, 0.7071, 0);
    bq_design(&r->um_r_lp, BQ_LP, r->fs, 5000, 0.7071, 0);
    static const double ap_f[4] = {420, 1350, 610, 1900};
    for (int i = 0; i < 4; i++) bq_design(&r->um_ap[i], BQ_AP, r->fs, ap_f[i], 0.6, 0);
    r->um_d1 = (unsigned)(0.012 * r->fs);
    r->um_d2 = (unsigned)(0.020 * r->fs);

    r->lim_att = tc_coef(r->fs, (double)r->lim_la / 4.0 / r->fs, 1);
    r->lim_rel = tc_coef(r->fs, 0.08, 1);

    r->ld_applied = -1; r->vo_applied = -1; r->bs_applied_f = -1; r->bs_applied_p = -1;
    r->bs_applied_t = -1; r->bs_applied_m = -1; r->bs_kick_g = 0;
    r->cl_applied = -1; r->fdn_applied_size = -1; r->hr_applied_angle = -100;
    update_fdn_geometry(r, DEFAULTS[P_AMB_SIZE], DEFAULTS[P_AMB_DAMP]);
    update_hrtf(r, DEFAULTS[P_HRTF_ANGLE]);
    reset_state(r);
    return r;
}

static LADSPA_Handle instantiate2(const LADSPA_Descriptor *d, unsigned long rate) { (void)d; return instantiate_n(rate, 2); }
static LADSPA_Handle instantiate8(const LADSPA_Descriptor *d, unsigned long rate) { (void)d; return instantiate_n(rate, 8); }

static void connect_port(LADSPA_Handle h, unsigned long port, LADSPA_Data *data)
{
    Resona *r = h;
    if (port < 2) r->in[port] = data;
    else if (port - 2 < MAXCH) r->out[port - 2] = data;
}

static void activate(LADSPA_Handle h) { reset_state((Resona *)h); }

static void run(LADSPA_Handle h, unsigned long n)
{
    Resona *r = h;
    resona_shm_t *m = r->shm;
    if (!r->in[0] || !r->in[1] || !r->out[0] || !r->out[1]) return;
#if defined(__SSE__)
    unsigned csr = _mm_getcsr();
    _mm_setcsr(csr | 0x8040); /* FTZ | DAZ */
#endif
    float pk_in[2] = {0, 0}, pk_out[2] = {0, 0};
    unsigned ring = m->ring_pos;
    const unsigned nout = r->nout;

    for (unsigned long off = 0; off < n;) {
        unsigned len = (unsigned)((n - off) < CTRL_BLOCK ? (n - off) : CTRL_BLOCK);
        float lev_from = r->lev_gain_lin;
        control(r, len);
        const float *p = r->ps;
        float lev_to = db2lin(r->lev_gain_db);
        r->lev_gain_lin = lev_to;
        float lev_step = (lev_to - lev_from) / (float)len;

        int surround = nout > 2;
        int layout = surround ? (int)p[P_LAYOUT] : 0;
        float wet = clampf(p[P_ENABLE], 0, 1);
        float pre = db2lin(clampf(p[P_PREAMP], -24, 12));
        float post = db2lin(clampf(p[P_OUTPUT], -24, 12));
        float vo = r->vo_applied;
        float tube = clampf(p[P_TUBE_ON], 0, 1) * clampf(p[P_TUBE_DRIVE], 0, 1);
        float tube_d = 1.0f + 5.0f * tube, tube_b = 0.18f;
        float tube_t0 = tanhf(tube_d * tube_b), tube_norm = 1.0f / (tube_d * (1.0f - tube_t0 * tube_t0));
        const bass_voice *bv = &BASS_VOICES[r->bs_applied_t < 0 ? 0 : r->bs_applied_t];
        float bass_on = clampf(p[P_BASS_ON], 0, 1) * clampf(p[P_BASS_AMT], 0, 1);
        float bass_h = bass_on * 1.8f * bv->harm;
        float kick = bass_on * bv->kick * clampf(p[P_BASS_PUNCH] * 1.5f + 0.4f, 0, 1.5f);
        float cl = r->cl_applied;
        float st_on = clampf(p[P_STAGE_ON], 0, 1);
        float width = 1.0f + (clampf(p[P_STAGE_WIDTH], 0, 2) - 1.0f) * st_on;
        float depth = clampf(p[P_STAGE_DEPTH], 0, 1) * st_on * 0.55f;
        float hrtf_on = surround ? 0 : clampf(p[P_HRTF_ON], 0, 1);
        float hrtf = hrtf_on * clampf(p[P_HRTF_AMT], 0, 1);
        float xf = surround ? 0 : clampf(p[P_XFEED_ON], 0, 1) * clampf(p[P_XFEED_AMT], 0, 1) * 0.55f * (1.0f - hrtf_on);
        float amb = clampf(p[P_AMB_ON], 0, 1) * clampf(p[P_AMB_MIX], 0, 1) * 0.6f;
        float corr_on = clampf(p[P_CORR_ON], 0, 1);
        float corr_pre = db2lin(clampf(p[P_CORR_PREAMP], -30, 12));
        float um = surround ? clampf(p[P_UPMIX_ON], 0, 1) * wet : 0;
        float um_c = (layout & 1) ? clampf(p[P_UPMIX_CENTER], 0, 1) * um : 0;
        float um_s = clampf(p[P_UPMIX_SURR], 0, 1) * um;
        float um_lfe = (layout & 2) ? clampf(p[P_UPMIX_LFE], 0, 1) * um : 0;
        int has_side = layout & 4, has_rear = layout & 8;
        float lim_on = clampf(p[P_LIM_ON], 0, 1) * wet;
        float ceil_lin = db2lin(clampf(p[P_LIM_CEIL], -12, 0));
        float lim_thr = lim_on > 0.5f ? ceil_lin : 4.0f;
        int ieq_any = 0;
        for (int b = 0; b < NBANDS; b++) if (fabsf(r->ieq_applied[b]) > 0.05f) ieq_any = 1;

        for (unsigned i = 0; i < len; i++) {
            float xl = r->in[0][off + i], xr = r->in[1][off + i];
            if (!isfinite(xl)) xl = 0;
            if (!isfinite(xr)) xr = 0;
            /* Discard corrupt finite samples before squaring detector inputs. */
            if (fabsf(xl) > 1e6f) xl = 0;
            if (fabsf(xr) > 1e6f) xr = 0;
            float al = fabsf(xl), ar = fabsf(xr);
            if (al > pk_in[0]) pk_in[0] = al;
            if (ar > pk_in[1]) pk_in[1] = ar;
            m->ring_in[ring & (RESONA_RING - 1)] = 0.5f * (xl + xr);

            float L = xl * pre, R = xr * pre;

            /* detector + leveler gain */
            {
                float hl = L - r->lev_hp_z[0]; r->lev_hp_z[0] += r->lev_hp_a * hl;
                float hr = R - r->lev_hp_z[1]; r->lev_hp_z[1] += r->lev_hp_a * hr;
                r->lev_acc += 0.5f * (hl * hl + hr * hr);
                r->lev_cnt++;
                float g = lev_from + lev_step * (float)(i + 1);
                L *= g; R *= g;
            }

            /* Intelligent EQ: analyse mono band energies, apply steered bands */
            {
                float mono = 0.5f * (L + R);
                for (int b = 0; b < NBANDS; b++) {
                    float y = bq(&r->ieq_bp[b], &r->ieq_bp_s[b], mono);
                    r->ieq_acc[b] += y * y;
                }
                if (ieq_any)
                    for (int b = 0; b < NBANDS; b++) {
                        if (fabsf(r->ieq_applied[b]) <= 0.05f) continue;
                        L = bq(&r->ieq_c[b], &r->ieq_s[0][b], L);
                        R = bq(&r->ieq_c[b], &r->ieq_s[1][b], R);
                    }
            }

            for (int b = 0; b < NBANDS; b++) {
                if (r->eq_applied[b] == 0) continue;
                L = bq(&r->eq_c[b], &r->eq_s[0][b], L);
                R = bq(&r->eq_c[b], &r->eq_s[1][b], R);
            }

            if (r->ld_applied > 0.05f) {
                L = bq(&r->ld_hi, &r->ld_hi_s[0], bq(&r->ld_lo, &r->ld_lo_s[0], L));
                R = bq(&r->ld_hi, &r->ld_hi_s[1], bq(&r->ld_lo, &r->ld_lo_s[1], R));
            }

            if (vo > 0.002f) {
                float M = 0.5f * (L + R), S = 0.5f * (L - R);
                M = bq(&r->vo_c, &r->vo_s, M);
                S *= 1.0f - 0.35f * vo;
                L = M + S; R = M - S;
            }

            /* Tube Warmth: biased tanh stage → even harmonics, DC-blocked */
            if (tube > 0.002f) {
                float in2[2] = {L, R};
                for (int c = 0; c < 2; c++) {
                    float sat = (tanhf(tube_d * (in2[c] + tube_b)) - tube_t0) * tube_norm;
                    float y = sat - r->tube_dc_x[c] + r->tube_dc_a * r->tube_dc_y[c];
                    r->tube_dc_x[c] = sat; r->tube_dc_y[c] = y;
                    in2[c] += (y - in2[c]) * tube;
                }
                L = in2[0]; R = in2[1];
            }

            if (r->bs_applied_p > 0.002f) {
                L = bq(&r->bs_shelf, &r->bs_shelf_s[0], L);
                R = bq(&r->bs_shelf, &r->bs_shelf_s[1], R);
            }
            if (fabsf(r->bs_applied_m) > 0.01f) {
                L = bq(&r->bs_mud, &r->bs_mud_s[0], L);
                R = bq(&r->bs_mud, &r->bs_mud_s[1], R);
            }
            if (kick > 0.001f) {
                /* transient kick: a 12 dB/oct low band (never cancels the dry signal) is pushed up
                   while the fast envelope runs ahead of the slow one, i.e. on drum hits */
                float lo1 = bq(&r->bs_lp, &r->bs_kick_s, 0.5f * (L + R));
                float a = fabsf(lo1);
                r->bs_fast += (a - r->bs_fast) * (a > r->bs_fast ? r->bs_fa : r->bs_fr);
                r->bs_slow += (a - r->bs_slow) * (a > r->bs_slow ? r->bs_sa : r->bs_sr);
                float t = clampf(r->bs_fast / (r->bs_slow + 1e-6f) - 1.0f, 0, 2.0f);
                float g = t * kick * 0.7f;
                r->bs_kick_g += (g - r->bs_kick_g) * 0.01f;
                L += lo1 * r->bs_kick_g; R += lo1 * r->bs_kick_g;
            } else if (r->bs_kick_g != 0) {
                r->bs_kick_g = 0; r->bs_fast = r->bs_slow = 0;
            }
            if (bass_h > 0.001f) {
                float mono = 0.5f * (L + R);
                float lo = bq(&r->bs_lp, &r->bs_lp_s[1], bq(&r->bs_lp, &r->bs_lp_s[0], mono));
                float a = fabsf(lo);
                r->bs_env += (a - r->bs_env) * (a > r->bs_env ? r->bs_att : r->bs_rel);
                float nrm = clampf(lo / (r->bs_env + 1e-5f), -1.5f, 1.5f);
                float shaped = r->bs_env * (bv->even * fabsf(nrm) + bv->odd * nrm * nrm * nrm);
                float hh = bq(&r->bs_hp, &r->bs_hp_s[1], bq(&r->bs_hp, &r->bs_hp_s[0], shaped));
                hh = bq(&r->bs_lp5, &r->bs_lp5_s, hh) * bass_h;
                L += hh; R += hh;
            }

            if (cl > 0.002f) {
                float hl = bq(&r->cl_hp, &r->cl_hp_s[0], L), hr = bq(&r->cl_hp, &r->cl_hp_s[1], R);
                float el = fabsf(hl), er = fabsf(hr);
                r->cl_env[0] += (el - r->cl_env[0]) * (el > r->cl_env[0] ? r->cl_att : r->cl_rel);
                r->cl_env[1] += (er - r->cl_env[1]) * (er > r->cl_env[1] ? r->cl_att : r->cl_rel);
                float nl = clampf(hl / (r->cl_env[0] + 1e-5f), -1.5f, 1.5f);
                float nr = clampf(hr / (r->cl_env[1] + 1e-5f), -1.5f, 1.5f);
                float xl2 = bq(&r->cl_hp2, &r->cl_hp2_s[0], r->cl_env[0] * nl * fabsf(nl));
                float xr2 = bq(&r->cl_hp2, &r->cl_hp2_s[1], r->cl_env[1] * nr * fabsf(nr));
                L = bq(&r->cl_air, &r->cl_air_s[0], L + xl2 * cl * 0.7f);
                R = bq(&r->cl_air, &r->cl_air_s[1], R + xr2 * cl * 0.7f);
            }

            /* 3D Stage */
            {
                float M = 0.5f * (L + R), S = 0.5f * (L - R);
                float shp = S - r->st_hp_z; r->st_hp_z += r->st_hp_a * shp;
                S = (S - shp) + shp * width;
                L = M + S; R = M - S;
                r->st_lp_z[0] += r->st_lp_a * (L - r->st_lp_z[0]);
                r->st_lp_z[1] += r->st_lp_a * (R - r->st_lp_z[1]);
                r->er_buf[0][r->er_pos] = r->st_lp_z[0];
                r->er_buf[1][r->er_pos] = r->st_lp_z[1];
                if (depth > 0.001f) {
                    float add[2] = {0, 0};
                    for (int c = 0; c < 2; c++)
                        for (int k = 0; k < 5; k++)
                            add[c] += r->er_gain[c][k] * r->er_buf[r->er_src[c][k]][(r->er_pos - r->er_tap[c][k]) & r->er_mask];
                    L += add[0] * depth; R += add[1] * depth;
                }
                r->er_pos = (r->er_pos + 1) & r->er_mask;
            }

            /* Crossfeed */
            {
                r->xf_z[0] += r->xf_a * (L - r->xf_z[0]);
                r->xf_z[1] += r->xf_a * (R - r->xf_z[1]);
                r->xf_buf[0][r->xf_pos] = r->xf_z[0];
                r->xf_buf[1][r->xf_pos] = r->xf_z[1];
                if (xf > 0.001f) {
                    unsigned rd = (r->xf_pos - r->xf_delay) & 255;
                    float nl = L + xf * r->xf_buf[1][rd], nr = R + xf * r->xf_buf[0][rd];
                    float norm = 1.0f / (1.0f + 0.5f * xf);
                    L = nl * norm; R = nr * norm;
                }
                r->xf_pos = (r->xf_pos + 1) & 255;
            }

            /* HRTF Spatial: virtual speakers at ±angle rendered binaurally */
            if (hrtf > 0.001f) {
                float ll = hrtf_run(&r->hr_ipsi, &r->hr_s[0], L);
                float rl = hrtf_run(&r->hr_contra, &r->hr_s[1], R);
                float rr = hrtf_run(&r->hr_ipsi, &r->hr_s[2], R);
                float lr = hrtf_run(&r->hr_contra, &r->hr_s[3], L);
                float bl = bq(&r->hr_eq, &r->hr_eq_s[0], (ll + rl) * r->hr_gain);
                float br = bq(&r->hr_eq, &r->hr_eq_s[1], (rr + lr) * r->hr_gain);
                L += (bl - L) * hrtf; R += (br - R) * hrtf;
            }

            /* Ambience */
            float wl = 0, wr = 0;
            if (amb > 0.001f) {
                float in = 0.5f * (L + R);
                float hp = in - r->fdn_in_hp_z; r->fdn_in_hp_z += r->fdn_in_hp_a * hp;
                float o4[4], sum = 0;
                for (int k = 0; k < 4; k++) {
                    float v = r->fdn_buf[k][(r->fdn_pos - r->fdn_len[k]) & r->fdn_mask];
                    r->fdn_lp_z[k] += r->fdn_lp_a * (v - r->fdn_lp_z[k]);
                    o4[k] = r->fdn_lp_z[k];
                    sum += o4[k];
                }
                for (int k = 0; k < 4; k++) {
                    float fb = (o4[k] - 0.5f * sum) * r->fdn_g[k];
                    r->fdn_buf[k][r->fdn_pos] = fb + hp * ((k & 1) ? -0.5f : 0.5f);
                }
                r->fdn_pos = (r->fdn_pos + 1) & r->fdn_mask;
                wl = amb * (o4[0] + 0.6f * o4[2]);
                wr = amb * (o4[1] + 0.6f * o4[3]);
                float front = um_s > 0.01f ? 0.35f : 1.0f; /* with surrounds, the room lives behind you */
                L += wl * front; R += wr * front;
            }

            /* Correction EQ (transducer compensation) */
            if (corr_on > 0.001f && r->corr_n > 0) {
                float cL = L * corr_pre, cR = R * corr_pre;
                for (int k = 0; k < r->corr_n; k++) {
                    cL = bq(&r->corr_c[k], &r->corr_s[0][k], cL);
                    cR = bq(&r->corr_c[k], &r->corr_s[1][k], cR);
                }
                L += (cL - L) * corr_on; R += (cR - R) * corr_on;
            }

            L *= post; R *= post;
            L = xl + (L - xl) * wet;
            R = xr + (R - xr) * wet;

            /* Surround upmix: correlation-steered centre, decorrelated surrounds, LFE */
            float o[MAXCH] = {L, R, 0, 0, 0, 0, 0, 0};
            if (surround) {
                r->um_pll += (L * L - r->um_pll) * r->um_a;
                r->um_prr += (R * R - r->um_prr) * r->um_a;
                r->um_plr += (L * R - r->um_plr) * r->um_a;
                float corr = clampf(r->um_plr / sqrtf(r->um_pll * r->um_prr + 1e-12f), 0, 1);
                float bal = (r->um_pll + 1e-12f) / (r->um_prr + 1e-12f);
                float even = clampf(1.0f - fabsf(log10f(bal)) * 0.5f, 0, 1); /* panned content stays put */
                float gc = um_c * corr * corr * even;
                float M = 0.5f * (L + R), S = 0.5f * (L - R);
                float C = gc * M;
                o[CH_FL] = L - C; o[CH_FR] = R - C;
                o[CH_FC] = C * 1.2f;
                if (um_lfe > 0.001f)
                    o[CH_LFE] = bq(&r->um_lfe_lp, &r->um_lfe_s[1], bq(&r->um_lfe_lp, &r->um_lfe_s[0], M)) * um_lfe;
                float s = bq(&r->um_s_lp, &r->um_s_lp_s, bq(&r->um_s_hp, &r->um_s_hp_s, S));
                r->um_dly[r->um_pos] = s;
                float sd = r->um_dly[(r->um_pos - r->um_d1) & r->um_mask];
                float sd2 = bq(&r->um_r_lp, &r->um_r_lp_s, r->um_dly[(r->um_pos - r->um_d2) & r->um_mask]);
                r->um_pos = (r->um_pos + 1) & r->um_mask;
                float sl = bq(&r->um_ap[0], &r->um_ap_s[0], sd), sr = -bq(&r->um_ap[1], &r->um_ap_s[1], sd);
                float bl = bq(&r->um_ap[2], &r->um_ap_s[2], sd2), br = -bq(&r->um_ap[3], &r->um_ap_s[3], sd2);
                sl = (sl * 1.4f + wl * 0.8f) * um_s; sr = (sr * 1.4f + wr * 0.8f) * um_s;
                if (has_side && has_rear) {
                    o[CH_SL] = sl; o[CH_SR] = sr;
                    o[CH_RL] = (bl + wl * 0.6f) * um_s; o[CH_RR] = (br + wr * 0.6f) * um_s;
                } else if (has_side) {
                    o[CH_SL] = sl; o[CH_SR] = sr;
                } else if (has_rear) {
                    o[CH_RL] = sl; o[CH_RR] = sr;
                }
            }

            /* linked lookahead limiter over every channel */
            {
                float pk = 0;
                for (unsigned c = 0; c < nout; c++) { float a = fabsf(o[c]); if (a > pk) pk = a; }
                float need = pk > lim_thr ? lim_thr / pk : 1.0f;
                unsigned nidx = r->lim_n++;
                while (r->dq_tail != r->dq_head && r->dq_val[(r->dq_tail - 1) & (LIM_DQ - 1)] >= need) r->dq_tail--;
                r->dq_idx[r->dq_tail & (LIM_DQ - 1)] = nidx;
                r->dq_val[r->dq_tail & (LIM_DQ - 1)] = need;
                r->dq_tail++;
                while ((unsigned)(nidx - r->dq_idx[r->dq_head & (LIM_DQ - 1)]) > r->lim_la) r->dq_head++;
                float gm = r->dq_val[r->dq_head & (LIM_DQ - 1)];
                r->lim_env += (gm - r->lim_env) * (gm < r->lim_env ? r->lim_att : r->lim_rel);
                unsigned rd = (r->lim_pos - r->lim_la) & r->lim_mask;
                int bad = 0;
                for (unsigned c = 0; c < nout; c++) {
                    r->lim_buf[c][r->lim_pos] = o[c];
                    float y = clampf(r->lim_buf[c][rd] * r->lim_env, -lim_thr, lim_thr);
                    if (!isfinite(y)) bad = 1;
                    o[c] = y;
                }
                r->lim_pos = (r->lim_pos + 1) & r->lim_mask;
                if (bad) { reset_state(r); memset(o, 0, sizeof o); }
            }

            for (unsigned c = 0; c < nout; c++)
                if (r->out[c]) r->out[c][off + i] = o[c];
            float ol = fabsf(o[0]), orr = fabsf(o[1]);
            if (ol > pk_out[0]) pk_out[0] = ol;
            if (orr > pk_out[1]) pk_out[1] = orr;
            m->ring_out[ring & (RESONA_RING - 1)] = 0.5f * (o[0] + o[1]);
            ring++;
        }
        off += len;
    }

    for (int c = 0; c < 2; c++) {
        r->meter_in[c] = pk_in[c] > r->meter_in[c] * 0.92f ? pk_in[c] : r->meter_in[c] * 0.92f;
        r->meter_out[c] = pk_out[c] > r->meter_out[c] * 0.92f ? pk_out[c] : r->meter_out[c] * 0.92f;
        m->in_peak[c] = r->meter_in[c];
        m->out_peak[c] = r->meter_out[c];
    }
    m->limiter_gr_db = lin2db(r->lim_env);
    __atomic_store_n(&m->ring_pos, ring, __ATOMIC_RELEASE);
    __atomic_add_fetch(&m->heartbeat, 1, __ATOMIC_RELAXED);
#if defined(__SSE__)
    _mm_setcsr(csr);
#endif
}

static void cleanup(LADSPA_Handle h)
{
    Resona *r = h;
    for (int i = 0; i < 2; i++) free(r->er_buf[i]);
    for (int i = 0; i < MAXCH; i++) free(r->lim_buf[i]);
    for (int i = 0; i < 4; i++) free(r->fdn_buf[i]);
    free(r->um_dly);
    if (r->shm_is_local) free(r->shm);
    else munmap(r->shm, sizeof(resona_shm_t));
    free(r);
}

/* ------------------------------------------------------------------ */

#define AIN (LADSPA_PORT_INPUT | LADSPA_PORT_AUDIO)
#define AOUT (LADSPA_PORT_OUTPUT | LADSPA_PORT_AUDIO)
static const LADSPA_PortDescriptor port_desc[10] = {AIN, AIN, AOUT, AOUT, AOUT, AOUT, AOUT, AOUT, AOUT, AOUT};
static const char *const port_names2[4] = {"In L", "In R", "Out L", "Out R"};
static const char *const port_names8[10] = {"In L", "In R", "Out FL", "Out FR", "Out FC", "Out LFE",
                                             "Out RL", "Out RR", "Out SL", "Out SR"};
static const LADSPA_PortRangeHint port_hints[10];

static const LADSPA_Descriptor descriptors[2] = {
    {
        .UniqueID = 0x534E41, .Label = "resona",
        .Properties = LADSPA_PROPERTY_HARD_RT_CAPABLE | LADSPA_PROPERTY_INPLACE_BROKEN,
        .Name = "Resona Sound Engine", .Maker = "noble6", .Copyright = "GPL-3.0-or-later",
        .PortCount = 4, .PortDescriptors = port_desc, .PortNames = port_names2, .PortRangeHints = port_hints,
        .instantiate = instantiate2, .connect_port = connect_port, .activate = activate, .run = run, .cleanup = cleanup,
    },
    {
        .UniqueID = 0x534E42, .Label = "resona_surround",
        .Properties = LADSPA_PROPERTY_HARD_RT_CAPABLE | LADSPA_PROPERTY_INPLACE_BROKEN,
        .Name = "Resona Sound Engine (surround upmix)", .Maker = "noble6", .Copyright = "GPL-3.0-or-later",
        .PortCount = 10, .PortDescriptors = port_desc, .PortNames = port_names8, .PortRangeHints = port_hints,
        .instantiate = instantiate8, .connect_port = connect_port, .activate = activate, .run = run, .cleanup = cleanup,
    },
};

__attribute__((visibility("default"))) const LADSPA_Descriptor *ladspa_descriptor(unsigned long index)
{
    return index < 2 ? &descriptors[index] : NULL;
}
