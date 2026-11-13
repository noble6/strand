// SPDX-License-Identifier: GPL-3.0-or-later
package org.strand.engine.resona;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The device's factory speaker calibration, made at build time from the stock
 * Dolby tuning (audio/tools/dax2resona.py), as Resona correction sections.
 */
final class SpeakerTuning {
    static final String PATH = "/system_ext/etc/strand/resona_speaker.json";

    /** Correction sections for the engine (10 floats each) and their preamp. */
    static final class Curve {
        final float[] sections;
        final float preamp;

        Curve(float[] sections, float preamp) {
            this.sections = sections;
            this.preamp = preamp;
        }
    }

    final Curve landscape, portrait;
    final float bassHigh;

    private SpeakerTuning(Curve landscape, Curve portrait, float bassHigh) {
        this.landscape = landscape;
        this.portrait = portrait;
        this.bassHigh = bassHigh;
    }

    Curve forOrientation(boolean isLandscape) {
        Curve c = isLandscape ? landscape : portrait;
        return c != null ? c : (isLandscape ? portrait : landscape);
    }

    /** Null on devices without a calibration file. */
    static SpeakerTuning load() {
        try {
            File f = new File(PATH);
            if (!f.canRead()) return null;
            JSONObject root = new JSONObject(new String(Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8));
            JSONObject tunings = root.getJSONObject("tunings");
            Curve land = null, port = null;
            for (java.util.Iterator<String> it = tunings.keys(); it.hasNext(); ) {
                JSONObject t = tunings.getJSONObject(it.next());
                Curve c = curve(t);
                String o = t.optString("orientation", "any");
                if (o.equals("landscape") || (o.equals("any") && land == null)) land = c;
                if (o.equals("portrait") || (o.equals("any") && port == null)) port = c;
            }
            float bass = (float) root.optDouble("bass_src_high", 0);
            return land == null && port == null ? null : new SpeakerTuning(land, port, bass);
        } catch (Exception e) {
            return null;
        }
    }

    private static Curve curve(JSONObject t) throws Exception {
        JSONArray s = t.getJSONArray("sections");
        int n = Math.min(s.length(), 24); // RESONA_NCORR
        float[] out = new float[n * 10];
        for (int i = 0; i < n; i++) {
            JSONArray e = s.getJSONArray(i);
            out[i * 10] = (float) e.getDouble(0);     // type
            out[i * 10 + 1] = (float) e.getDouble(1); // freq
            out[i * 10 + 2] = (float) e.getDouble(2); // gain
            out[i * 10 + 3] = (float) e.getDouble(3); // q
        }
        return new Curve(out, (float) t.optDouble("preamp", 0));
    }
}
