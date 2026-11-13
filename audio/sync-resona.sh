#!/bin/bash
# Copy the Resona DSP engine, unmodified, from a Resona checkout.
#   audio/sync-resona.sh ~/resona
set -euo pipefail
SRC=$(cd "${1:?path to a resona checkout}" && pwd)
DST=$(cd "$(dirname "$0")/resona" && pwd)
cp "$SRC"/engine/{resona_dsp.c,resona_shm.h,ladspa_abi.h} "$DST"/
cp "$SRC"/LICENSE "$DST"/LICENSE
rev=$(git -C "$SRC" rev-parse --short HEAD)
printf 'https://github.com/noble6/resona engine/ at %s\nCopied unmodified by audio/sync-resona.sh; change the DSP upstream, then sync.\n' "$rev" >"$DST/UPSTREAM"
echo "synced Resona $rev"
