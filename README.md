# Strand

Performance engine for Android: a native daemon, a system app, and a
faster SurfaceFlinger blur.

| Directory | What |
| --- | --- |
| `daemon/` | `strandd` (root, own SELinux domain) and `strandctl` |
| `app/` | Strand app: profiles, per-app profiles, system health, nerd mode, QS tile |
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
| `clearcache` | `pm trim-caches` for every app |
| `dexopt FILTER PKG...` | `pm compile -m FILTER -f` (speed-profile, speed, verify, everything) |

    adb shell strandctl status

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

## Tests

    tests/host-test.sh

Builds strandd for the host and checks profiles, stacking, crash recovery,
per-app switching, persistence, stats and maintenance commands against a
fake sysfs and a fake `cmd`.
