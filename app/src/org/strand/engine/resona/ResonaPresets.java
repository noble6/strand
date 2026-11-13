// SPDX-License-Identifier: GPL-3.0-or-later
package org.strand.engine.resona;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Profiles, device overlays and EQ presets, ported from Resona's
 * app/resona/presets.py so a profile sounds the same on Linux and Android.
 */
final class ResonaPresets {
    private ResonaPresets() {}

    /** Where the sound goes. Built-in tablet/phone speakers are small drivers, like a laptop's. */
    enum Device { SPEAKER, HEADPHONES, EXTERNAL }

    static final String AUTO = "Auto";

    static final Map<String, Float> DEFAULTS = new LinkedHashMap<>();
    static final Map<String, float[]> EQ = new LinkedHashMap<>();
    static final Map<String, Profile> PROFILES = new LinkedHashMap<>();
    static final String[] IEQ_TARGETS = {"Balanced", "Warm", "Focused", "Bright"};
    static final String[] BASS_TYPES = {"Balanced", "Sub Boost", "Punchy", "Warm"};

    static final class Profile {
        final String name, description, eq;
        final int petals; // shape of its chip/button: 0 circle, n lobes
        final Map<String, Float> params = new HashMap<>();

        Profile(String name, String description, int petals, String eq, Object... kv) {
            this.name = name;
            this.description = description;
            this.petals = petals;
            this.eq = eq;
            for (int i = 0; i < kv.length; i += 2) {
                params.put((String) kv[i], ((Number) kv[i + 1]).floatValue());
            }
        }
    }

    private static void d(String k, float v) { DEFAULTS.put(k, v); }

    static {
        d("enable", 1); d("preamp", 0); d("output", 0);
        d("eq_on", 1);
        for (int i = 0; i < 10; i++) d("eq" + i, 0);
        d("bass_on", 1); d("bass_amt", 0.45f); d("bass_freq", 100); d("bass_punch", 0.3f);
        d("clar_on", 1); d("clar_amt", 0.3f);
        d("voice_on", 0); d("voice_amt", 0.5f);
        d("stage_on", 1); d("stage_width", 1.25f); d("stage_depth", 0.2f);
        d("xfeed_on", 0); d("xfeed_amt", 0.5f);
        d("amb_on", 0); d("amb_mix", 0.2f); d("amb_size", 0.4f); d("amb_damp", 0.5f);
        d("lev_on", 0); d("lev_amt", 0.6f); d("lev_target", -16);
        d("loud_on", 1); d("loud_amt", 0.6f);
        d("lim_on", 1); d("lim_ceil", -0.5f);
        d("sysvol_db", 0);
        d("ieq_on", 0); d("ieq_amt", 0.5f); d("ieq_target", 0);
        d("tube_on", 0); d("tube_drive", 0.3f);
        d("hrtf_on", 0); d("hrtf_angle", 30); d("hrtf_amt", 1.0f);
        d("corr_on", 0); d("corr_preamp", 0);
        d("upmix_on", 1); d("upmix_center", 0.7f); d("upmix_surr", 0.6f); d("upmix_lfe", 0.5f);
        d("layout", 0);
        d("bass_type", 0);

        EQ.put("Flat", new float[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        EQ.put("Bass Boost", new float[] {6, 5, 3.5f, 1.5f, 0, 0, 0, 0, 0, 0});
        EQ.put("Warm", new float[] {3, 2.5f, 1.5f, 0.5f, 0, -0.5f, -1, -1, 0, 0.5f});
        EQ.put("V-Shape", new float[] {4.5f, 3.5f, 1.5f, -0.5f, -2, -2, -0.5f, 1.5f, 3.5f, 4.5f});
        EQ.put("Vocal", new float[] {-2, -1.5f, -0.5f, 1, 2.5f, 3, 2.5f, 1.5f, 0, -1});
        EQ.put("Treble", new float[] {0, 0, 0, 0, 0, 0.5f, 1.5f, 3, 4.5f, 5.5f});
        EQ.put("Rock", new float[] {4, 3, 1.5f, -0.5f, -1, 0, 1.5f, 3, 3.5f, 3.5f});
        EQ.put("Electronic", new float[] {5, 4, 1.5f, 0, -1.5f, 0, 0.5f, 1.5f, 3.5f, 4});
        EQ.put("Hip-Hop", new float[] {5.5f, 4.5f, 2, 1.5f, -0.5f, -0.5f, 1, 0, 1, 2});
        EQ.put("Jazz", new float[] {2.5f, 2, 1, 1.5f, -1, -1, 0, 1, 2, 2.5f});
        EQ.put("Classical", new float[] {3, 2.5f, 1.5f, 1, -0.5f, -0.5f, 0, 1.5f, 2, 2.5f});
        EQ.put("Acoustic", new float[] {3, 2.5f, 2, 1, 1.5f, 1, 2, 2.5f, 2, 1});
        EQ.put("Podcast", new float[] {-6, -4, -1, 1, 2, 2.5f, 2.5f, 1.5f, 0, -2});
        EQ.put("Footsteps", new float[] {-2, -2, -1, 0, 0, 1, 3.5f, 4.5f, 2.5f, 0});

        add(new Profile("Dynamic", "Balanced lift for everything", 9, "Flat",
                "bass_type", 0, "bass_on", 1, "bass_amt", 0.45f, "bass_punch", 0.3f,
                "clar_on", 1, "clar_amt", 0.3f, "voice_on", 0, "stage_on", 1,
                "stage_width", 1.25f, "stage_depth", 0.2f, "amb_on", 0, "lev_on", 0,
                "loud_on", 1, "loud_amt", 0.6f, "lim_ceil", -0.5f, "ieq_on", 1,
                "ieq_amt", 0.4f, "ieq_target", 0, "tube_on", 0));
        add(new Profile("Music", "Rich, wide and detailed", 8, "Warm",
                "bass_type", 0, "bass_on", 1, "bass_amt", 0.55f, "bass_punch", 0.35f,
                "clar_on", 1, "clar_amt", 0.35f, "voice_on", 0, "stage_on", 1,
                "stage_width", 1.4f, "stage_depth", 0.3f, "amb_on", 0, "lev_on", 0,
                "loud_on", 1, "loud_amt", 0.6f, "lim_ceil", -0.5f, "ieq_on", 1,
                "ieq_amt", 0.35f, "ieq_target", 1, "tube_on", 1, "tube_drive", 0.2f));
        add(new Profile("Movie", "Cinematic stage, clear dialogue", 12, "V-Shape",
                "bass_type", 1, "bass_on", 1, "bass_amt", 0.6f, "bass_punch", 0.45f,
                "clar_on", 1, "clar_amt", 0.25f, "voice_on", 1, "voice_amt", 0.45f,
                "stage_on", 1, "stage_width", 1.6f, "stage_depth", 0.55f, "amb_on", 1,
                "amb_mix", 0.16f, "amb_size", 0.6f, "amb_damp", 0.55f, "lev_on", 1,
                "lev_amt", 0.5f, "lev_target", -16, "loud_on", 1, "loud_amt", 0.7f,
                "lim_ceil", -0.5f, "ieq_on", 1, "ieq_amt", 0.3f, "ieq_target", 2, "tube_on", 0,
                "upmix_center", 0.8f, "upmix_surr", 0.7f, "upmix_lfe", 0.6f));
        add(new Profile("Gaming", "Pinpoint footsteps and positioning", 4, "Footsteps",
                "bass_type", 2, "bass_on", 1, "bass_amt", 0.3f, "bass_punch", 0.2f,
                "clar_on", 1, "clar_amt", 0.5f, "voice_on", 1, "voice_amt", 0.2f,
                "stage_on", 1, "stage_width", 1.7f, "stage_depth", 0.4f, "amb_on", 0,
                "lev_on", 1, "lev_amt", 0.35f, "lev_target", -16, "loud_on", 1,
                "loud_amt", 0.5f, "lim_ceil", -0.5f, "ieq_on", 0, "tube_on", 0,
                "upmix_center", 0.5f, "upmix_surr", 0.8f, "upmix_lfe", 0.4f));
        add(new Profile("Voice", "Podcasts, calls and lectures", 2, "Podcast",
                "bass_type", 0, "bass_on", 0, "clar_on", 1, "clar_amt", 0.25f, "voice_on", 1,
                "voice_amt", 0.8f, "stage_on", 1, "stage_width", 0.9f, "stage_depth", 0.0f,
                "amb_on", 0, "lev_on", 1, "lev_amt", 0.8f, "lev_target", -16, "loud_on", 1,
                "loud_amt", 0.5f, "lim_ceil", -1, "ieq_on", 1, "ieq_amt", 0.5f,
                "ieq_target", 2, "tube_on", 0, "upmix_center", 1.0f, "upmix_surr", 0.2f,
                "upmix_lfe", 0.2f));
        add(new Profile("Night", "Quiet listening, no surprises", 6, "Flat",
                "bass_type", 3, "bass_on", 1, "bass_amt", 0.35f, "bass_punch", 0.1f,
                "clar_on", 1, "clar_amt", 0.2f, "voice_on", 1, "voice_amt", 0.4f,
                "stage_on", 1, "stage_width", 1.1f, "stage_depth", 0.1f, "amb_on", 0,
                "lev_on", 1, "lev_amt", 1.0f, "lev_target", -22, "loud_on", 1,
                "loud_amt", 0.9f, "lim_ceil", -3, "ieq_on", 1, "ieq_amt", 0.4f,
                "ieq_target", 0, "tube_on", 0, "upmix_lfe", 0.25f));
        add(new Profile("Pure", "Untouched. Just the limiter", 0, "Flat",
                "bass_type", 0, "bass_on", 0, "clar_on", 0, "voice_on", 0, "stage_on", 0,
                "stage_width", 1.0f, "stage_depth", 0.0f, "amb_on", 0, "lev_on", 0,
                "loud_on", 0, "xfeed_on", 0, "hrtf_on", 0, "ieq_on", 0, "tube_on", 0,
                "lim_ceil", -0.3f, "upmix_on", 0));
    }

    private static void add(Profile p) { PROFILES.put(p.name, p); }

    /** Headphone spatial flavour per profile: virtual speakers for films and games. */
    private static String headphoneSpatial(String profile) {
        switch (profile) {
            case "Movie":
            case "Gaming":
                return "hrtf";
            case "Music":
            case "Dynamic":
            case "Night":
                return "xfeed";
            default:
                return null;
        }
    }

    /** Every engine parameter for a profile on a device, before user overrides. */
    static float[] params(String name, Device device) {
        Profile prof = PROFILES.containsKey(name) ? PROFILES.get(name) : PROFILES.get("Dynamic");
        Map<String, Float> out = new HashMap<>(DEFAULTS);
        out.putAll(prof.params);
        float[] eq = EQ.containsKey(prof.eq) ? EQ.get(prof.eq) : EQ.get("Flat");
        for (int i = 0; i < 10; i++) out.put("eq" + i, eq[i]);

        boolean pure = prof.name.equals("Pure");
        switch (device) {
            case HEADPHONES:
                out.put("bass_freq", 90f);
                if (!pure) {
                    String mode = headphoneSpatial(prof.name);
                    out.put("hrtf_on", "hrtf".equals(mode) ? 1f : 0f);
                    out.put("xfeed_on", "xfeed".equals(mode) ? 1f : 0f);
                    out.put("xfeed_amt", 0.45f);
                }
                break;
            case EXTERNAL:
                out.put("xfeed_on", 0f);
                out.put("hrtf_on", 0f);
                out.put("bass_freq", 90f);
                break;
            case SPEAKER:
                // Small drivers can't move air below ~150 Hz: lean on psychoacoustic
                // bass, keep the real low shelf gentle, and leave the amps headroom.
                out.put("xfeed_on", 0f);
                out.put("hrtf_on", 0f);
                out.put("bass_freq", 170f);
                if (!pure && out.get("bass_on") > 0) {
                    out.put("bass_amt", Math.max(out.get("bass_amt"), 0.7f));
                    out.put("bass_punch", Math.min(out.get("bass_punch"), 0.15f));
                }
                if (!pure && out.get("stage_on") > 0) {
                    out.put("stage_width", Math.min(2.0f, out.get("stage_width") + 0.15f));
                }
                out.put("lim_ceil", Math.min(out.get("lim_ceil"), -1.0f));
                break;
        }
        if (pure) out.put("xfeed_on", 0f);

        float[] p = new float[ResonaParams.COUNT];
        for (int i = 0; i < p.length; i++) {
            Float v = out.get(ResonaParams.KEYS[i]);
            p[i] = v != null ? v : 0f;
        }
        return p;
    }
}
