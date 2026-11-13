// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** strandd restores profiles itself; this only keeps the weekly job scheduled. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            MaintenanceJobService.ensureScheduled(context);
        }
    }
}
