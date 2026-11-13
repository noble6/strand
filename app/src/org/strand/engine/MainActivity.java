// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    /** One profile tile: shape icon, name, short description. */
    private final class Tile {
        final String name;
        final LinearLayout view;
        final ShapeIcon icon;
        final TextView title, desc;

        Tile(Profiles.Profile p) {
            name = p.name;
            view = new LinearLayout(MainActivity.this);
            view.setOrientation(LinearLayout.VERTICAL);
            int pad = Ui.dp(MainActivity.this, 18);
            view.setPadding(pad, pad, pad, pad);
            int[] look = look(p.name);
            icon = new ShapeIcon(MainActivity.this, look[0], look[1], look[1] == 0 ? 0f : 0.09f);
            int s = Ui.dp(MainActivity.this, 56);
            view.addView(icon, new LinearLayout.LayoutParams(s, s));
            title = Ui.text(MainActivity.this, p.label, 18, R.color.text, 500);
            title.setPadding(0, Ui.dp(MainActivity.this, 14), 0, 0);
            view.addView(title);
            desc = Ui.text(MainActivity.this, Profiles.description(MainActivity.this, p.name), 13,
                    R.color.text_dim);
            desc.setPadding(0, Ui.dp(MainActivity.this, 4), 0, 0);
            desc.setMaxLines(3);
            view.addView(desc);
            view.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                select(name);
            });
            setSelected(false, false);
        }

        void setSelected(boolean on, boolean animate) {
            view.setBackground(Ui.ripple(MainActivity.this, Ui.rounded(MainActivity.this,
                    on ? R.color.primary_container : R.color.surface, 28)));
            icon.setColors(getColor(on ? R.color.accent : R.color.secondary_container),
                    getColor(on ? R.color.on_accent : R.color.on_secondary_container));
            title.setTextColor(getColor(on ? R.color.on_primary_container : R.color.text));
            desc.setTextColor(getColor(on ? R.color.on_primary_container : R.color.text_dim));
            desc.setAlpha(on ? 0.8f : 1f);
            if (on && animate) icon.pop();
        }
    }

    /** Icon and shape (lobes) per profile. */
    private static int[] look(String name) {
        switch (name) {
            case "balanced": return new int[] {R.drawable.ic_balance, 0};
            case "performance": return new int[] {R.drawable.ic_bolt, 12};
            case "battery": return new int[] {R.drawable.ic_leaf, 6};
            case "gaming": return new int[] {R.drawable.ic_game, 4};
            default: return new int[] {R.drawable.ic_sparkle, 8};
        }
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private LinearLayout mColumn;
    private TextView mSubtitle, mHeroProfile, mHeroDetail;
    private RingGauge mCpu, mGpu, mMem;
    private final List<Tile> mTiles = new ArrayList<>();
    private List<Profiles.Profile> mProfiles = new ArrayList<>();
    private String mUserProfile = "";
    private boolean mResumed;

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            refreshLive();
            if (mResumed) mHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mColumn = Ui.page(this, getString(R.string.app_name), getString(R.string.tagline));
        mSubtitle = (TextView) mColumn.getChildAt(1);
        MaintenanceJobService.ensureScheduled(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        new Thread(() -> {
            List<Profiles.Profile> list = Profiles.load();
            StrandClient.Reply status = StrandClient.call("status");
            runOnUiThread(() -> build(list, status));
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mResumed = false;
        mHandler.removeCallbacks(mPoll);
    }

    private void build(List<Profiles.Profile> list, StrandClient.Reply status) {
        while (mColumn.getChildCount() > 2) mColumn.removeViewAt(2);
        mTiles.clear();
        if (list.isEmpty() || status == null) {
            mSubtitle.setText(R.string.daemon_down);
            return;
        }
        mProfiles = list;
        Map<String, String> st = status.map();

        mColumn.addView(hero());

        if ("1".equals(st.get("low_storage"))) {
            Ui.Group warn = new Ui.Group(this);
            View row = Ui.row(this, R.drawable.ic_storage, getString(R.string.low_storage_title),
                    getString(R.string.low_storage_desc, st.get("data_free_pct")), null,
                    v -> startActivity(new Intent(this, HealthActivity.class)));
            warn.addView(row);
            ((GradientDrawable) ((android.graphics.drawable.RippleDrawable) row.getBackground())
                    .getDrawable(0)).setColor(getColor(R.color.tertiary_container));
            ((LinearLayout.LayoutParams) warn.getLayoutParams()).topMargin = Ui.dp(this, 8);
            mColumn.addView(warn);
        }

        mColumn.addView(Ui.section(this, getString(R.string.section_profile)));
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % 2 == 0) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                llp.bottomMargin = Ui.dp(this, 8);
                grid.addView(line, llp);
            }
            Tile t = new Tile(list.get(i));
            mTiles.add(t);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            if (i % 2 == 0) tlp.setMarginEnd(Ui.dp(this, 8));
            line.addView(t.view, tlp);
        }
        if (list.size() % 2 == 1) line.addView(new View(this),
                new LinearLayout.LayoutParams(0, 1, 1));
        mColumn.addView(grid);

        Ui.Group game = new Ui.Group(this);
        Switch gs = new Switch(this);
        gs.setChecked(!"0".equals(st.get("autogame")));
        gs.setOnCheckedChangeListener((b, on) ->
                StrandClient.async("autogame " + (on ? "on" : "off"), null));
        game.addView(Ui.row(this, R.drawable.ic_sparkle, getString(R.string.autogame_title),
                getString(R.string.autogame_short), gs, null));
        ((LinearLayout.LayoutParams) game.getLayoutParams()).topMargin = Ui.dp(this, 2);
        mColumn.addView(game);

        mColumn.addView(Ui.section(this, getString(R.string.section_more)));
        Ui.Group more = new Ui.Group(this);
        more.addView(link(R.drawable.ic_sound, R.string.sound_title, R.string.sound_summary,
                org.strand.engine.resona.ResonaActivity.class));
        more.addView(link(R.drawable.ic_apps, R.string.apps_title, R.string.apps_summary,
                AppProfilesActivity.class));
        more.addView(link(R.drawable.ic_health, R.string.health_title, R.string.health_summary,
                HealthActivity.class));
        more.addView(link(R.drawable.ic_chart, R.string.nerd_title, R.string.nerd_summary,
                NerdActivity.class));
        more.addView(link(R.drawable.ic_update, R.string.update_title, R.string.update_summary,
                UpdateActivity.class));
        mColumn.addView(more);

        applyStatus(status, false);
        mHandler.removeCallbacks(mPoll);
        mHandler.post(mPoll);
    }

    private View link(int icon, int title, int summary, Class<?> cls) {
        return Ui.row(this, icon, getString(title), getString(summary), null,
                v -> startActivity(new Intent(this, cls)));
    }

    /** The big card: profile in force and three live rings. */
    private LinearLayout hero() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setBackground(Ui.rounded(this, R.color.primary_container, 32));
        int p = Ui.dp(this, 24);
        h.setPadding(p, p, p, Ui.dp(this, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        h.setLayoutParams(lp);

        TextView label = Ui.text(this, getString(R.string.hero_label), 13,
                R.color.on_primary_container, 500);
        label.setAlpha(0.75f);
        h.addView(label);
        mHeroProfile = Ui.text(this, "", 34, R.color.on_primary_container, 500);
        h.addView(mHeroProfile);
        mHeroDetail = Ui.text(this, "", 14, R.color.on_primary_container);
        mHeroDetail.setAlpha(0.8f);
        mHeroDetail.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 16));
        h.addView(mHeroDetail);

        LinearLayout rings = new LinearLayout(this);
        rings.setOrientation(LinearLayout.HORIZONTAL);
        int arc = getColor(R.color.accent);
        int track = (getColor(R.color.on_primary_container) & 0x00ffffff) | 0x26000000;
        int txt = getColor(R.color.on_primary_container);
        mCpu = new RingGauge(this, "CPU", arc, track, txt);
        mGpu = new RingGauge(this, "GPU", getColor(R.color.tertiary), track, txt);
        mMem = new RingGauge(this, "RAM", arc, track, txt);
        for (RingGauge g : new RingGauge[] {mCpu, mGpu, mMem}) {
            rings.addView(g, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        h.addView(rings);
        return h;
    }

    private void select(String name) {
        mUserProfile = name;
        highlight(name, true);
        StrandClient.async("profile " + name, r -> {
            ProfileTileService.requestUpdate(this);
            refreshLive();
        });
    }

    private void highlight(String name, boolean animate) {
        for (Tile t : mTiles) t.setSelected(name.equals(t.name), animate);
    }

    private void applyStatus(StrandClient.Reply status, boolean animate) {
        if (status == null || mHeroProfile == null) return;
        Map<String, String> s = status.map();
        String user = s.getOrDefault("profile", "");
        String eff = s.getOrDefault("effective", user);
        if (eff == null || eff.isEmpty()) eff = user;
        String top = s.getOrDefault("top_app", "-");
        if (!user.equals(mUserProfile)) highlight(user, animate);
        mUserProfile = user;
        mHeroProfile.setText(Profiles.label(mProfiles, eff));
        if (!eff.equals(user) && !"-".equals(top)) {
            mHeroDetail.setText(getString(R.string.hero_for_app, appLabel(top)));
        } else if ("1".equals(s.get("quiet"))) {
            mHeroDetail.setText(R.string.hero_quiet);
        } else {
            mHeroDetail.setText(Profiles.description(this, eff));
        }
        mSubtitle.setText(R.string.tagline);
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private void refreshLive() {
        new Thread(() -> {
            StrandClient.Reply st = StrandClient.call("stats");
            StrandClient.Reply status = StrandClient.call("status");
            runOnUiThread(() -> {
                applyStatus(status, true);
                if (st == null || mCpu == null) return;
                Map<String, String> m = st.map();
                float cpu = 0;
                for (Map.Entry<String, String> e : m.entrySet()) {
                    String k = e.getKey();
                    if (k.startsWith("cpu_") && k.endsWith("_cur")) {
                        long cur = parse(e.getValue());
                        long max = parse(m.get(k.substring(0, k.length() - 4) + "_max"));
                        if (max > 0) cpu = Math.max(cpu, cur / (float) max);
                    }
                }
                mCpu.set(cpu, Math.round(cpu * 100) + "%");
                String busy = m.get("gpu_busy_pct");
                if (busy != null) {
                    long g = parse(busy);
                    mGpu.set(g / 100f, g + "%");
                } else {
                    mGpu.set(0, "-");
                }
                long total = parse(m.get("mem_MemTotal_kb")), avail = parse(m.get("mem_MemAvailable_kb"));
                if (total > 0) {
                    float used = 1f - avail / (float) total;
                    mMem.set(used, String.format(Locale.getDefault(), "%.1fG",
                            (total - avail) / 1048576.0));
                }
            });
        }).start();
    }

    static long parse(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s.trim().split("\\s+")[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
