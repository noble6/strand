#!/bin/bash
# Runs strandd on the host against a fake sysfs/procfs and checks that
# profiles apply, stack and restore. Usage: tests/host-test.sh
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
make -s -C "$HERE/daemon"
T=$(mktemp -d)
trap 'kill $PID 2>/dev/null || true; rm -rf "$T"' EXIT
R=$T/root
fail() { echo "FAIL: $*"; exit 1; }

for p in 0:300000:1804800 4:600000:2400000 7:700000:2700000; do
	IFS=: read -r n lo hi <<<"$p"
	d=$R/sys/devices/system/cpu/cpufreq/policy$n
	mkdir -p "$d"
	echo "$lo 900000 1200000 1500000 $hi" >"$d/scaling_available_frequencies"
	echo "$lo" >"$d/scaling_min_freq"; echo "$hi" >"$d/scaling_max_freq"; echo "$lo" >"$d/scaling_cur_freq"
done
g="$R/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/devfreq/3d00000.qcom,kgsl-3d0"; mkdir -p "$g"
echo "253000000 400000000 550000000 700000000 940000000" >"$g/available_frequencies"
echo 253000000 >"$g/min_freq"; echo 940000000 >"$g/max_freq"; echo 253000000 >"$g/cur_freq"
mkdir -p "$R/proc/pressure" "$R/proc/sys/vm" "$R/proc/sys/kernel/random" "$R/props" "$R/data/misc/strand" "$R/dev/cpuset/top-app"
printf 'some avg10=0.50 avg60=0.10 avg300=0.00 total=1234\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=12\n' >"$R/proc/pressure/memory"
cp "$R/proc/pressure/memory" "$R/proc/pressure/cpu"; cp "$R/proc/pressure/memory" "$R/proc/pressure/io"
echo boot-1 >"$R/proc/sys/kernel/random/boot_id"
echo 1 >"$R/props/sys.boot_completed"
touch "$R/props/sys.strand.blur.lite" "$R/dev/cpuset/top-app/cgroup.procs"
mkdir -p "$R/data"

# Fake `cmd`: settings in files, everything else logged
mkdir -p "$T/settings"
cat >"$T/cmd" <<EOS
#!/bin/bash
echo "\$*" >>"$T/cmd.log"
S="$T/settings"
case "\$1 \$2" in
"settings get") cat "\$S/\$3.\$4" 2>/dev/null || echo null ;;
"settings put") echo "\$5" >"\$S/\$3.\$4" ;;
"settings delete") rm -f "\$S/\$3.\$4" ;;
"power set-mode") echo "\$3" >"\$S/global.low_power" ;;
"power set-fixed-performance-mode-enabled") echo "\$3" >"\$S/fixedperf" ;;
"device_config get") cat "\$S/dc.\$3.\$4" 2>/dev/null || echo null ;;
"device_config put") echo "\$5" >"\$S/dc.\$3.\$4" ;;
"device_config delete") rm -f "\$S/dc.\$3.\$4" ;;
"notification set_dnd") case \$3 in off) echo 0;; priority) echo 1;; none) echo 2;; alarms) echo 3;; esac >"\$S/global.zen_mode" ;;
esac
EOS
chmod +x "$T/cmd"
echo 0 >"$T/settings/global.low_power"; echo 0 >"$T/settings/global.zen_mode"
echo 120.0 >"$T/settings/system.peak_refresh_rate"

# f2fs for the GC job
F=$R/sys/fs/f2fs/dm-5; mkdir -p "$F"; echo 9000 >"$F/dirty_segments"; echo 0 >"$F/gc_urgent"
export STRAND_GC_POLL_MS=100
export STRAND_ROOT=$R STRAND_CMD=$T/cmd STRAND_SOCKET=$T/sock
export STRAND_CONFIG="$HERE/config/strand.conf $HERE/tests/device.conf"
start() { "$HERE/daemon/strandd" 2>>"$T/strandd.log" & PID=$!; for _ in $(seq 50); do [ -S "$T/sock" ] && return; sleep 0.1; done; fail "no socket"; }
ctl() { "$HERE/daemon/strandctl" "$@"; }
v() { cat "$R$1"; }
s() { cat "$T/settings/$1" 2>/dev/null || echo null; }
settle() { sleep 0.4; }
start

[ "$(ctl ping)" = "ok strandd 2" ] || fail ping
settle
[ "$(s dc.test_ns.flag)" = on ] || fail "base action"
ctl status | grep -q '^low_storage 1$' || fail "low storage"
sleep 0.5; grep -q 'package trim-caches' "$T/cmd.log" || fail "low storage cache clear"
ctl profiles | grep -q '^battery Battery saver$' || fail profiles
P=/sys/devices/system/cpu/cpufreq

ctl profile performance >/dev/null; settle
[ "$(v $P/policy0/scaling_min_freq)" = 900000 ] || fail "perf policy0 min $(v $P/policy0/scaling_min_freq)"
[ "$(v $P/policy7/scaling_min_freq)" = 1200000 ] || fail "perf policy7 min $(v $P/policy7/scaling_min_freq)"
[ "$(v /sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/devfreq/3d00000.qcom,kgsl-3d0/min_freq)" = 400000000 ] || fail "perf gpu min"
[ "$(s system.min_refresh_rate)" = 120.0 ] || fail "perf min refresh"
[ "$(s fixedperf)" = true ] || fail "fixed performance mode"

ctl profile battery >/dev/null; settle
[ "$(v $P/policy0/scaling_min_freq)" = 300000 ] || fail "perf not reverted"
[ "$(s system.min_refresh_rate)" = null ] || fail "min refresh not deleted"
[ "$(s fixedperf)" = false ] || fail "fixed performance mode not reverted"
[ "$(v $P/policy7/scaling_max_freq)" = 2700000 ] && fail "battery cap not applied"
[ "$(s global.low_power)" = 1 ] || fail "battery saver"
[ "$(s system.peak_refresh_rate)" = 60.0 ] || fail "peak refresh"
[ "$(cat $R/props/sys.strand.blur.lite)" = 1 ] || fail "blur lite"
[ "$(s dc.device_idle.inactive_to)" = 600000 ] || fail "doze timing"
[ -s "$R/data/misc/strand/journal" ] || fail "journal not created"
grep -q '60.0' "$R/data/misc/strand/journal" || fail "journal"

# Crash while in battery: the new daemon undoes the old run, reapplies
# battery (the saved profile), and leaving it restores the real originals.
kill -9 $PID; wait $PID 2>/dev/null || true; rm -f "$T/sock"
start; settle
ctl status | grep -q '^effective battery$' || fail "battery not reapplied after crash"
ctl profile balanced >/dev/null; settle
[ "$(s global.low_power)" = 0 ] || fail "low_power not restored after crash"
[ "$(s system.peak_refresh_rate)" = 120.0 ] || fail "peak not restored after crash"
[ "$(v $P/policy7/scaling_max_freq)" = 2700000 ] || fail "cap not restored after crash"
[ "$(cat $R/props/sys.strand.blur.lite)" = "" ] || fail "blur lite not restored"
[ "$(s dc.device_idle.inactive_to)" = null ] || fail "doze timing not restored"

# Per-app: gaming while com.game is on top, back to the user profile after
ctl profile balanced >/dev/null
ctl app com.game gaming >/dev/null
ctl app 'bad;name' gaming >/dev/null && fail "bad package accepted"
sleep 100 & GAME=$!
sleep 100 & SYS=$!
mkdir -p "$R/proc/$SYS"; printf 'system_server\0' >"$R/proc/$SYS/cmdline"
printf '%s\n%s\n' $SYS $GAME >"$R/dev/cpuset/top-app/cgroup.procs"
mkdir -p "$R/proc/$GAME"; printf 'com.game\0' >"$R/proc/$GAME/cmdline"
sleep 1.6
ctl status | grep -q '^effective gaming$' || { ctl status; fail "per-app gaming"; }
[ "$(s global.zen_mode)" = 1 ] || fail "dnd"
: >"$R/dev/cpuset/top-app/cgroup.procs"
sleep 1.6
ctl status | grep -q '^effective balanced$' || fail "per-app revert"
[ "$(s global.zen_mode)" = 0 ] || fail "dnd revert"
kill $GAME $SYS

# State survives a restart
kill $PID; wait $PID 2>/dev/null || true; rm -f "$T/sock"; start; settle
ctl apps | grep -q '^com.game gaming$' || fail "app profiles not restored"
ctl profile | grep -q '^ok balanced$' || fail "profile not restored"
ctl profile battery >/dev/null; kill $PID; wait $PID 2>/dev/null || true; rm -f "$T/sock"; start; settle
ctl status | grep -q '^effective battery$' || fail "battery not reapplied after restart"
[ "$(s global.low_power)" = 1 ] || fail "battery saver after restart"
ctl profile balanced >/dev/null; settle
[ "$(s global.low_power)" = 0 ] || fail "battery saver off"
# strandd wrote its PSI trigger into the fake file; put the stats back
printf 'some avg10=0.50 avg60=0.10 avg300=0.00 total=1234\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=12\n' >"$R/proc/pressure/memory"
ctl stats >"$T/stats"; grep -q "^gpu_freq_mhz 253$" "$T/stats" || { cat "$T/stats"; fail "stat div"; }
ctl stats | grep -q '^psi_memory_some_avg10 0.50$' || fail "psi stats"
ctl stats | grep -q '^cpu_policy4_max 2400000$' || fail "cpufreq stats"
ctl dexopt speed com.a com.b >/dev/null; ctl clearcache >/dev/null; sleep 0.5
grep -q 'package compile -m speed -f com.b' "$T/cmd.log" || fail dexopt
grep -q 'package trim-caches' "$T/cmd.log" || fail clearcache
ctl dexopt bogus com.a >/dev/null && fail "bad filter accepted"

# Auto game mode: a known game in front gets the gaming profile and quiet mode
ctl apps clear >/dev/null; ctl profile balanced >/dev/null
ctl games add com.racer >/dev/null
sleep 100 & RACER=$!
mkdir -p "$R/proc/$RACER"; printf 'com.racer\0' >"$R/proc/$RACER/cmdline"
echo $RACER >"$R/dev/cpuset/top-app/cgroup.procs"
sleep 1.6
ctl status | grep -q '^effective gaming$' || { ctl status; fail "auto game profile"; }
ctl status | grep -q '^quiet 1$' || fail "quiet with a game in front"
# Maintenance waits while the game runs
ctl gc >/dev/null; sleep 0.5
ctl status | grep -q '^maint waiting' || { ctl status; fail "gc did not wait for the game"; }
[ "$(cat $F/gc_urgent)" = 0 ] || fail "gc ran during a game"
ctl autogame off >/dev/null; sleep 0.5
ctl status | grep -q '^effective balanced$' || fail "autogame off"
ctl autogame on >/dev/null

# Game ends: GC runs until few dirty segments are left, then restores gc_urgent
( until grep -q 1 "$F/gc_urgent"; do sleep 0.05; done
  for d in 7000 5000 3000 1000 400; do sleep 0.3; echo $d >"$F/dirty_segments"; done ) &
: >"$R/dev/cpuset/top-app/cgroup.procs"
kill $RACER
for _ in $(seq 100); do ctl status | grep -q '^last_gc dirty' && break; sleep 0.2; done
ctl status | grep -q 'last_gc dirty dm-5:9000->400(done)' || { ctl status; fail "gc"; }
[ "$(cat $F/gc_urgent)" = 0 ] || fail "gc_urgent not restored"
# The idle window ends: stop interrupts a GC that is making no progress
echo 8000 >"$F/dirty_segments"
ctl gc >/dev/null; sleep 0.4
[ "$(cat $F/gc_urgent)" = 1 ] || fail "gc did not start"
ctl stop >/dev/null
for _ in $(seq 30); do grep -q 0 "$F/gc_urgent" && break; sleep 0.1; done
ctl status | grep -q 'last_gc dirty dm-5:8000->8000(stopped)' || { ctl status; fail "stop"; }
[ "$(cat $F/gc_urgent)" = 0 ] || fail "gc_urgent not restored after stop"
ctl games clear >/dev/null

# Hot device: hot overlay and quiet; back when it cools
ctl thermal 3 >/dev/null; settle
[ "$(cat $R/props/sys.strand.blur.lite)" = 1 ] || fail "hot overlay"
ctl status | grep -q '^quiet 1$' || fail "quiet when hot"
ctl thermal 1 >/dev/null; settle
[ "$(cat $R/props/sys.strand.blur.lite)" = "" ] || fail "hot overlay not reverted"
ctl status | grep -q '^quiet 0$' || fail "quiet after cooling"

# Heavy GPU work (any app) makes the system quiet; it lifts once the load drops
mkdir -p "$R/sys/class/kgsl/kgsl-3d0"; echo "95 %" >"$R/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"
sleep 2.5
ctl status | grep -q '^gpu_heavy 1$' || { ctl status; fail "gpu heavy"; }
ctl status | grep -q '^quiet 1$' || fail "quiet on gpu load"
echo "20 %" >"$R/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"
sleep 1.5
ctl status | grep -q '^quiet 0$' || fail "quiet after gpu load"
ctl profile nope >/dev/null && fail "unknown profile accepted"
echo "all host tests passed"
