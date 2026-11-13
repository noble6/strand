// SPDX-License-Identifier: GPL-3.0-or-later
package org.strand.engine.resona;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick settings: Resona on/off, showing the profile in force. */
public class ResonaTileService extends TileService {
    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        ResonaController c = ResonaController.get(this);
        c.setEnabled(!c.isEnabled());
        refresh();
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        ResonaController c = ResonaController.get(this);
        if (!c.available()) {
            t.setState(Tile.STATE_UNAVAILABLE);
        } else {
            t.setState(c.isEnabled() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            t.setSubtitle(c.isEnabled() ? c.effectiveProfile() : null);
        }
        t.updateTile();
    }
}
