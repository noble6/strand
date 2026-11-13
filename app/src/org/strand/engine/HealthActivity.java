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
    private TextView mOptStatus, mTrimStatus, mCacheStatus;
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
        LinearLayout col = Ui.page(this, getString(R.string.health_title), null);

        LinearLayout storage = Ui.card(this, getString(R.string.health_storage), "");
        mStorage = (TextView) storage.getChildAt(1);
        col.addView(storage);

        LinearLayout battery = Ui.card(this, getString(R.string.health_battery), "");
        mBattery = (TextView) battery.getChildAt(1);
        mBatteryTemp = Ui.text(this, "", 14, R.color.text_dim);
        battery.addView(mBatteryTemp);
        col.addView(battery);

        col.addView(Ui.section(this, getString(R.string.section_more)));

        LinearLayout opt = Ui.card(this, getString(R.string.health_optimize),
                getString(R.string.health_optimize_desc));
        mTopApps = Ui.text(this, "", 13, R.color.text_dim);
        mTopApps.setPadding(0, Ui.dp(this, 6), 0, 0);
        opt.addView(mTopApps);
        mOptStatus = status(opt);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(Ui.button(this, getString(R.string.health_run), v -> optimize(false)));
        TextView max = Ui.button(this, getString(R.string.health_optimize_max),
                v -> optimize(true));
        ((LinearLayout.LayoutParams) max.getLayoutParams()).setMarginStart(Ui.dp(this, 8));
        buttons.addView(max);
        opt.addView(buttons);
        col.addView(opt);

        LinearLayout trim = Ui.card(this, getString(R.string.health_trim),
                getString(R.string.health_trim_desc));
        mTrimStatus = status(trim);
        trim.addView(Ui.button(this, getString(R.string.health_run),
                v -> send("trim", mTrimStatus)));
        col.addView(trim);

        LinearLayout cache = Ui.card(this, getString(R.string.health_cache),
                getString(R.string.health_cache_desc));
        mCacheStatus = status(cache);
        cache.addView(Ui.button(this, getString(R.string.health_run), v ->
                new AlertDialog.Builder(this)
                        .setMessage(R.string.health_cache_confirm)
                        .setPositiveButton(R.string.ok, (d, w) -> send("clearcache", mCacheStatus))
                        .setNegativeButton(R.string.cancel, null)
                        .show()));
        col.addView(cache);

        LinearLayout auto = new LinearLayout(this);
        auto.setOrientation(LinearLayout.HORIZONTAL);
        auto.setGravity(Gravity.CENTER_VERTICAL);
        auto.setBackgroundResource(R.drawable.card);
        int p = Ui.dp(this, 18);
        auto.setPadding(p, p, p, p);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, getString(R.string.health_auto), 18, R.color.text));
        texts.addView(Ui.text(this, getString(R.string.health_auto_desc), 14, R.color.text_dim));
        auto.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        sw.setChecked(MaintenanceJobService.prefs(this)
                .getBoolean(MaintenanceJobService.PREF_AUTO, true));
        sw.setOnCheckedChangeListener((b, on) -> {
            MaintenanceJobService.prefs(this).edit()
                    .putBoolean(MaintenanceJobService.PREF_AUTO, on).apply();
            MaintenanceJobService.ensureScheduled(this);
        });
        auto.addView(sw);
        col.addView(auto);

        new Thread(() -> {
            List<String> top = UsageAnalyzer.topApps(this, MaintenanceJobService.TOP_APPS);
            runOnUiThread(() -> {
                mTop = top;
                mTopApps.setText(top.isEmpty() ? getString(R.string.health_no_apps)
                        : getString(R.string.health_top_apps, labels(top)));
            });
        }).start();
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
            mBattery.setText(getString(R.string.health_battery_value,
                    (int) Math.round(100.0 * full / design), cycles));
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
        if (w.length >= 6 && w[0].equals("rc") && w[2].equals("free")) {
            long freed = MainActivity.parse(w[5]) - MainActivity.parse(w[3]);
            return "freed " + Ui.bytes(Math.max(0, freed));
        }
        return last.toLowerCase(Locale.ROOT);
    }
}
