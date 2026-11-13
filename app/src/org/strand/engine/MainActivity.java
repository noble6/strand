// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private LinearLayout mColumn;
    private TextView mSubtitle;
    private TextView mCpu, mGpu, mMem;
    private final List<LinearLayout> mCards = new ArrayList<>();
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
        mCards.clear();
        if (list.isEmpty() || status == null) {
            mSubtitle.setText(R.string.daemon_down);
            return;
        }
        mProfiles = list;

        mColumn.addView(liveStrip());

        mColumn.addView(Ui.section(this, getString(R.string.section_profile)));
        for (Profiles.Profile p : list) {
            LinearLayout card = Ui.card(this, p.label, Profiles.description(this, p.name));
            card.setTag(p.name);
            card.setOnClickListener(v -> select(p.name));
            mCards.add(card);
            mColumn.addView(card);
        }

        mColumn.addView(Ui.section(this, getString(R.string.section_more)));
        addLink(R.string.apps_title, R.string.apps_summary, AppProfilesActivity.class);
        addLink(R.string.health_title, R.string.health_summary, HealthActivity.class);
        addLink(R.string.nerd_title, R.string.nerd_summary, NerdActivity.class);

        applyStatus(status);
        mHandler.removeCallbacks(mPoll);
        mHandler.post(mPoll);
    }

    private void addLink(int title, int summary, Class<?> cls) {
        LinearLayout card = Ui.card(this, getString(title), getString(summary));
        card.setOnClickListener(v -> startActivity(new Intent(this, cls)));
        mColumn.addView(card);
    }

    private LinearLayout liveStrip() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(this, 8), 0, 0);
        mCpu = tile(row, "CPU");
        mGpu = tile(row, "GPU");
        mMem = tile(row, "RAM");
        return row;
    }

    private TextView tile(LinearLayout row, String label) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackgroundResource(R.drawable.card);
        int p = Ui.dp(this, 14);
        t.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMarginEnd(row.getChildCount() < 2 ? Ui.dp(this, 8) : 0);
        t.setLayoutParams(lp);
        TextView l = Ui.text(this, label, 12, R.color.text_dim);
        TextView v = Ui.text(this, "-", 20, R.color.text);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setGravity(Gravity.START);
        t.addView(l);
        t.addView(v);
        row.addView(t);
        return v;
    }

    private void select(String name) {
        mUserProfile = name;
        highlight(name);
        StrandClient.async("profile " + name, r -> {
            ProfileTileService.requestUpdate(this);
            refreshLive();
        });
    }

    private void highlight(String name) {
        for (LinearLayout c : mCards) {
            c.setBackgroundResource(name.equals(c.getTag())
                    ? R.drawable.card_selected : R.drawable.card);
        }
    }

    private void applyStatus(StrandClient.Reply status) {
        if (status == null) return;
        Map<String, String> s = status.map();
        mUserProfile = s.getOrDefault("profile", "");
        String eff = s.getOrDefault("effective", mUserProfile);
        String top = s.getOrDefault("top_app", "-");
        highlight(mUserProfile);
        if (!eff.equals(mUserProfile) && !"-".equals(top)) {
            mSubtitle.setText(getString(R.string.effective_app,
                    Profiles.label(mProfiles, eff), top));
        } else {
            mSubtitle.setText(Profiles.label(mProfiles, mUserProfile));
        }
    }

    private void refreshLive() {
        new Thread(() -> {
            StrandClient.Reply st = StrandClient.call("stats");
            StrandClient.Reply status = StrandClient.call("status");
            runOnUiThread(() -> {
                applyStatus(status);
                if (st == null || mCpu == null) return;
                Map<String, String> m = st.map();
                long maxCpu = 0;
                for (Map.Entry<String, String> e : m.entrySet()) {
                    if (e.getKey().startsWith("cpu_") && e.getKey().endsWith("_cur")) {
                        maxCpu = Math.max(maxCpu, parse(e.getValue()));
                    }
                }
                mCpu.setText(String.format(Locale.getDefault(), "%.2f GHz", maxCpu / 1e6));
                String busy = m.get("gpu_busy_pct");
                String mhz = m.get("gpu_freq_mhz");
                mGpu.setText(mhz != null ? mhz + " MHz" : (busy != null ? busy : "-"));
                long avail = parse(m.get("mem_MemAvailable_kb"));
                mMem.setText(String.format(Locale.getDefault(), "%.1f GB", avail / 1048576.0));
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
