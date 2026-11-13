// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Small view helpers so every screen looks the same without a support library. */
final class Ui {
    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static int color(Context c, int res) {
        return c.getColor(res);
    }

    /** Scrolling page with a large title; returns the column to fill. */
    static LinearLayout page(Activity a, String title, String subtitle) {
        ScrollView scroll = new ScrollView(a);
        scroll.setFitsSystemWindows(true);
        scroll.setClipToPadding(false);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 20);
        col.setPadding(p, dp(a, 36), p, dp(a, 32));
        scroll.addView(col);
        a.setContentView(scroll);

        TextView t = text(a, title, 40, R.color.text);
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        col.addView(t);
        if (subtitle != null) {
            TextView s = text(a, subtitle, 15, R.color.text_dim);
            s.setPadding(0, dp(a, 4), 0, dp(a, 12));
            col.addView(s);
        }
        return col;
    }

    static TextView text(Context c, String s, float sp, int colorRes) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(c.getColor(colorRes));
        return t;
    }

    static TextView section(Context c, String s) {
        TextView t = text(c, s, 14, R.color.accent);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(c, 4), dp(c, 24), 0, dp(c, 8));
        return t;
    }

    /** Rounded card with a title and a summary line. */
    static LinearLayout card(Context c, String title, String summary) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card);
        int p = dp(c, 18);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 8);
        card.setLayoutParams(lp);
        TextView t = text(c, title, 18, R.color.text);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(t);
        if (summary != null) {
            TextView s = text(c, summary, 14, R.color.text_dim);
            s.setPadding(0, dp(c, 4), 0, 0);
            card.addView(s);
        }
        return card;
    }

    /** Filled pill button. */
    static TextView button(Context c, String label, View.OnClickListener l) {
        TextView b = text(c, label, 15, R.color.on_accent);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackgroundResource(R.drawable.pill);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 10));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 12);
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
