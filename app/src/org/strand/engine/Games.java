// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

/** Tells strandd which installed apps are games (quiet system, auto gaming profile). */
final class Games {
    private Games() {}

    static void start(Context c) {
        sync(c);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        c.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                sync(ctx);
            }
        }, f, Context.RECEIVER_EXPORTED);
    }

    @SuppressWarnings("deprecation") // FLAG_IS_GAME: older games only set this
    static List<String> installed(Context c) {
        List<String> out = new ArrayList<>();
        for (ApplicationInfo ai : c.getPackageManager().getInstalledApplications(
                PackageManager.MATCH_ALL)) {
            if (ai.category == ApplicationInfo.CATEGORY_GAME
                    || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0) {
                out.add(ai.packageName);
            }
        }
        return out;
    }

    static void sync(Context c) {
        new Thread(() -> {
            List<String> games = installed(c);
            if (StrandClient.call("games clear") == null) return;
            for (int i = 0; i < games.size(); i += 40) {
                List<String> chunk = games.subList(i, Math.min(games.size(), i + 40));
                StrandClient.call("games add " + String.join(" ", chunk));
            }
        }).start();
    }
}
