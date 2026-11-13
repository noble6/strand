// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HealthActivity extends Activity {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TextView mStorage, mBattery, mBatteryTemp, mTopApps;
    private RingGauge mStorageRing, mBatteryRing;
    private TextView mOptStatus, mTrimStatus, mCacheStatus, mGcStatus;
    private List<String> mTop = new ArrayList<>();
    private boolean mResumed;

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            refresh();
            if (mResumed) mHandler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout col = Ui.page(this, getString(R.string.health_title),
                getString(R.string.health_summary));

        // Hero: storage and battery health at a glance
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setBackground(Ui.rounded(this, R.color.primary_container, 32));
        int hp = Ui.dp(this, 20);
        hero.setPadding(hp, hp, hp, hp);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Ui.dp(this, 12);
        hero.setLayoutParams(hlp);
        LinearLayout rings = new LinearLayout(this);
        rings.setOrientation(LinearLayout.HORIZONTAL);
        int track = (getColor(R.color.on_primary_container) & 0x00ffffff) | 0x26000000;
        int txt = getColor(R.color.on_primary_container);
        mStorageRing = new RingGauge(this, getString(R.string.health_storage),
                getColor(R.color.accent), track, txt);
        mBatteryRing = new RingGauge(this, getString(R.string.health_battery),
                getColor(R.color.tertiary), track, txt);
        rings.addView(mStorageRing, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        rings.addView(mBatteryRing, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        hero.addView(rings);
        mStorage = Ui.text(this, "", 14, R.color.on_primary_container);
        mStorage.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), 0, 0);
        hero.addView(mStorage);
        mBattery = Ui.text(this, "", 14, R.color.on_primary_container);
        mBattery.setPadding(Ui.dp(this, 4), Ui.dp(this, 2), 0, 0);
        hero.addView(mBattery);
        mBatteryTemp = Ui.text(this, "", 14, R.color.on_primary_container);
        mBatteryTemp.setPadding(Ui.dp(this, 4), Ui.dp(this, 2), 0, 0);
        mBatteryTemp.setAlpha(0.8f);
        hero.addView(mBatteryTemp);
        col.addView(hero);

        col.addView(Ui.section(this, getString(R.string.health_maintenance)));

        LinearLayout opt = action(R.drawable.ic_rocket, R.string.health_optimize,
                R.string.health_optimize_desc);
        mTopApps = Ui.text(this, "", 13, R.color.text_dim);
        mTopApps.setPadding(0, Ui.dp(this, 8), 0, 0);
        opt.addView(mTopApps);
        mOptStatus = status(opt);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(Ui.button(this, getString(R.string.health_run), v -> optimize(false)));
        TextView max = Ui.tonalButton(this, getString(R.string.health_optimize_max),
                v -> optimize(true));
        ((LinearLayout.LayoutParams) max.getLayoutParams()).setMarginStart(Ui.dp(this, 8));
        buttons.addView(max);
        opt.addView(buttons);
        col.addView(opt);

        LinearLayout gc = action(R.drawable.ic_storage, R.string.health_gc,
                R.string.health_gc_desc);
        mGcStatus = status(gc);
        gc.addView(Ui.button(this, getString(R.string.health_run), v -> send("gc", mGcStatus)));
        col.addView(gc);

        LinearLayout trim = action(R.drawable.ic_trim, R.string.health_trim,
                R.string.health_trim_desc);
        mTrimStatus = status(trim);
        trim.addView(Ui.tonalButton(this, getString(R.string.health_run),
                v -> send("trim", mTrimStatus)));
        col.addView(trim);

        LinearLayout cache = action(R.drawable.ic_clean, R.string.health_cache,
                R.string.health_cache_desc);
        mCacheStatus = status(cache);
        cache.addView(Ui.tonalButton(this, getString(R.string.health_run), v ->
                new AlertDialog.Builder(this)
                        .setMessage(R.string.health_cache_confirm)
                        .setPositiveButton(R.string.ok, (d, w) -> send("clearcache", mCacheStatus))
                        .setNegativeButton(R.string.cancel, null)
                        .show()));
        col.addView(cache);

        col.addView(Ui.section(this, getString(R.string.health_automatic)));
        Ui.Group autoGroup = new Ui.Group(this);
        Switch sw = new Switch(this);
        sw.setChecked(MaintenanceJobService.prefs(this)
                .getBoolean(MaintenanceJobService.PREF_AUTO, true));
        sw.setOnCheckedChangeListener((b, on) -> {
            MaintenanceJobService.prefs(this).edit()
                    .putBoolean(MaintenanceJobService.PREF_AUTO, on).apply();
            MaintenanceJobService.ensureScheduled(this);
        });
        autoGroup.addView(Ui.row(this, R.drawable.ic_sparkle, getString(R.string.health_auto),
                getString(R.string.health_auto_desc), sw, null));
        col.addView(autoGroup);

        new Thread(() -> {
            List<String> top = UsageAnalyzer.topApps(this, MaintenanceJobService.TOP_APPS);
            runOnUiThread(() -> {
                mTop = top;
                mTopApps.setText(top.isEmpty() ? getString(R.string.health_no_apps)
                        : getString(R.string.health_top_apps, labels(top)));
            });
        }).start();
    }

    /** Maintenance card: icon badge and title on one line, the explanation below. */
    private LinearLayout action(int icon, int title, int desc) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.rounded(this, R.color.surface, 28));
        int p = Ui.dp(this, 20);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 8);
        c.setLayoutParams(lp);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView b = Ui.badge(this, icon, R.color.secondary_container,
                R.color.on_secondary_container, 40);
        ((LinearLayout.LayoutParams) b.getLayoutParams()).setMarginEnd(Ui.dp(this, 14));
        head.addView(b);
        head.addView(Ui.text(this, getString(title), 18, R.color.text, 500));
        c.addView(head);
        TextView d = Ui.text(this, getString(desc), 14, R.color.text_dim);
        d.setPadding(0, Ui.dp(this, 12), 0, 0);
        d.setLineSpacing(0, 1.1f);
        c.addView(d);
        return c;
    }

    private TextView status(LinearLayout card) {
        TextView t = Ui.text(this, "", 13, R.color.accent);
        t.setPadding(0, Ui.dp(this, 6), 0, 0);
        t.setVisibility(View.GONE);
        card.addView(t);
        return t;
    }

    private String labels(List<String> pkgs) {
        List<String> out = new ArrayList<>();
        for (String p : pkgs) {
            try {
                out.add(getPackageManager().getApplicationLabel(
                        getPackageManager().getApplicationInfo(p, 0)).toString());
            } catch (Exception e) {
                out.add(p);
            }
            if (out.size() == 6) break;
        }
        String s = String.join(", ", out);
        return pkgs.size() > 6 ? s + ", +" + (pkgs.size() - 6) : s;
    }

    private void optimize(boolean max) {
        if (mTop.isEmpty()) return;
        if (max) {
            // Full AOT for the three most used, profile-guided for the rest
            List<String> head = mTop.subList(0, Math.min(3, mTop.size()));
            List<String> tail = mTop.subList(head.size(), mTop.size());
            send(UsageAnalyzer.dexoptCommand("speed", new ArrayList<>(head)), mOptStatus);
            String rest = UsageAnalyzer.dexoptCommand("speed-profile", new ArrayList<>(tail));
            if (rest != null) StrandClient.async(rest, null);
        } else {
            send(UsageAnalyzer.dexoptCommand("speed-profile", mTop), mOptStatus);
        }
    }

    private void send(String cmd, TextView status) {
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.health_queued);
        StrandClient.async(cmd, r -> {
            if (r == null) status.setText(R.string.daemon_down);
            else if (!r.ok) status.setText(r.message);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        mHandler.post(mPoll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mResumed = false;
        mHandler.removeCallbacks(mPoll);
    }

    private void refresh() {
        new Thread(() -> {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            long free = fs.getAvailableBytes(), total = fs.getTotalBytes();
            StrandClient.Reply st = StrandClient.call("stats");
            StrandClient.Reply status = StrandClient.call("status");
            runOnUiThread(() -> {
                mStorage.setText(getString(R.string.health_storage_value, Ui.bytes(free),
                        Ui.bytes(total)));
                if (total > 0) {
                    float used = 1f - free / (float) total;
                    mStorageRing.set(used, Math.round(used * 100) + "%");
                }
                if (st != null) showBattery(st.map());
                if (status != null) showJobs(status.map());
            });
        }).start();
    }

    private void showBattery(Map<String, String> m) {
        long full = MainActivity.parse(m.get("battery_full_uah"));
        long design = MainActivity.parse(m.get("battery_design_uah"));
        String cycles = m.getOrDefault("battery_cycles", "?");
        if (design > 0 && full > 0) {
            int health = (int) Math.round(100.0 * full / design);
            mBattery.setText(getString(R.string.health_battery_value, health, cycles));
            mBatteryRing.set(Math.min(1f, health / 100f), health + "%");
        } else {
            mBattery.setText(m.getOrDefault("battery_pct", "?") + "%");
        }
        String temp = m.get("battery_temp_dc");
        if (temp != null) {
            mBatteryTemp.setText(getString(R.string.health_battery_temp,
                    MainActivity.parse(temp) / 10.0));
        }
    }

    private void showJobs(Map<String, String> s) {
        String busy = s.getOrDefault("maint", "idle");
        line(mOptStatus, busy.startsWith("dexopt") ? busy : null, s.get("last_dexopt"));
        line(mTrimStatus, busy.equals("trim") ? busy : null, s.get("last_trim"));
        line(mCacheStatus, busy.equals("clearcache") ? busy : null, s.get("last_clearcache"));
        line(mGcStatus, busy.startsWith("gc") || busy.startsWith("waiting") ? busy : null,
                s.get("last_gc"));
    }

    private void line(TextView t, String busy, String last) {
        if (busy != null) {
            t.setVisibility(View.VISIBLE);
            t.setText(getString(R.string.health_busy, busy));
        } else if (last != null && !last.equals("never")) {
            t.setVisibility(View.VISIBLE);
            t.setText(getString(R.string.health_last, pretty(last)));
        }
    }

    /** "trimmed 123456" -> "trimmed 120 KB", "rc 0 free 1 -> 2" -> "freed ..." */
    private static String pretty(String last) {
        String[] w = last.split(" ");
        if (w.length >= 2 && w[0].equals("trimmed")) {
            return "trimmed " + Ui.bytes(MainActivity.parse(w[1]));
        }
        if (w.length >= 2 && w[0].equals("dirty")) {
            return "storage GC:" + last.substring(5);
        }
        if (w.length >= 6 && w[0].equals("rc") && w[2].equals("free")) {
            long freed = MainActivity.parse(w[5]) - MainActivity.parse(w[3]);
            return "freed " + Ui.bytes(Math.max(0, freed));
        }
        return last.toLowerCase(Locale.ROOT);
    }
}
