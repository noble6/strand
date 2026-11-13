// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.app.Application;

import org.strand.engine.resona.ResonaController;

/** The persistent process: keeps Resona on the output mix from boot. */
public class StrandApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ResonaController.get(this).start();
        Games.start(this);
        MaintenanceJobService.ensureScheduled(this);
    }
}
