# Strand

Performance engine for Android: a native daemon, a system app, and a
faster SurfaceFlinger blur.

| Directory | What |
| --- | --- |
| `daemon/` | `strandd` (root, own SELinux domain) and `strandctl` |
| `apex/` | `org.strand.engine`: the daemon and profiles as an updatable APEX |
| `app/` | Strand app: profiles, per-app profiles, system health, nerd mode, Resona, QS tiles |
| `audio/` | Resona sound engine as an Android audio effect (`libresonafx`) |
| `config/` | generic profiles, `/system_ext/etc/strand/strand.conf` |
| `sepolicy/` | system_ext policy for strandd |
| `patches/` | patches for other repos (frameworks/native: Strand blur) |
| `bench/` | launch, jank and retention benchmark over adb |
| `tests/` | host tests for strandd against a fake sysfs |
| `tools/` | perfetto trace helpers |

## Using it in a ROM

Sync this repo to `vendor/strand`, then in the device:

    STRAND_DEVICE_CONFIG := $(LOCAL_PATH)/configs/strand/device.conf
    $(call inherit-product-if-exists, vendor/strand/strand.mk)

Apply `patches/frameworks/native/*` for the blur. Device policy grants
strandd any vendor sysfs it reads (see `sepolicy/device-vendor-example.te`).

## strandd

Holds the profile, follows the foreground app for per-app profiles, reacts to
memory pressure, and runs maintenance jobs. Every change it makes is
journaled in `/data/misc/strand/journal` and undone when the profile is left,
when the daemon restarts after a crash, or (for settings) after a reboot.
The chosen profile and per-app list are kept in `/data/misc/strand/state`.

### Staying fast over months

- Storage GC: daily while idle, the app asks strandd to put f2fs in urgent GC
  (the same mechanism vold's idle maintenance uses) until few dirty segments
  are left. Weekly while idle and charging: fstrim, GC, and recompiling the
  most used apps with their latest profiles. The job holds the idle window
  and strandd stops the moment Android ends it, so it never competes with use.
- Games: the app sends the installed games. One in front gets the gaming
  profile (auto game mode) and a quiet system; so does any app that keeps the
  GPU above `gpu_busy_pct` for `gpu_busy_s`. Quiet means no compaction kicks
  and no maintenance until it ends.
- Battery profile: Doze about 10 minutes after the screen goes off.
- `[base]` turns on Android's Smart Idle Maintenance (vold paces f2fs GC from
  the write rate and flash wear; built in since Android 13, off by default).
- Nearly full storage is slow storage: under `low_storage_pct` free, strandd
  clears app caches (once a day, never during a game) and the app warns.
- Hot device (thermal status `hot_status` or worse): the `[hot]` actions apply
  (lighter blur) and the system is quiet until it cools.

### Config

`strand.conf` then `device.conf`; a later file adds lines to the same
sections. See `config/strand.conf` for every action.

    [profile performance]
    fixedperf on                      # power HAL FIXED_PERFORMANCE
    write /sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq @pct:50
    setting system min_refresh_rate 120.0

`@max`, `@min` and `@pct:N` pick a step from the frequency table next to the
node, so one config fits every bin of a chip. On devices whose power HAL owns
CPU and GPU limits (libperfmgr), use `fixedperf` and `powersave` and put the
limits in the HAL config instead: it restores its own values after boosts.

### Socket protocol

Init socket `/dev/socket/strandd`, one command per line. The reply is
`ok ...` or `err ...`, data lines, then a line with a single `.`.

| Command | |
| --- | --- |
| `ping`, `status`, `stats`, `profiles` | state and counters |
| `profile [NAME]` | get or set the profile |
| `app PKG PROFILE\|none`, `apps [clear]` | per-app profiles |
| `trim` | FITRIM on `[trim]` paths |
| `gc`, `stop` | f2fs GC until clean; `stop` ends maintenance (idle window over) |
| `games clear`, `games add PKG...`, `autogame on\|off` | game list and auto game mode |
| `thermal STATUS` | PowerManager thermal status (the app sends it) |
| `version` | daemon protocol version |
| `clearcache` | `pm trim-caches` for every app |
| `dexopt FILTER PKG...` | `pm compile -m FILTER -f` (speed-profile, speed, verify, everything) |

    adb shell /apex/org.strand.engine/bin/strandctl status

## Blur

`patches/frameworks/native/0001-renderengine-Add-the-Strand-blur-filter.patch`
adds `StrandBlurFilter`, the default algorithm after the patch
(`debug.renderengine.blur_algorithm=kawase2` goes back to stock).

It looks the same as stock Kawase dual filter (same shaders and radius
mapping) but skips work it has done before. SkiaRenderEngine tells it which
layers are below the blurred one; when they and the display settings match
an earlier frame, so do the input pixels:

- same rect and radius: the last result is reused, no GPU work
- same rect, new radius (shade or dialog fading in): the downsample pyramid
  is kept, only the upsample passes run

That is the notification shade over a still launcher or app, where only the
shade content moves. Front buffered and command stream layers turn the cache
off for the frame. Passes render to persistent 8-bit opaque surfaces (F16
only for HDR input) and discard old contents. `sys.strand.blur.lite=1` (set
by the battery profile) caps the passes at two; `ro.strand.blur.cache=false`
turns the cache off. Hit counts are logged every 1024 frames
(`logcat -s RenderEngine`) and passes show in perfetto as `StrandBlur *`.

## Updates without a ROM build

The daemon and the generic profiles ship as the updatable APEX
`org.strand.engine` (product partition); the app is a normal priv-app.
`tools/make-release.sh`, run in the ROM tree that built the device's ROM,
publishes both with an `update.json` (versions, SHA-256) as a GitHub release.
The app's Updates screen checks the latest release, verifies the checksums,
and stages both in one install that applies on the next reboot (Android only
updates a persistent app that way, the same way Mainline modules update).
Package manager accepts them only with the same keys as the installed build.

Keys: run `vendor/strand/tools/make-keys.sh` once in a ROM tree. It creates
the APEX keys in `vendor/strand/apex/keys/`, which is never committed; the
build stops if they are missing. Back that folder up with your other signing
keys: updates must carry the same keys as the ROM. The Resona effect library is in the vendor
partition and still needs a ROM update.

## Resona

The Resona sound engine (github.com/noble6/resona), the same DSP as Resona
for Linux, as an open audio effect on the output mix: spatial HRTF, DeepBass,
Intelligent EQ, Clarity, Voice Focus, 3D Stage, Ambience, Tube Warmth, Smart
Leveler, Adaptive Loudness, 10-band EQ, correction EQ and a lookahead limiter.

- `audio/resona/` is the engine, copied unmodified (`audio/sync-resona.sh`).
  `audio/effect/` wraps it as an effect library: parameters go through the
  engine's lock-free control block, so the audio thread never waits, and a
  rebuild (sample rate change) passes audio through instead of blocking.
- No proprietary blobs and no extra HAL service: one library loaded by the
  audio effect HAL. Turning Resona off releases the effect, so offloaded
  playback comes back.
- The app keeps the effect on the output mix from boot, follows the output
  (speaker, headphones, external), sends the stream volume for the
  level-aware stages, and recreates the effect if audioserver restarts.
  Profiles and per-device memory match Resona on Linux; Auto picks Music,
  Movie, Gaming or Voice from what is playing.
- Built-in speakers get the small-driver tuning (psychoacoustic bass, gentle
  low shelf, 1 dB more limiter headroom).
- Factory speaker calibration: phone and tablet makers calibrate their
  speakers in the stock Dolby tuning file. `audio/tools/dax2resona.py` reads
  only that calibration curve (per orientation) from `dax-default.xml` and
  fits Resona correction filters to it; the device installs the result as
  `/system_ext/etc/strand/resona_speaker.json`. Resona applies it on the
  built-in speakers, switching with orientation, and runs DeepBass in the band
  the calibration hands to virtual bass. No Dolby code or library is used.
- Hardware: the screen shows the output, its formats, rates and channels, the
  mixer rate, the Bluetooth codec, and for USB DACs a Hi-Res switch that runs
  the mixer at the DAC's highest rate (Resona keeps working).

Register it in the device's `audio_effects.xml`
(`audio/audio_effects.xml.example`).

Tests: `make -C audio/tests` (limiter ceiling, garbage input, threads, CPU),
`make -C audio/tests tsan`, `asan`, and `glue` (loads the library like the
audio HAL; needs AOSP headers), `audio/tests/check_params.py`.
Measured on the host: all effects on, 48 kHz stereo, about 1% of one core.

## Licenses and credits

Strand is Apache-2.0. Resona (`audio/`, `app/src/org/strand/engine/resona/`)
is GPL-3.0-or-later; an app build that includes it is distributed under the
GPL-3.0.

- Resona: noble6 and rajdeep-3305, https://github.com/noble6/resona
- Lunaris Dolby (hardware/dolby, nekoshirro and contributors): reference for
  how a ROM ships a system sound app on the output mix. No code is used.
- The blur builds on AOSP's KawaseBlurDualFilterV2 (The Android Open Source
  Project); Axion OS's blur research was read for ideas, no code is used.

## Tests

    tests/host-test.sh

Builds strandd for the host and checks profiles, stacking, crash recovery,
per-app switching, persistence, stats and maintenance commands against a
fake sysfs and a fake `cmd`.
