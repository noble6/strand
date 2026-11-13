/*
 * Resona on Android: wraps the unmodified Linux engine (resona/resona_dsp.c,
 * built into this translation unit) for an effect library.
 *
 * The engine reads its controls from a resona_shm_t block. On Linux that block
 * is shared memory written by the UI; here it is the engine's private copy
 * (no /dev/shm on Android) and set_param() writes it with the same seqlock
 * protocol the Linux UI uses, so parameter changes never take a lock on the
 * audio thread.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "../resona/resona_dsp.c"

#include <pthread.h>

#include "resona_fx.h"

/* Frames per engine call. Larger blocks are split, so process() never allocates. */
#define FX_BLOCK 1024

struct resona_fx {
    /* Writers (parameters, meters, rebuilds) serialize on ctl. lock is held
       only while the engine is swapped or reset; process() only tries it, so
       a parameter change never costs the audio thread a block. */
    pthread_mutex_t ctl;
    pthread_mutex_t lock;
    Resona *r;
    unsigned rate;
    float in[2][FX_BLOCK], out[2][FX_BLOCK];
    /* What the UI set, replayed into a rebuilt engine */
    float params[P_COUNT];
    resona_corr_t corr[NCORR];
    unsigned corr_count;
};

/* --- engine control block, same protocol as the Linux UI (app/resona/shm.py) --- */

static void shm_begin(resona_shm_t *m)
{
    __atomic_add_fetch(&m->control_seq, 1, __ATOMIC_SEQ_CST); /* odd: writing */
}

static void shm_end(resona_shm_t *m)
{
    __atomic_add_fetch(&m->param_seq, 1, __ATOMIC_RELEASE);
    __atomic_add_fetch(&m->control_seq, 1, __ATOMIC_SEQ_CST); /* even: stable */
}

static void shm_store_params(resona_shm_t *m, const float *p)
{
    for (int i = 0; i < P_COUNT; i++) __atomic_store(&m->params[i], (float *)&p[i], __ATOMIC_RELAXED);
}

static void shm_store_corr(resona_shm_t *m, const resona_corr_t *c, unsigned n)
{
    for (unsigned i = 0; i < n; i++) {
        float *dst = (float *)&m->corr[i];
        const float *src = (const float *)&c[i];
        for (unsigned k = 0; k < sizeof(resona_corr_t) / sizeof(float); k++)
            __atomic_store(&dst[k], (float *)&src[k], __ATOMIC_RELAXED);
    }
    __atomic_store_n(&m->corr_count, n, __ATOMIC_RELAXED);
}

static Resona *engine_new(resona_fx *fx, unsigned rate)
{
    Resona *r = instantiate_n(rate, 2);
    if (!r) return NULL;
    connect_port(r, 0, fx->in[0]);
    connect_port(r, 1, fx->in[1]);
    connect_port(r, 2, fx->out[0]);
    connect_port(r, 3, fx->out[1]);
    shm_begin(r->shm);
    shm_store_params(r->shm, fx->params);
    shm_store_corr(r->shm, fx->corr, fx->corr_count);
    shm_end(r->shm);
    return r;
}

resona_fx *resona_fx_create(unsigned rate)
{
    resona_fx *fx = calloc(1, sizeof *fx);
    if (!fx) return NULL;
    pthread_mutex_init(&fx->ctl, NULL);
    pthread_mutex_init(&fx->lock, NULL);
    memcpy(fx->params, DEFAULTS, sizeof DEFAULTS);
    fx->rate = rate ? rate : 48000;
    fx->r = engine_new(fx, fx->rate);
    if (!fx->r) {
        free(fx);
        return NULL;
    }
    return fx;
}

void resona_fx_destroy(resona_fx *fx)
{
    if (!fx) return;
    if (fx->r) cleanup(fx->r);
    pthread_mutex_destroy(&fx->lock);
    pthread_mutex_destroy(&fx->ctl);
    free(fx);
}

int resona_fx_set_rate(resona_fx *fx, unsigned rate)
{
    if (rate < 8000 || rate > 384000) return -1;
    pthread_mutex_lock(&fx->ctl);
    if (rate == fx->rate) {
        pthread_mutex_unlock(&fx->ctl);
        return 0;
    }
    Resona *fresh = engine_new(fx, rate);
    if (!fresh) {
        pthread_mutex_unlock(&fx->ctl);
        return -1;
    }
    pthread_mutex_lock(&fx->lock);
    Resona *old = fx->r;
    fx->r = fresh;
    fx->rate = rate;
    pthread_mutex_unlock(&fx->lock);
    pthread_mutex_unlock(&fx->ctl);
    cleanup(old);
    return 0;
}

unsigned resona_fx_rate(const resona_fx *fx) { return fx->rate; }

void resona_fx_reset(resona_fx *fx)
{
    pthread_mutex_lock(&fx->ctl);
    pthread_mutex_lock(&fx->lock);
    reset_state(fx->r);
    pthread_mutex_unlock(&fx->lock);
    pthread_mutex_unlock(&fx->ctl);
}

int resona_fx_set_params(resona_fx *fx, const float *values, unsigned count)
{
    if (count > P_COUNT) count = P_COUNT;
    for (unsigned i = 0; i < count; i++)
        if (!isfinite(values[i])) return -1;
    pthread_mutex_lock(&fx->ctl);
    memcpy(fx->params, values, count * sizeof(float));
    resona_shm_t *m = fx->r->shm;
    shm_begin(m);
    shm_store_params(m, fx->params);
    shm_end(m);
    pthread_mutex_unlock(&fx->ctl);
    return 0;
}

int resona_fx_set_param(resona_fx *fx, int index, float value)
{
    if (index < 0 || index >= P_COUNT || !isfinite(value)) return -1;
    pthread_mutex_lock(&fx->ctl);
    fx->params[index] = value;
    resona_shm_t *m = fx->r->shm;
    shm_begin(m);
    __atomic_store(&m->params[index], &value, __ATOMIC_RELAXED);
    shm_end(m);
    pthread_mutex_unlock(&fx->ctl);
    return 0;
}

float resona_fx_get_param(resona_fx *fx, int index)
{
    if (index < 0 || index >= P_COUNT) return 0;
    pthread_mutex_lock(&fx->ctl);
    float v = fx->params[index];
    pthread_mutex_unlock(&fx->ctl);
    return v;
}

int resona_fx_set_correction(resona_fx *fx, const resona_corr_t *sections, unsigned count)
{
    if (count > NCORR) return -1;
    pthread_mutex_lock(&fx->ctl);
    memcpy(fx->corr, sections, count * sizeof *sections);
    fx->corr_count = count;
    resona_shm_t *m = fx->r->shm;
    shm_begin(m);
    shm_store_corr(m, fx->corr, count);
    shm_end(m);
    pthread_mutex_unlock(&fx->ctl);
    return 0;
}

/* Flush denormals to zero on the audio thread, as the engine does with SSE on x86. */
#if defined(__aarch64__)
static inline uint64_t ftz_on(void)
{
    uint64_t fpcr;
    __asm__ volatile("mrs %0, fpcr" : "=r"(fpcr));
    __asm__ volatile("msr fpcr, %0" ::"r"(fpcr | (1ull << 24)));
    return fpcr;
}
static inline void ftz_restore(uint64_t fpcr) { __asm__ volatile("msr fpcr, %0" ::"r"(fpcr)); }
#else
static inline uint64_t ftz_on(void) { return 0; }
static inline void ftz_restore(uint64_t fpcr) { (void)fpcr; }
#endif

static void pass(const float *in, float *out, size_t n, int accumulate)
{
    if (accumulate) {
        for (size_t i = 0; i < n; i++) out[i] += in[i];
    } else if (in != out) {
        memmove(out, in, n * sizeof(float));
    }
}

void resona_fx_process(resona_fx *fx, const float *in, float *out, size_t frames,
                       unsigned channels, int accumulate)
{
    if (channels != 2 || pthread_mutex_trylock(&fx->lock) != 0) {
        pass(in, out, frames * channels, accumulate);
        return;
    }
    uint64_t fpcr = ftz_on();
    Resona *r = fx->r;
    for (size_t off = 0; off < frames;) {
        size_t n = frames - off < FX_BLOCK ? frames - off : FX_BLOCK;
        const float *src = in + off * 2;
        for (size_t i = 0; i < n; i++) {
            fx->in[0][i] = src[2 * i];
            fx->in[1][i] = src[2 * i + 1];
        }
        run(r, n);
        float *dst = out + off * 2;
        if (accumulate) {
            for (size_t i = 0; i < n; i++) {
                dst[2 * i] += fx->out[0][i];
                dst[2 * i + 1] += fx->out[1][i];
            }
        } else {
            for (size_t i = 0; i < n; i++) {
                dst[2 * i] = fx->out[0][i];
                dst[2 * i + 1] = fx->out[1][i];
            }
        }
        off += n;
    }
    ftz_restore(fpcr);
    pthread_mutex_unlock(&fx->lock);
}

void resona_fx_meters(resona_fx *fx, float out[RESONA_FX_NMETERS])
{
    pthread_mutex_lock(&fx->ctl);
    const resona_shm_t *m = fx->r->shm;
    out[0] = m->in_peak[0];
    out[1] = m->in_peak[1];
    out[2] = m->out_peak[0];
    out[3] = m->out_peak[1];
    out[4] = m->leveler_db;
    out[5] = m->limiter_gr_db;
    out[6] = m->loudness_db;
    out[7] = m->content_db;
    for (int b = 0; b < RESONA_NBANDS; b++) out[8 + b] = m->ieq_db[b];
    pthread_mutex_unlock(&fx->ctl);
}

void resona_fx_scope(resona_fx *fx, float in[RESONA_FX_SCOPE], float out[RESONA_FX_SCOPE])
{
    pthread_mutex_lock(&fx->ctl);
    const resona_shm_t *m = fx->r->shm;
    unsigned end = __atomic_load_n(&m->ring_pos, __ATOMIC_ACQUIRE);
    for (unsigned i = 0; i < RESONA_FX_SCOPE; i++) {
        unsigned k = (end - RESONA_FX_SCOPE + i) & (RESONA_RING - 1);
        in[i] = m->ring_in[k];
        out[i] = m->ring_out[k];
    }
    pthread_mutex_unlock(&fx->ctl);
}
