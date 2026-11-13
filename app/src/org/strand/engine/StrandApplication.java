// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.app.Application;
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
    }
}
