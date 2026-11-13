// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** The profiles strandd knows, in its order, with the app's descriptions. */
public final class Profiles {

    public static final class Profile {
        public final String name;
        public final String label;

        Profile(String name, String label) {
            this.name = name;
            this.label = label;
        }
    }

    private Profiles() {}

    /** Blocking. Empty when the daemon is down. */
    public static List<Profile> load() {
        List<Profile> out = new ArrayList<>();
        StrandClient.Reply r = StrandClient.call("profiles");
        if (r == null || !r.ok) return out;
        for (String l : r.lines) {
            int sp = l.indexOf(' ');
            if (sp > 0) out.add(new Profile(l.substring(0, sp), l.substring(sp + 1)));
        }
        return out;
    }

    public static String description(Context c, String name) {
        switch (name) {
            case "balanced": return c.getString(R.string.desc_balanced);
            case "performance": return c.getString(R.string.desc_performance);
            case "battery": return c.getString(R.string.desc_battery);
            case "gaming": return c.getString(R.string.desc_gaming);
            default: return c.getString(R.string.desc_other);
        }
    }

    public static String label(List<Profile> list, String name) {
        for (Profile p : list) if (p.name.equals(name)) return p.label;
        return name;
    }
}
