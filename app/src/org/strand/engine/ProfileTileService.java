// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.content.ComponentName;
import android.content.Context;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import java.util.List;

/** Quick settings tile: shows the profile, a tap moves to the next one. */
public class ProfileTileService extends TileService {

    static void requestUpdate(Context c) {
        TileService.requestListeningState(c, new ComponentName(c, ProfileTileService.class));
    }

    @Override
    public void onStartListening() {
        refresh(null);
    }

    @Override
    public void onClick() {
        new Thread(() -> {
            List<Profiles.Profile> list = Profiles.load();
            StrandClient.Reply cur = StrandClient.call("profile");
            if (list.isEmpty() || cur == null) {
                refreshOnMain(list, null);
                return;
            }
            String now = cur.message.trim();
            int i = 0;
            for (int k = 0; k < list.size(); k++) if (list.get(k).name.equals(now)) i = k;
            String next = list.get((i + 1) % list.size()).name;
            StrandClient.call("profile " + next);
            refreshOnMain(list, next);
        }).start();
    }

    private void refresh(String known) {
        new Thread(() -> {
            List<Profiles.Profile> list = Profiles.load();
            String name = known;
            if (name == null) {
                StrandClient.Reply cur = StrandClient.call("profile");
                if (cur != null && cur.ok) name = cur.message.trim();
            }
            refreshOnMain(list, name);
        }).start();
    }

    private void refreshOnMain(List<Profiles.Profile> list, String name) {
        getMainExecutor().execute(() -> {
            Tile t = getQsTile();
            if (t == null) return;
            if (name == null) {
                t.setState(Tile.STATE_UNAVAILABLE);
                t.setSubtitle(null);
            } else {
                t.setState("balanced".equals(name) ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
                t.setSubtitle(Profiles.label(list, name));
            }
            t.updateTile();
        });
    }
}
