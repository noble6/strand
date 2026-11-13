#!/bin/bash
# Publish a Strand update (engine APEX + app) built in a ROM tree.
#
#   cd <rom tree> && source build/envsetup.sh && lunch <target>
#   m org.strand.engine Strand
#   vendor/strand/tools/make-release.sh [notes]        # needs gh, logged in
#
# Devices update only to files signed with the keys of their build, so
# release from the tree (and keys) that built the ROM people run. Bump
# apex/apex_manifest.json "version" and the app's android:versionCode first.
set -euo pipefail
: "${OUT:?run lunch first}"
NOTES=${1:-}
TOP=$(pwd)
S=$TOP/vendor/strand
APEX=$(find "$OUT" -path '*/apex/org.strand.engine.apex' -print -quit)
APK=$(find "$OUT" -path '*/priv-app/Strand/Strand.apk' -print -quit)
[ -f "$APEX" ] || { echo "no org.strand.engine.apex under $OUT"; exit 1; }
[ -f "$APK" ] || { echo "no Strand.apk under $OUT"; exit 1; }

apex_ver=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["version"])' "$S/apex/apex_manifest.json")
app_ver=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$S/app/AndroidManifest.xml" | head -1)
tag="strand-e${apex_ver}-a${app_ver}"

D=$(mktemp -d)
trap 'rm -rf "$D"' EXIT
cp "$APEX" "$D/org.strand.engine.apex"
cp "$APK" "$D/Strand.apk"
python3 - "$D" "$apex_ver" "$app_ver" <<'PY'
import hashlib, json, sys
d, apex, app = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
sha = {n: hashlib.sha256(open(f"{d}/{n}", "rb").read()).hexdigest()
       for n in ("org.strand.engine.apex", "Strand.apk")}
json.dump({"apex_version": apex, "app_version": app, "sha256": sha},
          open(f"{d}/update.json", "w"), indent=1)
PY
cat "$D/update.json"
gh release create "$tag" -R noble6/strand --title "Strand engine $apex_ver, app $app_ver" \
    --notes "${NOTES:-Strand update}" \
    "$D/org.strand.engine.apex" "$D/Strand.apk" "$D/update.json"
