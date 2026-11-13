// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

import java.util.HashMap;
import java.util.Map;

/**
 * Strand's colors. "wallpaper" uses the system's dynamic Material You
 * tones (res/values*); a preset builds the same Material 3 roles from a seed
 * hue. Pure black makes dark backgrounds #000. Every view asks here.
 */
public final class Theme {
    private Theme() {}

    /** Name, seed hue (-1 = wallpaper, -2 = mono). */
    public static final String[] NAMES = {"wallpaper", "teal", "blue", "purple", "pink", "red",
            "orange", "green", "mono"};
    public static final float[] HUES = {-1, 178, 215, 270, 330, 4, 28, 130, -2};

    private static Map<Integer, Integer> sPalette;
    private static String sKey;

    public static String current(Context c) {
        return c.getSharedPreferences("strand", Context.MODE_PRIVATE)
                .getString("theme", "wallpaper");
    }

    public static boolean pureBlack(Context c) {
        return c.getSharedPreferences("strand", Context.MODE_PRIVATE)
                .getBoolean("pure_black", false);
    }

    public static void set(Context c, String theme, boolean pureBlack) {
        c.getSharedPreferences("strand", Context.MODE_PRIVATE).edit()
                .putString("theme", theme).putBoolean("pure_black", pureBlack).apply();
        sKey = null;
    }

    private static boolean dark(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** The color for a role (R.color.*) in the current theme. */
    public static int color(Context c, int res) {
        String theme = current(c);
        boolean dark = dark(c), black = pureBlack(c);
        String key = theme + dark + black;
        if (!key.equals(sKey)) {
            sPalette = build(c, theme, dark, black);
            sKey = key;
        }
        Integer v = sPalette == null ? null : sPalette.get(res);
        return v != null ? v : c.getColor(res);
    }

    /** Seed swatch for the picker. */
    public static int swatch(Context c, int index) {
        float h = HUES[index];
        if (h == -1) return c.getColor(R.color.accent);
        if (h == -2) return hsl(0, 0, 0.45f);
        return hsl(h, 0.55f, 0.48f);
    }

    private static int hsl(float h, float s, float l) {
        return Color.HSVToColor(hsv(h, s, l));
    }

    // HSL to HSV, so android.graphics.Color can do the rest
    private static float[] hsv(float h, float s, float l) {
        float v = l + s * Math.min(l, 1 - l);
        float sv = v == 0 ? 0 : 2 * (1 - l / v);
        return new float[] {((h % 360) + 360) % 360, sv, v};
    }

    private static Map<Integer, Integer> build(Context c, String theme, boolean dark,
            boolean black) {
        int idx = 0;
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(theme)) idx = i;
        float hue = HUES[idx];
        Map<Integer, Integer> m = new HashMap<>();
        if (hue == -1) {
            // Wallpaper colors come from resources; only pure black changes them
            if (dark && black) {
                m.put(R.color.bg, Color.BLACK);
                m.put(R.color.surface, hsl(0, 0, 0.07f));
            }
            return m.isEmpty() ? null : m;
        }
        float s = hue == -2 ? 0f : 1f; // mono: no chroma
        float h = hue == -2 ? 0 : hue, h3 = h + 60;
        if (!dark) {
            m.put(R.color.accent, hsl(h, 0.62f * s, 0.40f));
            m.put(R.color.on_accent, Color.WHITE);
            m.put(R.color.primary_container, hsl(h, 0.75f * s, 0.88f));
            m.put(R.color.on_primary_container, hsl(h, 0.70f * s, 0.12f));
            m.put(R.color.selected, hsl(h, 0.75f * s, 0.88f));
            m.put(R.color.secondary_container, hsl(h, 0.28f * s, 0.89f));
            m.put(R.color.on_secondary_container, hsl(h, 0.30f * s, 0.14f));
            m.put(R.color.tertiary, hsl(h3, 0.45f * s, 0.42f));
            m.put(R.color.tertiary_container, hsl(h3, 0.55f * s, 0.88f));
            m.put(R.color.on_tertiary_container, hsl(h3, 0.50f * s, 0.12f));
            m.put(R.color.warn, hsl(h3, 0.45f * s, 0.42f));
            m.put(R.color.bg, hsl(h, 0.10f * s, 0.98f));
            m.put(R.color.surface, hsl(h, 0.12f * s, 0.945f));
            m.put(R.color.surface_high, hsl(h, 0.12f * s, 0.915f));
            m.put(R.color.surface_highest, hsl(h, 0.12f * s, 0.885f));
            m.put(R.color.text, hsl(h, 0.10f * s, 0.10f));
            m.put(R.color.text_dim, hsl(h, 0.08f * s, 0.32f));
            m.put(R.color.outline, hsl(h, 0.06f * s, 0.62f));
            m.put(R.color.graph_grid, hsl(h, 0.08f * s, 0.86f));
        } else {
            m.put(R.color.accent, hsl(h, 0.65f * s, 0.78f));
            m.put(R.color.on_accent, hsl(h, 0.70f * s, 0.18f));
            m.put(R.color.primary_container, hsl(h, 0.45f * s, 0.27f));
            m.put(R.color.on_primary_container, hsl(h, 0.75f * s, 0.90f));
            m.put(R.color.selected, hsl(h, 0.45f * s, 0.27f));
            m.put(R.color.secondary_container, hsl(h, 0.20f * s, 0.25f));
            m.put(R.color.on_secondary_container, hsl(h, 0.30f * s, 0.88f));
            m.put(R.color.tertiary, hsl(h3, 0.50f * s, 0.78f));
            m.put(R.color.tertiary_container, hsl(h3, 0.35f * s, 0.27f));
            m.put(R.color.on_tertiary_container, hsl(h3, 0.60f * s, 0.90f));
            m.put(R.color.warn, hsl(h3, 0.50f * s, 0.78f));
            m.put(R.color.bg, black ? Color.BLACK : hsl(h, 0.10f * s, 0.065f));
            m.put(R.color.surface, black ? hsl(h, 0.08f * s, 0.075f) : hsl(h, 0.10f * s, 0.12f));
            m.put(R.color.surface_high, hsl(h, 0.10f * s, 0.17f));
            m.put(R.color.surface_highest, hsl(h, 0.10f * s, 0.22f));
            m.put(R.color.text, hsl(h, 0.12f * s, 0.92f));
            m.put(R.color.text_dim, hsl(h, 0.10f * s, 0.74f));
            m.put(R.color.outline, hsl(h, 0.06f * s, 0.45f));
            m.put(R.color.graph_grid, hsl(h, 0.08f * s, 0.28f));
        }
        return m;
    }
}
