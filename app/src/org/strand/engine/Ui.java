// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Material 3 Expressive building blocks on plain framework views, so every
 * screen looks like Android 16+ Settings: a large title that collapses into a
 * top bar, segmented row groups (one rounded card per row, big outer corners,
 * small inner ones), tonal icon badges, pill buttons.
 */
final class Ui {
    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static int color(Context c, int res) {
        return c.getColor(res);
    }

    static Typeface font(int weight) {
        return Typeface.create(Typeface.DEFAULT, weight, false);
    }

    static TextView text(Context c, String s, float sp, int colorRes) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(c.getColor(colorRes));
        return t;
    }

    static TextView text(Context c, String s, float sp, int colorRes, int weight) {
        TextView t = text(c, s, sp, colorRes);
        t.setTypeface(font(weight));
        return t;
    }

    static GradientDrawable rounded(Context c, int colorRes, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(c.getColor(colorRes));
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static Drawable ripple(Context c, Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(
                (c.getColor(R.color.text) & 0x00ffffff) | 0x22000000), content, null);
    }

    /**
     * Scrolling page with a large title (and optional subtitle) that collapses
     * into a small top bar as the page scrolls. Returns the column to fill.
     */
    static LinearLayout page(Activity a, String title, String subtitle) {
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(a.getColor(R.color.bg));
        root.setFitsSystemWindows(true);

        ScrollView scroll = new ScrollView(a);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int side = dp(a, 16);
        col.setPadding(side, dp(a, 64), side, dp(a, 40));
        scroll.addView(col);
        root.addView(scroll);

        TextView big = text(a, title, 40, R.color.text, 400);
        big.setPadding(dp(a, 8), dp(a, 8), 0, 0);
        col.addView(big);
        if (subtitle != null) {
            TextView s = text(a, subtitle, 16, R.color.text_dim);
            s.setPadding(dp(a, 8), dp(a, 6), 0, dp(a, 8));
            col.addView(s);
        }

        // Top bar: fades in with the small title once the big one scrolls away
        TextView bar = text(a, title, 20, R.color.text, 500);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(a, 24), 0, dp(a, 24), 0);
        bar.setBackgroundColor(a.getColor(R.color.surface));
        bar.setAlpha(0f);
        bar.setElevation(dp(a, 2));
        root.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 56)));
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            float f = Math.max(0f, Math.min(1f, (y - dp(a, 24)) / (float) dp(a, 40)));
            bar.setAlpha(f);
            big.setAlpha(1f - f);
        });

        a.setContentView(root);
        return col;
    }

    static TextView section(Context c, String s) {
        TextView t = text(c, s, 14, R.color.accent, 600);
        t.setPadding(dp(c, 16), dp(c, 28), 0, dp(c, 10));
        return t;
    }

    /** A standalone rounded card (title and optional summary). */
    static LinearLayout card(Context c, String title, String summary) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ripple(c, rounded(c, R.color.surface, 28)));
        int p = dp(c, 20);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 8);
        card.setLayoutParams(lp);
        card.addView(text(c, title, 18, R.color.text, 500));
        if (summary != null) {
            TextView s = text(c, summary, 14, R.color.text_dim);
            s.setPadding(0, dp(c, 4), 0, 0);
            s.setLineSpacing(0, 1.1f);
            card.addView(s);
        }
        return card;
    }

    /** Icon in a tonal circle (M3 list leading element). */
    static ImageView badge(Context c, int icon, int bgRes, int fgRes, float sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageResource(icon);
        v.setImageTintList(ColorStateList.valueOf(c.getColor(fgRes)));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(c.getColor(bgRes));
        v.setBackground(g);
        int pad = dp(c, sizeDp * 0.25f);
        v.setPadding(pad, pad, pad, pad);
        int s = dp(c, sizeDp);
        v.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return v;
    }

    /**
     * Segmented group: each row is its own card, 2dp apart; the first and last
     * get large outer corners, the rest small ones (Android 16 Settings).
     */
    static final class Group extends LinearLayout {
        Group(Context c) {
            super(c);
            setOrientation(VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(c, 8);
            setLayoutParams(lp);
        }

        @Override
        public void addView(View child, int index, ViewGroup.LayoutParams params) {
            super.addView(child, index, params);
            restyle();
        }

        void restyle() {
            Context c = getContext();
            float big = dp(c, 28), small = dp(c, 6);
            int n = getChildCount(), shown = 0;
            for (int i = 0; i < n; i++) if (getChildAt(i).getVisibility() != GONE) shown++;
            int k = 0;
            for (int i = 0; i < n; i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                float top = k == 0 ? big : small, bottom = k == shown - 1 ? big : small;
                GradientDrawable g = new GradientDrawable();
                g.setColor(c.getColor(R.color.surface));
                g.setCornerRadii(new float[] {top, top, top, top, bottom, bottom, bottom, bottom});
                v.setBackground(v.isClickable() ? ripple(c, g) : g);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
                lp.topMargin = k == 0 ? 0 : dp(c, 2);
                v.setLayoutParams(lp);
                k++;
            }
        }
    }

    /**
     * A list row: tonal icon badge, title, summary, and a trailing view
     * (switch, value) or a chevron when it opens something.
     */
    static LinearLayout row(Context c, int icon, String title, String summary, View trailing,
            View.OnClickListener click) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(c, 72));
        r.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (icon != 0) {
            ImageView b = badge(c, icon, R.color.secondary_container,
                    R.color.on_secondary_container, 40);
            ((LinearLayout.LayoutParams) b.getLayoutParams()).setMarginEnd(dp(c, 16));
            r.addView(b);
        }
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(c, title, 17, R.color.text, 500));
        if (summary != null) {
            TextView s = text(c, summary, 14, R.color.text_dim);
            s.setPadding(0, dp(c, 2), 0, 0);
            texts.addView(s);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (trailing == null && click != null) {
            ImageView chev = new ImageView(c);
            chev.setImageResource(R.drawable.ic_chevron);
            chev.setImageTintList(ColorStateList.valueOf(c.getColor(R.color.text_dim)));
            trailing = chev;
        }
        if (trailing != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(c, 12));
            r.addView(trailing, lp);
        }
        if (click != null) {
            r.setClickable(true);
            r.setOnClickListener(click);
        }
        return r;
    }

    /** Filled pill button. */
    static TextView button(Context c, String label, View.OnClickListener l) {
        return pill(c, label, R.color.accent, R.color.on_accent, l);
    }

    /** Tonal pill button (secondary container). */
    static TextView tonalButton(Context c, String label, View.OnClickListener l) {
        return pill(c, label, R.color.secondary_container, R.color.on_secondary_container, l);
    }

    private static TextView pill(Context c, String label, int bg, int fg, View.OnClickListener l) {
        TextView b = text(c, label, 15, fg, 600);
        b.setBackground(ripple(c, rounded(c, bg, 24)));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 24), dp(c, 12), dp(c, 24), dp(c, 12));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 14);
        b.setLayoutParams(lp);
        return b;
    }

    static String bytes(long b) {
        if (b < 0) return "?";
        double v = b;
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(java.util.Locale.getDefault(), i >= 3 ? "%.1f %s" : "%.0f %s", v, u[i]);
    }
}
