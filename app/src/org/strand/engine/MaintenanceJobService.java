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

/** Weekly trim and app optimization, only while idle and charging. */
public class MaintenanceJobService extends JobService {
    private static final int JOB_ID = 0x57a4d;
    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;
    static final String PREF_AUTO = "weekly_maintenance";
    static final int TOP_APPS = 12;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("strand", MODE_PRIVATE);
    }

    static void ensureScheduled(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        boolean want = prefs(c).getBoolean(PREF_AUTO, true);
        boolean have = js.getPendingJob(JOB_ID) != null;
        if (want && !have) {
            js.schedule(new JobInfo.Builder(JOB_ID,
                    new ComponentName(c, MaintenanceJobService.class))
                    .setRequiresCharging(true)
                    .setRequiresDeviceIdle(true)
                    .setPeriodic(WEEK)
                    .setPersisted(true)
                    .build());
        } else if (!want && have) {
            js.cancel(JOB_ID);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            StrandClient.call("trim");
            List<String> top = UsageAnalyzer.topApps(this, TOP_APPS);
            String cmd = UsageAnalyzer.dexoptCommand("speed-profile", top);
            if (cmd != null) StrandClient.call(cmd);
            // strandd works through its queue on its own
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
