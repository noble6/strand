#!/bin/bash
# Create the signing keys for the Strand APEX, once per ROM tree:
#   vendor/strand/tools/make-keys.sh
# They go in vendor/strand/apex/keys/ (never committed). Updates must be
# signed with the same keys as the ROM, so back that folder up with your
# other signing keys.
set -euo pipefail
K=$(cd "$(dirname "$0")/../apex" && pwd)/keys
if [ -f "$K/org.strand.engine.pem" ]; then
    echo "keys already exist in $K"
    exit 0
fi
mkdir -p "$K"
chmod 700 "$K"
cd "$K"
openssl genrsa -out org.strand.engine.pem 4096 2>/dev/null
# AVB public key (avbtool's AvbRSAPublicKeyHeader encoding)
python3 - <<'PY'
import re, struct, subprocess
mod = subprocess.run(["openssl", "rsa", "-in", "org.strand.engine.pem", "-noout", "-modulus"],
                     capture_output=True, text=True, check=True).stdout
n = int(re.search(r"Modulus=([0-9A-Fa-f]+)", mod).group(1), 16)
bits = n.bit_length()
b = 2 ** 32
n0inv = b - pow(n, -1, b)
rr = pow(2 ** bits, 2, n)
enc = lambda v: v.to_bytes(bits // 8, "big")
open("org.strand.engine.avbpubkey", "wb").write(struct.pack("!II", bits, n0inv) + enc(n) + enc(rr))
PY
openssl req -new -x509 -sha256 -days 10000 -newkey rsa:4096 -nodes -keyout container.key \
    -out org.strand.engine.x509.pem -subj "/O=Strand/CN=org.strand.engine" 2>/dev/null
openssl pkcs8 -topk8 -outform DER -nocrypt -in container.key -out org.strand.engine.pk8
rm container.key
chmod 600 ./*
echo "Strand APEX keys created in $K. Back them up: updates need the same keys."
