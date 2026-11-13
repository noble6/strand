// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

/**
 * Idle maintenance, so the device stays as fast after months as on day one:
 *  - daily, while idle: storage GC (merges fragmented flash segments)
 *  - weekly, while idle and charging: fstrim, GC, and recompiling the most
 *    used apps with their latest profiles
 * The job stays running while strandd works and tells it to stop the moment
 * Android ends the idle window (screen on, unplugged), so maintenance never
 * competes with the user.
 */
public class MaintenanceJobService extends JobService {
    private static final int DAILY_ID = 0x57a4c;
    private static final int WEEKLY_ID = 0x57a4d;
    private static final long DAY = 24L * 60 * 60 * 1000;
    static final String PREF_AUTO = "weekly_maintenance";
    static final int TOP_APPS = 12;

    private volatile boolean mStopped;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("strand", MODE_PRIVATE);
    }

    static void ensureScheduled(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        boolean want = prefs(c).getBoolean(PREF_AUTO, true);
        ComponentName me = new ComponentName(c, MaintenanceJobService.class);
        if (!want) {
            js.cancel(DAILY_ID);
            js.cancel(WEEKLY_ID);
            return;
        }
        if (js.getPendingJob(DAILY_ID) == null) {
            js.schedule(new JobInfo.Builder(DAILY_ID, me)
                    .setRequiresDeviceIdle(true)
                    .setRequiresBatteryNotLow(true)
                    .setPeriodic(DAY)
                    .setPersisted(true)
                    .build());
        }
        if (js.getPendingJob(WEEKLY_ID) == null) {
            js.schedule(new JobInfo.Builder(WEEKLY_ID, me)
                    .setRequiresDeviceIdle(true)
                    .setRequiresCharging(true)
                    .setPeriodic(7 * DAY)
                    .setPersisted(true)
                    .build());
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        mStopped = false;
        boolean weekly = params.getJobId() == WEEKLY_ID;
        new Thread(() -> {
            if (weekly) StrandClient.call("trim");
            StrandClient.call("gc");
            if (weekly) {
                List<String> top = UsageAnalyzer.topApps(this, TOP_APPS);
                String cmd = UsageAnalyzer.dexoptCommand("speed-profile", top);
                if (cmd != null) StrandClient.call(cmd);
            }
            // Stay in the idle window until strandd is done (or we are stopped).
            for (int i = 0; i < 360 && !mStopped; i++) {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    break;
                }
                StrandClient.Reply s = StrandClient.call("status");
                if (s == null || "idle".equals(s.map().get("maint"))) break;
            }
            if (!mStopped) jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        mStopped = true;
        new Thread(() -> StrandClient.call("stop")).start();
        return false; // periodic: it comes back next window
    }
}
