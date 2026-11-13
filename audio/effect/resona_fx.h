/*
 * Resona on Android: the engine behind a small, platform-free API that the
 * effect HAL glue (resona_effect.c) and the host tests both use.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef RESONA_FX_H
#define RESONA_FX_H

#include <stddef.h>
#include <stdint.h>

#include "../resona/resona_shm.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct resona_fx resona_fx;

/* Meter snapshot for the UI */
#define RESONA_FX_NMETERS (8 + RESONA_NBANDS)
/* Samples of mono input and output kept for the UI spectrum */
#define RESONA_FX_SCOPE 1024

resona_fx *resona_fx_create(unsigned rate);
void resona_fx_destroy(resona_fx *fx);

/* Sample rate change rebuilds the engine; parameters are kept. Not realtime. */
int resona_fx_set_rate(resona_fx *fx, unsigned rate);
unsigned resona_fx_rate(const resona_fx *fx);
void resona_fx_reset(resona_fx *fx);

/* Parameters: lock-free against process(), from any thread. */
int resona_fx_set_param(resona_fx *fx, int index, float value);
int resona_fx_set_params(resona_fx *fx, const float *values, unsigned count);
float resona_fx_get_param(resona_fx *fx, int index);
int resona_fx_set_correction(resona_fx *fx, const resona_corr_t *sections, unsigned count);

/*
 * Interleaved audio. Stereo is processed; any other layout passes through
 * untouched. accumulate adds the result to out. Never blocks: if the engine
 * is being rebuilt the block passes through.
 */
void resona_fx_process(resona_fx *fx, const float *in, float *out, size_t frames,
                       unsigned channels, int accumulate);

/* in_peak[2] out_peak[2] leveler_db limiter_gr_db loudness_db content_db ieq_db[10] */
void resona_fx_meters(resona_fx *fx, float out[RESONA_FX_NMETERS]);
/* Last RESONA_FX_SCOPE mono samples, oldest first, of the input and the output. */
void resona_fx_scope(resona_fx *fx, float in[RESONA_FX_SCOPE], float out[RESONA_FX_SCOPE]);

#ifdef __cplusplus
}
#endif

#endif
