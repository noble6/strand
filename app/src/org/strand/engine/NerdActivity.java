// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class NerdActivity extends Activity {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private GraphView mPressure, mAvail, mZram, mCpu, mGpu, mTemp;
    private TextView mRaw;
    private boolean mResumed;

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            sample();
            if (mResumed) mHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout col = Ui.page(this, getString(R.string.nerd_title), null);
        mPressure = graph(col, R.string.nerd_mem_pressure, 1, 0);
        mAvail = graph(col, R.string.nerd_mem_avail, 1, 0);
        mZram = graph(col, R.string.nerd_zram, 1, 0);
        mCpu = graph(col, R.string.nerd_cpu, 3, 0);
        mGpu = graph(col, R.string.nerd_gpu, 1, 100);
        mTemp = graph(col, R.string.nerd_temp, 1, 0);

        LinearLayout raw = Ui.card(this, getString(R.string.nerd_raw), null);
        mRaw = Ui.text(this, "", 12, R.color.text_dim);
        mRaw.setTypeface(Typeface.MONOSPACE);
        mRaw.setTextIsSelectable(true);
        mRaw.setPadding(0, Ui.dp(this, 8), 0, 0);
        raw.addView(mRaw);
        col.addView(raw);
    }

    private GraphView graph(LinearLayout col, int title, int series, float max) {
        LinearLayout card = Ui.card(this, getString(title), null);
        GraphView g = new GraphView(this, series, max);
        g.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(g);
        col.addView(card);
        return g;
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

    private static float f(Map<String, String> m, String k) {
        String v = m.get(k);
        if (v == null) return Float.NaN;
        try {
            return Float.parseFloat(v.trim().split("\\s+")[0]);
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    private void sample() {
        new Thread(() -> {
            StrandClient.Reply st = StrandClient.call("stats");
            StrandClient.Reply status = StrandClient.call("status");
            runOnUiThread(() -> {
                if (st == null) {
                    mRaw.setText(R.string.daemon_down);
                    return;
                }
                Map<String, String> m = st.map();
                mPressure.add(f(m, "psi_memory_some_avg10"));
                mAvail.add(f(m, "mem_MemAvailable_kb") / 1024f);
                float orig = f(m, "zram_orig"), compr = f(m, "zram_compr");
                mZram.add(compr > 0 ? orig / compr : 0);
                List<Float> cpus = new ArrayList<>();
                for (Map.Entry<String, String> e : m.entrySet()) {
                    if (e.getKey().startsWith("cpu_") && e.getKey().endsWith("_cur")) {
                        cpus.add(f(m, e.getKey()) / 1000f);
                    }
                }
                float[] c = new float[cpus.size()];
                for (int i = 0; i < c.length; i++) c[i] = cpus.get(i);
                mCpu.add(c);
                mGpu.add(f(m, "gpu_busy_pct"));
                mTemp.add(f(m, "battery_temp_dc") / 10f);

                StringBuilder sb = new StringBuilder();
                if (status != null) for (String l : status.lines) sb.append(l).append('\n');
                for (String l : st.lines) sb.append(l).append('\n');
                mRaw.setText(sb.toString().trim());
            });
        }).start();
    }
}
