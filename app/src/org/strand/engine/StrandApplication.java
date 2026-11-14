// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;

import org.strand.engine.resona.ResonaController;

/** The persistent process: keeps Resona on the output mix from boot. */
public class StrandApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ResonaController.get(this).start();
        Games.start(this);
        MaintenanceJobService.ensureScheduled(this);
        // A hot device throttles; strandd goes quiet and lighter while it is
        PowerManager pm = getSystemService(PowerManager.class);
        pm.addThermalStatusListener(getMainExecutor(),
                status -> StrandClient.async("thermal " + status, null));
        StrandClient.async("thermal " + pm.getCurrentThermalStatus(), null);
        // Plug state for strandd (battery saver comes back on when unplugged);
        // it can't read battery sysfs itself
        IntentFilter plug = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
        plug.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                reportPower(Intent.ACTION_POWER_DISCONNECTED.equals(i.getAction()));
            }
        }, plug);
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        reportPower(battery == null || battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0);
    }

    private static void reportPower(boolean onBattery) {
        StrandClient.async(onBattery ? "power battery" : "power plugged", null);
    }
}
