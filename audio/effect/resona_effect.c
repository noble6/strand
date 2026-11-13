/*
 * Resona audio effect library (vendor/lib64/soundfx/libresonafx.so).
 *
 * An insert effect for the output mix (session 0). Controlled with
 * EFFECT_CMD_SET_PARAM / GET_PARAM, int32 keys:
 *
 *   0 .. P_COUNT-1   one engine parameter, float value (resona_shm.h order)
 *   RFX_PARAMS       all P_COUNT parameters at once, float[P_COUNT]
 *   RFX_CORRECTION   correction EQ, resona_corr_t[n] (n <= RESONA_NCORR)
 *   RFX_VERSION      get: int32 RFX_API_VERSION, int32 P_COUNT
 *   RFX_METERS       get: float[RESONA_FX_NMETERS]
 *   RFX_SCOPE        get: float[2 * RESONA_FX_SCOPE], input then output
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#define LOG_TAG "resonafx"

#include <errno.h>
#include <stdlib.h>
#include <string.h>

#include <hardware/audio_effect.h>
#include <log/log.h>

#include "resona_fx.h"

#define RFX_API_VERSION 1
#define RFX_MAX_CH 8
#define RFX_SCRATCH_FRAMES 1024
enum {
    RFX_PARAMS = 1000,
    RFX_CORRECTION = 1001,
    RFX_VERSION = 2000,
    RFX_METERS = 2001,
    RFX_SCOPE = 2002,
};

/* Type and implementation UUIDs, also in audio_effects.xml and the app */
static const effect_descriptor_t kDescriptor = {
    .type = {0x7c0e4a52, 0x9d61, 0x4f3e, 0x8b2a, {0x52, 0x45, 0x53, 0x4f, 0x4e, 0x41}},
    .uuid = {0x2f3c6d81, 0x5b0e, 0x4c7a, 0x9e14, {0x52, 0x45, 0x53, 0x4f, 0x4e, 0x41}},
    .apiVersion = EFFECT_CONTROL_API_VERSION,
    .flags = EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_LAST,
    .cpuLoad = 20,     /* 0.1 MIPS units */
    .memoryUsage = 64, /* KB */
    .name = "Resona",
    .implementor = "Strand / Resona",
};

typedef struct {
    const struct effect_interface_s *itfe; /* must be first: effect_handle_t points here */
    resona_fx *fx;
    effect_config_t config;
    int enabled;
    float *scratch; /* PCM16 conversion */
    size_t scratch_frames;
} ResonaContext;

static int supported_format(audio_format_t f)
{
    return f == AUDIO_FORMAT_PCM_FLOAT || f == AUDIO_FORMAT_PCM_16_BIT;
}

static int32_t fx_process(effect_handle_t self, audio_buffer_t *in, audio_buffer_t *out)
{
    ResonaContext *ctx = (ResonaContext *)self;
    if (!ctx || !in || !out || !in->raw || !out->raw || in->frameCount != out->frameCount)
        return -EINVAL;
    if (!ctx->enabled) return -ENODATA;

    const unsigned ch = audio_channel_count_from_out_mask(ctx->config.outputCfg.channels);
    const int acc = ctx->config.outputCfg.accessMode == EFFECT_BUFFER_ACCESS_ACCUMULATE;
    const size_t frames = in->frameCount;

    if (ctx->config.inputCfg.format == AUDIO_FORMAT_PCM_FLOAT) {
        resona_fx_process(ctx->fx, in->f32, out->f32, frames, ch, acc);
        return 0;
    }

    /* PCM16: through a float buffer sized at SET_CONFIG; larger blocks in pieces */
    if (ch == 0 || ch > RFX_MAX_CH) return 0; /* layouts we never touch: leave the data */
    size_t done = 0;
    while (done < frames) {
        size_t n = frames - done;
        if (n > ctx->scratch_frames) n = ctx->scratch_frames;
        const int16_t *src = in->s16 + done * ch;
        int16_t *dst = out->s16 + done * ch;
        for (size_t i = 0; i < n * ch; i++) ctx->scratch[i] = src[i] * (1.0f / 32768.0f);
        resona_fx_process(ctx->fx, ctx->scratch, ctx->scratch, n, ch, 0);
        for (size_t i = 0; i < n * ch; i++) {
            float v = ctx->scratch[i] * 32768.0f;
            if (acc) v += dst[i];
            dst[i] = v > 32767.0f ? 32767 : v < -32768.0f ? -32768 : (int16_t)v;
        }
        done += n;
    }
    return 0;
}

static int set_config(ResonaContext *ctx, const effect_config_t *cfg)
{
    if (cfg->inputCfg.samplingRate != cfg->outputCfg.samplingRate ||
        cfg->inputCfg.channels != cfg->outputCfg.channels ||
        cfg->inputCfg.format != cfg->outputCfg.format || !supported_format(cfg->inputCfg.format))
        return -EINVAL;
    if (cfg->outputCfg.accessMode != EFFECT_BUFFER_ACCESS_WRITE &&
        cfg->outputCfg.accessMode != EFFECT_BUFFER_ACCESS_ACCUMULATE)
        return -EINVAL;
    if (resona_fx_set_rate(ctx->fx, cfg->inputCfg.samplingRate)) return -EINVAL;

    unsigned ch = audio_channel_count_from_out_mask(cfg->outputCfg.channels);
    if (cfg->inputCfg.format == AUDIO_FORMAT_PCM_16_BIT && !ctx->scratch) {
        ctx->scratch = malloc(RFX_SCRATCH_FRAMES * RFX_MAX_CH * sizeof(float));
        if (!ctx->scratch) return -ENOMEM;
        ctx->scratch_frames = RFX_SCRATCH_FRAMES;
    }
    ctx->config = *cfg;
    ALOGI("config %u Hz, %u ch, %s", cfg->inputCfg.samplingRate, ch,
          cfg->inputCfg.format == AUDIO_FORMAT_PCM_FLOAT ? "float" : "pcm16");
    return 0;
}

/* Value area of an effect_param_t: after the key, padded to 32 bits */
static void *param_value(effect_param_t *p)
{
    return p->data + ((p->psize + 3) & ~3u);
}

static int set_param(ResonaContext *ctx, effect_param_t *p, uint32_t size)
{
    if (size < sizeof(effect_param_t) + 4 || p->psize != 4 ||
        size < sizeof(effect_param_t) + 4 + p->vsize)
        return -EINVAL;
    int32_t key;
    memcpy(&key, p->data, 4);
    const void *v = param_value(p);

    if (key >= 0 && key < P_COUNT) {
        if (p->vsize != sizeof(float)) return -EINVAL;
        float f;
        memcpy(&f, v, 4);
        return resona_fx_set_param(ctx->fx, key, f) ? -EINVAL : 0;
    }
    if (key == RFX_PARAMS) {
        if (p->vsize != P_COUNT * sizeof(float)) return -EINVAL;
        float all[P_COUNT];
        memcpy(all, v, sizeof all);
        return resona_fx_set_params(ctx->fx, all, P_COUNT) ? -EINVAL : 0;
    }
    if (key == RFX_CORRECTION) {
        if (p->vsize % sizeof(resona_corr_t) || p->vsize / sizeof(resona_corr_t) > RESONA_NCORR)
            return -EINVAL;
        resona_corr_t c[RESONA_NCORR];
        unsigned n = p->vsize / sizeof(resona_corr_t);
        memcpy(c, v, p->vsize);
        return resona_fx_set_correction(ctx->fx, c, n) ? -EINVAL : 0;
    }
    return -EINVAL;
}

/* Fills the value of a GET_PARAM reply; returns the value size or <0. */
static int get_param(ResonaContext *ctx, int32_t key, void *v, uint32_t room)
{
    if (key >= 0 && key < P_COUNT) {
        if (room < 4) return -EINVAL;
        float f = resona_fx_get_param(ctx->fx, key);
        memcpy(v, &f, 4);
        return 4;
    }
    if (key == RFX_PARAMS) {
        if (room < P_COUNT * sizeof(float)) return -EINVAL;
        float all[P_COUNT];
        for (int i = 0; i < P_COUNT; i++) all[i] = resona_fx_get_param(ctx->fx, i);
        memcpy(v, all, sizeof all);
        return sizeof all;
    }
    if (key == RFX_VERSION) {
        if (room < 8) return -EINVAL;
        int32_t ver[2] = {RFX_API_VERSION, P_COUNT};
        memcpy(v, ver, 8);
        return 8;
    }
    if (key == RFX_METERS) {
        float m[RESONA_FX_NMETERS];
        if (room < sizeof m) return -EINVAL;
        resona_fx_meters(ctx->fx, m);
        memcpy(v, m, sizeof m);
        return sizeof m;
    }
    if (key == RFX_SCOPE) {
        static const uint32_t bytes = 2 * RESONA_FX_SCOPE * sizeof(float);
        if (room < bytes) return -EINVAL;
        float *f = malloc(bytes);
        if (!f) return -ENOMEM;
        resona_fx_scope(ctx->fx, f, f + RESONA_FX_SCOPE);
        memcpy(v, f, bytes);
        free(f);
        return bytes;
    }
    return -EINVAL;
}

static int32_t fx_command(effect_handle_t self, uint32_t cmd, uint32_t size, void *data,
                          uint32_t *reply_size, void *reply)
{
    ResonaContext *ctx = (ResonaContext *)self;
    if (!ctx) return -EINVAL;
    int32_t *status = reply_size && *reply_size >= sizeof(int32_t) ? reply : NULL;

    switch (cmd) {
    case EFFECT_CMD_INIT:
        if (!status) return -EINVAL;
        *status = 0;
        return 0;
    case EFFECT_CMD_SET_CONFIG:
        if (!data || size != sizeof(effect_config_t) || !status) return -EINVAL;
        *status = set_config(ctx, data);
        return 0;
    case EFFECT_CMD_GET_CONFIG:
        if (!reply || !reply_size || *reply_size != sizeof(effect_config_t)) return -EINVAL;
        memcpy(reply, &ctx->config, sizeof(effect_config_t));
        return 0;
    case EFFECT_CMD_RESET:
        resona_fx_reset(ctx->fx);
        return 0;
    case EFFECT_CMD_ENABLE:
    case EFFECT_CMD_DISABLE:
        if (!status) return -EINVAL;
        if (cmd == EFFECT_CMD_ENABLE && !ctx->enabled) resona_fx_reset(ctx->fx);
        ctx->enabled = cmd == EFFECT_CMD_ENABLE;
        *status = 0;
        return 0;
    case EFFECT_CMD_SET_PARAM:
        if (!data || !status) return -EINVAL;
        *status = set_param(ctx, data, size);
        return 0;
    case EFFECT_CMD_GET_PARAM: {
        if (!data || size < sizeof(effect_param_t) + 4 || !reply || !reply_size ||
            *reply_size < sizeof(effect_param_t) + 4)
            return -EINVAL;
        const effect_param_t *q = data;
        if (q->psize != 4) return -EINVAL;
        effect_param_t *p = reply;
        memcpy(p, q, sizeof(effect_param_t) + 4);
        int32_t key;
        memcpy(&key, q->data, 4);
        uint32_t head = sizeof(effect_param_t) + 4;
        int n = get_param(ctx, key, param_value(p), *reply_size - head);
        p->status = n < 0 ? n : 0;
        p->vsize = n < 0 ? 0 : (uint32_t)n;
        *reply_size = head + p->vsize;
        return 0;
    }
    case EFFECT_CMD_SET_DEVICE:
    case EFFECT_CMD_SET_AUDIO_MODE:
    case EFFECT_CMD_SET_AUDIO_SOURCE:
    case EFFECT_CMD_SET_VOLUME:
        /* The app sends device and volume as parameters; nothing to do here. */
        if (status) *status = 0;
        return 0;
    default:
        return -EINVAL;
    }
}

static int32_t fx_get_descriptor(effect_handle_t self, effect_descriptor_t *d)
{
    if (!self || !d) return -EINVAL;
    *d = kDescriptor;
    return 0;
}

static const struct effect_interface_s kInterface = {
    .process = fx_process,
    .command = fx_command,
    .get_descriptor = fx_get_descriptor,
    .process_reverse = NULL,
};

static int same_uuid(const effect_uuid_t *a, const effect_uuid_t *b)
{
    return memcmp(a, b, sizeof *a) == 0;
}

static int32_t lib_create(const effect_uuid_t *uuid, int32_t session, int32_t io,
                          effect_handle_t *handle)
{
    (void)io;
    if (!uuid || !handle || !same_uuid(uuid, &kDescriptor.uuid)) return -EINVAL;
    ResonaContext *ctx = calloc(1, sizeof *ctx);
    if (!ctx) return -ENOMEM;
    ctx->itfe = &kInterface;
    ctx->fx = resona_fx_create(48000);
    if (!ctx->fx) {
        free(ctx);
        return -ENOMEM;
    }
    ctx->config.inputCfg.samplingRate = ctx->config.outputCfg.samplingRate = 48000;
    ctx->config.inputCfg.channels = ctx->config.outputCfg.channels = AUDIO_CHANNEL_OUT_STEREO;
    ctx->config.inputCfg.format = ctx->config.outputCfg.format = AUDIO_FORMAT_PCM_FLOAT;
    ctx->config.inputCfg.accessMode = EFFECT_BUFFER_ACCESS_READ;
    ctx->config.outputCfg.accessMode = EFFECT_BUFFER_ACCESS_WRITE;
    *handle = (effect_handle_t)ctx;
    ALOGI("created for session %d", session);
    return 0;
}

static int32_t lib_release(effect_handle_t handle)
{
    ResonaContext *ctx = (ResonaContext *)handle;
    if (!ctx) return -EINVAL;
    resona_fx_destroy(ctx->fx);
    free(ctx->scratch);
    free(ctx);
    return 0;
}

static int32_t lib_get_descriptor(const effect_uuid_t *uuid, effect_descriptor_t *d)
{
    if (!uuid || !d || !same_uuid(uuid, &kDescriptor.uuid)) return -EINVAL;
    *d = kDescriptor;
    return 0;
}

__attribute__((visibility("default"))) audio_effect_library_t AUDIO_EFFECT_LIBRARY_INFO_SYM = {
    .tag = AUDIO_EFFECT_LIBRARY_TAG,
    .version = EFFECT_LIBRARY_API_VERSION,
    .name = "Resona",
    .implementor = "Strand / Resona",
    .create_effect = lib_create,
    .release_effect = lib_release,
    .get_descriptor = lib_get_descriptor,
};
