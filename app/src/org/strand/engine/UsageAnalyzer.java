// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Picks the apps worth optimizing from the last weeks of usage. */
final class UsageAnalyzer {
    private static final long DAY = 24L * 60 * 60 * 1000;
    /** Less foreground time than this in the window isn't "frequent". */
    private static final long MIN_FOREGROUND = 10L * 60 * 1000;

    private UsageAnalyzer() {}

    /** Launchable apps by foreground time over the last 14 days, most used first. */
    static List<String> topApps(Context c, int max) {
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        Map<String, UsageStats> stats = usm.queryAndAggregateUsageStats(now - 14 * DAY, now);
        PackageManager pm = c.getPackageManager();
        List<UsageStats> list = new ArrayList<>();
        for (UsageStats u : stats.values()) {
            if (u.getTotalTimeInForeground() < MIN_FOREGROUND) continue;
            if (u.getPackageName().equals(c.getPackageName())) continue;
            if (pm.getLaunchIntentForPackage(u.getPackageName()) == null) continue;
            list.add(u);
        }
        list.sort((a, b) -> Long.compare(b.getTotalTimeInForeground(),
                a.getTotalTimeInForeground()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.size() && i < max; i++) out.add(list.get(i).getPackageName());
        return out;
    }

    /** "dexopt FILTER pkg..." for strandd, or null with nothing to do. */
    static String dexoptCommand(String filter, List<String> pkgs) {
        if (pkgs.isEmpty()) return null;
        return "dexopt " + filter + " " + String.join(" ", pkgs);
    }
}
