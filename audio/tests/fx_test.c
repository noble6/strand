/*
 * Host test for the Resona effect core: safety, thread safety, CPU cost.
 *   make -C audio/tests
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#define _GNU_SOURCE
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "../effect/resona_fx.h"

#define RATE 48000
#define BLOCK 960 /* 20 ms, what an Android mixer hands an output mix effect */

static int failures;
#define CHECK(c, ...) do { if (!(c)) { printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); failures++; } } while (0)

static unsigned seed = 1;
static float noise(void) { seed = seed * 1664525u + 1013904223u; return (float)((int)(seed >> 8) - (1 << 23)) / (float)(1 << 23); }

/* Loud, bass heavy program: worst case for the bass and limiter stages */
static void program(float *buf, size_t frames, size_t t0)
{
    for (size_t i = 0; i < frames; i++) {
        double t = (double)(t0 + i) / RATE;
        float s = 0.7f * (float)sin(2 * M_PI * 55 * t) + 0.3f * noise();
        buf[2 * i] = s;
        buf[2 * i + 1] = 0.8f * s + 0.2f * noise();
    }
}

static double now(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec * 1e-9;
}

/* Music profile from resona/app presets.py, on headphones (HRTF off, crossfeed on) */
static void music_profile(float *p)
{
    p[P_ENABLE] = 1; p[P_BASS_ON] = 1; p[P_BASS_AMT] = 0.55f; p[P_BASS_PUNCH] = 0.35f;
    p[P_CLAR_ON] = 1; p[P_CLAR_AMT] = 0.35f; p[P_STAGE_ON] = 1; p[P_STAGE_WIDTH] = 1.4f;
    p[P_STAGE_DEPTH] = 0.3f; p[P_LOUD_ON] = 1; p[P_IEQ_ON] = 1; p[P_IEQ_AMT] = 0.35f;
    p[P_IEQ_TARGET] = 1; p[P_TUBE_ON] = 1; p[P_TUBE_DRIVE] = 0.2f; p[P_XFEED_ON] = 1;
    p[P_AMB_ON] = 1; p[P_HRTF_ON] = 0; p[P_LIM_ON] = 1; p[P_LIM_CEIL] = -0.5f;
    static const float warm[10] = {3, 2.5f, 1.5f, 0.5f, 0, -0.5f, -1, -1, 0, 0.5f};
    for (int b = 0; b < 10; b++) p[P_EQ0 + b] = warm[b];
}

static atomic_int stop;

static void *twiddle(void *arg)
{
    resona_fx *fx = arg;
    unsigned n = 0;
    while (!atomic_load(&stop)) {
        resona_fx_set_param(fx, P_BASS_AMT, (n % 100) / 100.0f);
        resona_fx_set_param(fx, P_HRTF_ON, n & 1);
        resona_fx_set_param(fx, P_HRTF_ANGLE, 10 + (n % 60));
        float m[RESONA_FX_NMETERS], a[RESONA_FX_SCOPE], b[RESONA_FX_SCOPE];
        resona_fx_meters(fx, m);
        resona_fx_scope(fx, a, b);
        if ((n % 500) == 0) resona_fx_set_rate(fx, (n / 500) & 1 ? 44100 : 48000);
        n++;
    }
    return NULL;
}

int main(void)
{
    static float in[BLOCK * 2], out[BLOCK * 2];
    resona_fx *fx = resona_fx_create(RATE);
    CHECK(fx, "create");

    float p[P_COUNT];
    for (int i = 0; i < P_COUNT; i++) p[i] = resona_fx_get_param(fx, i);
    music_profile(p);
    CHECK(resona_fx_set_params(fx, p, P_COUNT) == 0, "set params");

    /* 1. 20 s of loud program: always finite, never above the ceiling */
    float ceil = powf(10.0f, -0.5f / 20.0f), peak = 0;
    int finite = 1;
    for (size_t t = 0; t < 20 * RATE; t += BLOCK) {
        program(in, BLOCK, t);
        resona_fx_process(fx, in, out, BLOCK, 2, 0);
        for (int i = 0; i < BLOCK * 2; i++) {
            if (!isfinite(out[i])) finite = 0;
            if (fabsf(out[i]) > peak) peak = fabsf(out[i]);
        }
    }
    CHECK(finite, "non-finite output");
    CHECK(peak <= ceil + 1e-4f, "peak %.4f above ceiling %.4f", peak, ceil);
    printf("loud program: peak %.4f (ceiling %.4f)\n", peak, ceil);

    /* 2. garbage in: NaN, inf and huge values come out finite */
    for (int i = 0; i < BLOCK * 2; i++) in[i] = (i % 3 == 0) ? NAN : (i % 3 == 1) ? INFINITY : 1e30f;
    resona_fx_process(fx, in, out, BLOCK, 2, 0);
    finite = 1;
    for (int i = 0; i < BLOCK * 2; i++) if (!isfinite(out[i])) finite = 0;
    CHECK(finite, "garbage input gave non-finite output");

    /* 3. other layouts pass through untouched, accumulate adds */
    for (int i = 0; i < BLOCK * 2; i++) { in[i] = noise(); out[i] = 0.5f; }
    resona_fx_process(fx, in, out, BLOCK * 2 / 6, 6, 0);
    CHECK(memcmp(in, out, (BLOCK * 2 / 6) * 6 * sizeof(float)) == 0, "6ch passthrough");
    for (int i = 0; i < BLOCK; i++) out[i] = 1.0f;
    resona_fx_process(fx, in, out, BLOCK / 2, 1, 1);
    CHECK(fabsf(out[7] - (1.0f + in[7])) < 1e-6f, "mono accumulate");

    /* 4. bad parameters are refused */
    CHECK(resona_fx_set_param(fx, P_COUNT, 1) != 0, "out of range index");
    CHECK(resona_fx_set_param(fx, P_BASS_AMT, NAN) != 0, "NaN value");
    CHECK(resona_fx_set_rate(fx, 1000) != 0, "silly rate");

    /* 5. parameters, meters and rate changes from another thread while audio runs */
    pthread_t th;
    pthread_create(&th, NULL, twiddle, fx);
    finite = 1;
    for (size_t t = 0; t < 10 * RATE; t += BLOCK) {
        program(in, BLOCK, t);
        resona_fx_process(fx, in, out, BLOCK, 2, 0);
        for (int i = 0; i < BLOCK * 2; i++) if (!isfinite(out[i]) || fabsf(out[i]) > 1.0f) finite = 0;
    }
    atomic_store(&stop, 1);
    pthread_join(th, NULL);
    CHECK(finite, "bad output while parameters changed");

    /* 6. CPU: heaviest stereo setup (HRTF + everything on), 60 s of audio */
    resona_fx_set_rate(fx, RATE);
    music_profile(p);
    p[P_HRTF_ON] = 1; p[P_XFEED_ON] = 0; p[P_VOICE_ON] = 1; p[P_LEV_ON] = 1; p[P_CORR_ON] = 0;
    resona_fx_set_params(fx, p, P_COUNT);
    double t0 = now();
    for (size_t t = 0; t < 60 * RATE; t += BLOCK) {
        program(in, BLOCK, t);
        resona_fx_process(fx, in, out, BLOCK, 2, 0);
    }
    double cpu = now() - t0;
    printf("60 s of 48 kHz stereo, all effects: %.0f ms CPU, %.0fx realtime, %.2f%% of one core\n",
           cpu * 1000, 60 / cpu, cpu / 60 * 100);

    resona_fx_destroy(fx);
    printf(failures ? "%d FAILED\n" : "all effect tests passed\n", failures);
    return failures != 0;
}
