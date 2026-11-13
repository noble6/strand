/*
 * Loads libresonafx like the audio HAL does and drives it through the
 * effect command protocol. Needs AOSP headers (system/media, libhardware):
 *   make -C audio/tests glue AOSP_INC="-I.../system/media/audio/include -I.../libhardware/include_all"
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <dlfcn.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <hardware/audio_effect.h>

static int failures;
#define CHECK(c, ...) do { if (!(c)) { printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); failures++; } } while (0)

static const effect_uuid_t kUuid = {0x2f3c6d81, 0x5b0e, 0x4c7a, 0x9e14, {0x52, 0x45, 0x53, 0x4f, 0x4e, 0x41}};

static int32_t cmd(effect_handle_t h, uint32_t code, uint32_t size, void *data)
{
    int32_t status = -1;
    uint32_t rs = sizeof status;
    int32_t r = (*h)->command(h, code, size, data, &rs, &status);
    return r ? r : status;
}

static int32_t set_param(effect_handle_t h, int32_t key, const void *v, uint32_t vsize)
{
    uint32_t size = sizeof(effect_param_t) + 4 + vsize;
    effect_param_t *p = calloc(1, size);
    p->psize = 4;
    p->vsize = vsize;
    memcpy(p->data, &key, 4);
    memcpy(p->data + 4, v, vsize);
    int32_t r = cmd(h, EFFECT_CMD_SET_PARAM, size, p);
    free(p);
    return r;
}

static int32_t get_param(effect_handle_t h, int32_t key, void *v, uint32_t room)
{
    uint32_t size = sizeof(effect_param_t) + 4 + room;
    effect_param_t *p = calloc(1, size);
    p->psize = 4;
    p->vsize = room;
    memcpy(p->data, &key, 4);
    uint32_t rs = size;
    int32_t r = (*h)->command(h, EFFECT_CMD_GET_PARAM, sizeof(effect_param_t) + 4, p, &rs, p);
    if (!r && !p->status) memcpy(v, p->data + 4, p->vsize);
    int32_t out = r ? r : p->status ? p->status : (int32_t)p->vsize;
    free(p);
    return out;
}

static int32_t configure(effect_handle_t h, audio_format_t fmt, uint32_t rate)
{
    effect_config_t c = {0};
    c.inputCfg.samplingRate = c.outputCfg.samplingRate = rate;
    c.inputCfg.channels = c.outputCfg.channels = AUDIO_CHANNEL_OUT_STEREO;
    c.inputCfg.format = c.outputCfg.format = fmt;
    c.inputCfg.accessMode = EFFECT_BUFFER_ACCESS_READ;
    c.outputCfg.accessMode = EFFECT_BUFFER_ACCESS_WRITE;
    c.inputCfg.buffer.frameCount = 960;
    return cmd(h, EFFECT_CMD_SET_CONFIG, sizeof c, &c);
}

int main(int argc, char **argv)
{
    void *lib = dlopen(argc > 1 ? argv[1] : "./libresonafx.so", RTLD_NOW);
    CHECK(lib, "dlopen: %s", dlerror());
    if (!lib) return 1;
    audio_effect_library_t *L = dlsym(lib, AUDIO_EFFECT_LIBRARY_INFO_SYM_AS_STR);
    CHECK(L && L->tag == AUDIO_EFFECT_LIBRARY_TAG, "library symbol");
    if (!L) return 1;

    effect_descriptor_t d;
    CHECK(L->get_descriptor(&kUuid, &d) == 0 && !strcmp(d.name, "Resona"), "descriptor");
    effect_uuid_t wrong = kUuid;
    wrong.timeLow++;
    effect_handle_t h = NULL;
    CHECK(L->create_effect(&wrong, 0, 0, &h) != 0, "wrong uuid accepted");
    CHECK(L->create_effect(&kUuid, 0, 0, &h) == 0 && h, "create");

    CHECK(cmd(h, EFFECT_CMD_INIT, 0, NULL) == 0, "init");
    CHECK(configure(h, AUDIO_FORMAT_PCM_FLOAT, 48000) == 0, "float config");
    CHECK(configure(h, AUDIO_FORMAT_PCM_8_BIT, 48000) != 0, "8-bit accepted");

    int32_t ver[2];
    CHECK(get_param(h, 2000, ver, sizeof ver) == 8 && ver[0] == 1 && ver[1] > 50, "version");
    float all[128];
    int n = get_param(h, 1000, all, sizeof all);
    CHECK(n > 0 && n % 4 == 0, "get all params");
    int count = n / 4;
    all[0] = 1;        /* enable */
    all[14] = 1;       /* bass on */
    all[15] = 0.8f;    /* bass amount */
    CHECK(set_param(h, 1000, all, count * 4) == 0, "set all params");
    float bad = NAN;
    CHECK(set_param(h, 15, &bad, 4) != 0, "NaN accepted");
    CHECK(set_param(h, 1000, all, 8) != 0, "short bulk accepted");
    float corr[2][10] = {{1, 1000, -3, 1.0f}, {2, 100, 2, 0.7f}};
    CHECK(set_param(h, 1001, corr, sizeof corr) == 0, "correction");

    static float in[960 * 2], out[960 * 2];
    audio_buffer_t ib = {.frameCount = 960, .f32 = in}, ob = {.frameCount = 960, .f32 = out};
    CHECK((*h)->process(h, &ib, &ob) == -ENODATA, "disabled effect processed");
    CHECK(cmd(h, EFFECT_CMD_ENABLE, 0, NULL) == 0, "enable");
    float peak = 0;
    for (int b = 0; b < 200; b++) {
        for (int i = 0; i < 960; i++) in[2 * i] = in[2 * i + 1] = 0.9f * sinf((b * 960 + i) * 0.0072f);
        CHECK((*h)->process(h, &ib, &ob) == 0, "process");
        for (int i = 0; i < 1920; i++) if (fabsf(out[i]) > peak) peak = fabsf(out[i]);
    }
    CHECK(peak > 0.1f && peak < 1.0f, "float output peak %f", peak);

    float meters[18];
    CHECK(get_param(h, 2001, meters, sizeof meters) == sizeof meters && meters[2] > 0.1f, "meters");
    static float scope[2048];
    CHECK(get_param(h, 2002, scope, sizeof scope) == sizeof scope, "scope");
    CHECK(get_param(h, 2002, scope, 100) < 0, "scope into a small buffer");

    /* PCM16 at 44.1 kHz, in place */
    CHECK(configure(h, AUDIO_FORMAT_PCM_16_BIT, 44100) == 0, "pcm16 config");
    static int16_t s16[4096 * 2];
    audio_buffer_t sb = {.frameCount = 4096, .s16 = s16};
    for (int b = 0; b < 50; b++) {
        for (int i = 0; i < 4096 * 2; i++) s16[i] = (int16_t)(30000 * sinf((b * 4096 + i / 2) * 0.01f));
        CHECK((*h)->process(h, &sb, &sb) == 0, "pcm16 process");
    }
    effect_config_t got;
    uint32_t rs = sizeof got;
    CHECK((*h)->command(h, EFFECT_CMD_GET_CONFIG, 0, NULL, &rs, &got) == 0 &&
          got.inputCfg.samplingRate == 44100, "get config");

    CHECK(cmd(h, EFFECT_CMD_DISABLE, 0, NULL) == 0, "disable");
    CHECK(L->release_effect(h) == 0, "release");
    printf(failures ? "%d FAILED\n" : "all glue tests passed\n", failures);
    return failures != 0;
}
