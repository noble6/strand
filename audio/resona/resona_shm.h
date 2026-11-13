/*
 * Resona: shared memory contract between the DSP engine and the UI.
 * The UI writes params[] / corr[] then bumps param_seq; the engine writes meters + rings.
 * control_seq is odd during a UI write; the engine accepts only stable, even snapshots.
 * Layout is mirrored in app/resona/shm.py (offsets verified by tests/test_dsp.c).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef RESONA_SHM_H
#define RESONA_SHM_H
#include <stdint.h>

#define RESONA_MAGIC   0x414E5352u /* "RSNA" */
#define RESONA_VERSION 3u
#define RESONA_NPARAMS 128
#define RESONA_RING    4096
#define RESONA_NCORR   24
#define RESONA_NBANDS  10

enum {
    P_ENABLE = 0, P_PREAMP, P_OUTPUT,
    P_EQ_ON, P_EQ0, P_EQ1, P_EQ2, P_EQ3, P_EQ4, P_EQ5, P_EQ6, P_EQ7, P_EQ8, P_EQ9,
    P_BASS_ON, P_BASS_AMT, P_BASS_FREQ, P_BASS_PUNCH,
    P_CLAR_ON, P_CLAR_AMT,
    P_VOICE_ON, P_VOICE_AMT,
    P_STAGE_ON, P_STAGE_WIDTH, P_STAGE_DEPTH,
    P_XFEED_ON, P_XFEED_AMT,
    P_AMB_ON, P_AMB_MIX, P_AMB_SIZE, P_AMB_DAMP,
    P_LEV_ON, P_LEV_AMT, P_LEV_TARGET,
    P_LOUD_ON, P_LOUD_AMT,
    P_LIM_ON, P_LIM_CEIL,
    P_SYSVOL_DB,
    /* v2 */
    P_IEQ_ON, P_IEQ_AMT, P_IEQ_TARGET,     /* Intelligent EQ: 0 balanced 1 warm 2 focused 3 bright */
    P_TUBE_ON, P_TUBE_DRIVE,
    P_HRTF_ON, P_HRTF_ANGLE, P_HRTF_AMT,   /* binaural virtual speakers for headphones */
    P_CORR_ON, P_CORR_PREAMP,             /* correction EQ (AutoEq / DDC / EqualizerAPO) */
    P_UPMIX_ON, P_UPMIX_CENTER, P_UPMIX_SURR, P_UPMIX_LFE,
    P_LAYOUT,                             /* bitmask: 1 centre, 2 LFE, 4 side pair, 8 rear pair */
    /* v2.1 */
    P_BASS_TYPE,                          /* 0 balanced 1 sub boost 2 punchy 3 warm */
    P_COUNT
};

enum { CORR_OFF = 0, CORR_PK, CORR_LSC, CORR_HSC, CORR_LP, CORR_HP, CORR_RAW };

typedef struct {
    float type, freq, gain, q;  /* designed filters */
    float b0, b1, b2, a1, a2;   /* CORR_RAW: y = b0x + b1x1 + b2x2 - a1y1 - a2y2 */
    float pad;
} resona_corr_t;

typedef struct {
    uint32_t magic;
    uint32_t version;
    uint32_t param_seq;
    uint32_t sample_rate;
    float    params[RESONA_NPARAMS];
    /* engine -> UI */
    uint32_t heartbeat;
    uint32_t ring_pos;
    float    in_peak[2];
    float    out_peak[2];
    float    leveler_db;
    float    limiter_gr_db;
    float    loudness_db;
    float    content_db;
    float    ieq_db[RESONA_NBANDS];
    uint32_t out_channels;
    uint32_t corr_count;        /* UI -> engine */
    uint32_t control_seq;       /* UI -> engine publication guard */
    float    reserved[5];
    resona_corr_t corr[RESONA_NCORR];
    float    ring_in[RESONA_RING];
    float    ring_out[RESONA_RING];
} resona_shm_t;

#endif
