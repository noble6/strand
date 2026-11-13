#!/usr/bin/env python3
"""Turn a device's stock Dolby speaker calibration into Resona correction EQ.

    audio/tools/dax2resona.py vendor/etc/dolby/dax-default.xml > resona_speaker.json

Phone and tablet makers calibrate their speakers in Dolby's DAX tuning file
(the "audio optimizer" curve per endpoint and orientation). This reads those
curves, nothing else, and fits peaking filters whose summed response matches
them, so Resona plays the speakers with the factory calibration. Gains in the
file are 1/16 dB.

Output: {"tunings": {"speaker_landscape": {"orientation": "landscape",
          "preamp": dB, "sections": [[type, freq, gain, q], ...]}, ...},
         "bass_src_high": Hz}
type 1 is a peaking filter (resona_shm.h CORR_PK).
"""
import json
import math
import sys
import xml.etree.ElementTree as ET

FS = 48000.0
ORIENT = {"0": "portrait", "1": "landscape", "2": "any"}


def peak_db(f, fc, gain, q):
    """Magnitude in dB at f of the RBJ peaking filter the engine designs."""
    a = 10 ** (gain / 40)
    w0 = 2 * math.pi * fc / FS
    al = math.sin(w0) / (2 * q)
    b = [1 + al * a, -2 * math.cos(w0), 1 - al * a]
    d = [1 + al / a, -2 * math.cos(w0), 1 - al / a]
    w = 2 * math.pi * f / FS
    z = complex(math.cos(w), -math.sin(w))
    num = b[0] + b[1] * z + b[2] * z * z
    den = d[0] + d[1] * z + d[2] * z * z
    return 20 * math.log10(abs(num / den))


def fit(freqs, target):
    n = len(freqs)
    qs = []
    for i, f in enumerate(freqs):
        lo = freqs[i - 1] if i else f / (freqs[1] / freqs[0])
        hi = freqs[i + 1] if i + 1 < n else f * (f / freqs[i - 1])
        bw = math.log2(hi / lo) / 2  # octaves to the neighbours, on average
        bw = min(max(bw, 0.3), 2.5)
        qs.append(math.sqrt(2 ** bw) / (2 ** bw - 1))
    gains = list(target)
    for _ in range(60):
        resp = [sum(peak_db(f, freqs[k], gains[k], qs[k]) for k in range(n)) for f in freqs]
        gains = [max(-12.0, min(12.0, g + 0.6 * (t - r))) for g, t, r in zip(gains, target, resp)]
    resp = [sum(peak_db(f, freqs[k], gains[k], qs[k]) for k in range(n)) for f in freqs]
    err = max(abs(t - r) for t, r in zip(target, resp))
    sections = [[1, f, round(g, 2), round(q, 3)] for f, g, q in zip(freqs, gains, qs) if abs(g) >= 0.1]
    return sections, err


def main():
    root = ET.parse(sys.argv[1]).getroot()
    out = {"tunings": {}, "bass_src_high": None}
    for tu in root.iter("tuning"):
        if tu.get("endpoint_type") != "speaker":
            continue
        enabled = [e.get("value") for e in tu.iter("audio-optimizer-enable")]
        bands = [(float(b.get("frequency")),
                  (int(b.get("gain_left")) + int(b.get("gain_right"))) / 32.0)
                 for b in tu.iter("band_optimizer")]
        if not bands or enabled[:1] != ["true"]:
            continue
        freqs = [f for f, _ in bands]
        target = [g for _, g in bands]
        sections, err = fit(freqs, target) if any(abs(t) >= 0.1 for t in target) else ([], 0.0)
        out["tunings"][tu.get("name")] = {
            "orientation": ORIENT.get(tu.get("orientation"), "any"),
            # The curve only cuts on ruan; leave headroom for any boost.
            "preamp": round(-max(0.0, max(target)), 2) + 0.0,
            "sections": sections,
            "fit_error_db": round(err, 2),
        }
        for vb in tu.iter("virtual-bass-src-freqs"):
            out["bass_src_high"] = float(vb.get("frequency_high"))
    json.dump(out, sys.stdout, indent=1)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
