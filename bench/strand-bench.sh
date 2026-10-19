#!/bin/bash
# Strand benchmark: app launch times, UI jank and app retention over adb.
#
#   strand-bench.sh [-n RUNS] [-l LABEL] [-a APPS] [TEST...]
#
# Tests: info launch jank retention (default: all). Results land in
# results/<label>-<time>/; summary.txt has the numbers to compare.
# Cold launches drop the page cache when adb runs as root.
set -euo pipefail

RUNS=5
LABEL=baseline
HERE=$(cd "$(dirname "$0")" && pwd)
APPS=$HERE/apps.txt
while getopts n:l:a: o; do
	case $o in
	n) RUNS=$OPTARG ;;
	l) LABEL=$OPTARG ;;
	a) APPS=$OPTARG ;;
	*) exit 1 ;;
	esac
done
shift $((OPTIND - 1))
TESTS=${*:-info launch jank retention}

OUT=$HERE/results/$LABEL-$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT"
SUM=$OUT/summary.txt

sh() { adb shell "$@" | tr -d '\r'; }
log() { echo "$*" | tee -a "$SUM"; }
mapfile -t COMPS < <(grep -v -e '^#' -e '^$' "$APPS")
ROOT=$([ "$(sh id -u)" = 0 ] && echo 1 || echo 0)
read -r W H < <(sh wm size | awk -F'[ x]' '/Physical/ {print $3, $4}')

# median and p90 of the numbers on stdin
stats() { sort -n | awk '{v[NR]=$1} END {if (NR) printf "median %d  p90 %d  (n=%d)", v[int((NR+1)/2)], v[int(NR*0.9+0.999)], NR}'; }

prepare() {
	sh 'svc power stayon usb; input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; input keyevent KEYCODE_HOME'
	sleep 2
}

# am start -W; prints "<state> <ms>"
start() {
	sh am start -W "$@" | awk '/LaunchState/ {s=$2} /TotalTime/ {t=$2} END {print s, t}'
}

home() { sh input keyevent KEYCODE_HOME; sleep "${1:-2}"; }

test_info() {
	{
		echo "== build"; sh getprop ro.lineage.version; sh uname -r
		echo "== vm"
		sh 'cd /proc/sys/vm; for f in swappiness watermark_scale_factor min_free_kbytes extra_free_kbytes page-cluster vfs_cache_pressure compaction_proactiveness; do echo "$f=$(cat $f)"; done'
		echo "== zram"; sh 'cat /sys/block/zram0/disksize /sys/block/zram0/comp_algorithm'
		echo "== lmkd"; sh 'getprop | grep -E "ro.lmk|ro.config.low_ram"' || true
		echo "== cpufreq"
		sh 'for p in /sys/devices/system/cpu/cpufreq/policy*; do echo "$p $(cat $p/scaling_governor) $(cat $p/scaling_min_freq)-$(cat $p/scaling_max_freq) cur=$(cat $p/scaling_cur_freq)"; done'
		echo "== uclamp"; sh 'for g in top-app foreground background; do echo "$g min=$(cat /dev/cpuctl/$g/cpu.uclamp.min) max=$(cat /dev/cpuctl/$g/cpu.uclamp.max)"; done'
		echo "== meminfo"; sh 'head -5 /proc/meminfo'
	} > "$OUT/info.txt"
	log "info: saved"
}

test_launch() {
	echo "app,run,kind,state,ms" > "$OUT/launch.csv"
	for c in "${COMPS[@]}"; do
		pkg=${c%%/*}
		for r in $(seq "$RUNS"); do
			sh am force-stop "$pkg"
			[ $ROOT = 1 ] && sh 'sync; echo 3 > /proc/sys/vm/drop_caches'
			sleep 1
			echo "$pkg,$r,cold,$(start -n "$c" | tr ' ' ,)" >> "$OUT/launch.csv"
			home
			echo "$pkg,$r,warm,$(start -n "$c" | tr ' ' ,)" >> "$OUT/launch.csv"
			home
		done
		sh am force-stop "$pkg"
	done
	# a "cold" launch only counts if the process was really gone
	log "launch cold: $(awk -F, '$3=="cold" && $4=="COLD" {print $5}' "$OUT/launch.csv" | stats) ms"
	log "launch warm: $(awk -F, '$3=="warm" {print $5}' "$OUT/launch.csv" | stats) ms"
	for c in "${COMPS[@]}"; do
		pkg=${c%%/*}
		log "  ${pkg##*.} cold $(awk -F, -v p="$pkg" '$1==p && $3=="cold" && $4=="COLD" {print $5}' "$OUT/launch.csv" | stats)"
	done
}

# frames for one package after running the gesture function $2
jank_run() {
	sh dumpsys gfxinfo "$1" reset > /dev/null
	$2
	sh dumpsys gfxinfo "$1" | awk -v p="$1" -v t="$3" '
		/Total frames rendered/ {f=$4} /^Janky frames:/ {j=$3; jp=$4}
		/^50th percentile/ {p50=$3} /^90th percentile/ {p90=$3}
		/^95th percentile/ {p95=$3} /^99th percentile/ {p99=$3}
		END {gsub(/[()%]/, "", jp); gsub(/ms/, "", p50); gsub(/ms/, "", p90); gsub(/ms/, "", p95); gsub(/ms/, "", p99)
		     print t "," f "," j "," jp "," p50 "," p90 "," p95 "," p99}'
}

# Developer options: the longest list in Settings, in the right pane on tablets
scroll_settings() {
	local x=$((W * 13 / 20))
	for _ in $(seq 10); do
		sh input swipe $x $((H * 3 / 4)) $x $((H / 4)) 150; sleep 0.6
		sh input swipe $x $((H / 4)) $x $((H * 3 / 4)) 150; sleep 0.6
	done
}

pull_shade() {
	for _ in $(seq 10); do
		sh input swipe $((W / 2)) 5 $((W / 2)) $((H * 2 / 3)) 250; sleep 0.8
		sh input swipe $((W / 2)) $((H * 2 / 3)) $((W / 2)) 5 250; sleep 0.8
	done
}

test_jank() {
	echo "test,run,frames,janky,janky_pct,p50,p90,p95,p99" > "$OUT/jank.csv"
	for r in $(seq "$RUNS"); do
		start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS > /dev/null; sleep 2
		echo "$(jank_run com.android.settings scroll_settings settings | sed "s/,/,$r,/")" >> "$OUT/jank.csv"
		home
		echo "$(jank_run com.android.systemui pull_shade shade | sed "s/,/,$r,/")" >> "$OUT/jank.csv"
	done
	sh am force-stop com.android.settings
	for t in settings shade; do
		log "jank $t: janky% $(awk -F, -v t=$t '$1==t {print int($5*10)}' "$OUT/jank.csv" | stats | sed 's/median \([0-9]*\)/median \1 permille/;s/p90 \([0-9]*\)/p90 \1 permille/')"
		log "  $t p99 frame: $(awk -F, -v t=$t '$1==t {print $9}' "$OUT/jank.csv" | stats) ms"
	done
}

vm() { sh "grep -E '^($1) ' /proc/vmstat" | awk '{s+=$2} END {print s+0}'; }
psi_us() { sh cat /proc/pressure/memory | awk -v k="$1" '$1==k {sub("total=", "", $5); print $5}'; }

test_retention() {
	echo "round,app,state,ms" > "$OUT/retention.csv"
	for c in "${COMPS[@]}"; do sh am force-stop "${c%%/*}"; done
	sh logcat -b events -c
	local sin sout psome pfull
	sin=$(vm pswpin); sout=$(vm pswpout); psome=$(psi_us some); pfull=$(psi_us full)
	for round in 1 2 3; do
		for c in "${COMPS[@]}"; do
			echo "$round,${c%%/*},$(start -n "$c" | tr ' ' ,)" >> "$OUT/retention.csv"
			sleep 3
			home 1
		done
	done
	local kept total kills
	kept=$(awk -F, '$1>1 && $3!="COLD"' "$OUT/retention.csv" | wc -l)
	total=$(awk -F, '$1>1' "$OUT/retention.csv" | wc -l)
	kills=$(sh logcat -b events -d | grep -c killinfo || true)
	log "retention: $kept/$total relaunches stayed cached, lmkd kills $kills"
	log "  swap in $(( $(vm pswpin) - sin )) out $(( $(vm pswpout) - sout )) pages, memory PSI some $(( ($(psi_us some) - psome) / 1000 )) ms full $(( ($(psi_us full) - pfull) / 1000 )) ms"
	log "  zram: $(sh cat /sys/block/zram0/mm_stat | awk '{printf "orig %d MB, compressed %d MB", $1/1048576, $2/1048576}')"
}

log "strand-bench $LABEL $(date -Is) runs=$RUNS root=$ROOT ${W}x$H apps=${#COMPS[@]}"
prepare
for t in $TESTS; do "test_$t"; done
log "results: $OUT"
