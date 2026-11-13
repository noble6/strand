// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Updates Strand without a ROM build: the engine (org.strand.engine APEX:
 * strandd and the profiles) and this app, from the GitHub releases of
 * noble6/strand (tools/make-release.sh makes them).
 *
 * Both go in one staged install that applies on the next reboot, the way
 * Mainline modules update: Android only lets a persistent app update that
 * way, and the APEX needs a reboot anyway. Package manager only accepts
 * them when they carry the same keys as the installed build.
 */
final class Updater {
    static final String RELEASES = "https://api.github.com/repos/noble6/strand/releases/latest";
    static final String APEX = "org.strand.engine";
    static final String APEX_FILE = "org.strand.engine.apex";
    static final String APK_FILE = "Strand.apk";
    private static final String ACTION_RESULT = "org.strand.engine.UPDATE_RESULT";

    /** What a release offers against what is installed. */
    static final class Check {
        String tag, notes;
        long apexInstalled, apexOffered, appInstalled, appOffered;
        final Map<String, String> urls = new HashMap<>(), sha = new HashMap<>();

        boolean apexNewer() { return apexOffered > apexInstalled && urls.containsKey(APEX_FILE); }
        boolean appNewer() { return appOffered > appInstalled && urls.containsKey(APK_FILE); }
        boolean any() { return apexNewer() || appNewer(); }
    }

    private Updater() {}

    static long apexVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(APEX, PackageManager.MATCH_APEX);
            return pi.getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    static long appVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = con.getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            con.disconnect();
        }
    }

    /** Blocking: the latest release against the installed versions. */
    static Check check(Context c) throws Exception {
        JSONObject rel = new JSONObject(get(RELEASES));
        Check ck = new Check();
        ck.tag = rel.optString("tag_name");
        ck.notes = rel.optString("body");
        JSONArray assets = rel.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            ck.urls.put(a.getString("name"), a.getString("browser_download_url"));
        }
        if (!ck.urls.containsKey("update.json")) throw new Exception("release has no update.json");
        JSONObject u = new JSONObject(get(ck.urls.get("update.json")));
        ck.apexOffered = u.optLong("apex_version");
        ck.appOffered = u.optLong("app_version");
        JSONObject sha = u.getJSONObject("sha256");
        for (java.util.Iterator<String> it = sha.keys(); it.hasNext(); ) {
            String k = it.next();
            ck.sha.put(k, sha.getString(k));
        }
        ck.apexInstalled = apexVersion(c);
        ck.appInstalled = appVersion(c);
        return ck;
    }

    private static File download(Context c, Check ck, String name, Consumer<String> progress)
            throws Exception {
        String want = ck.sha.get(name);
        if (want == null) throw new Exception(name + ": no checksum in update.json");
        File dir = new File(c.getCacheDir(), "update");
        dir.mkdirs();
        File f = new File(dir, name);
        HttpURLConnection con = (HttpURLConnection) new URL(ck.urls.get(name)).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(60000);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long total = con.getContentLengthLong(), done = 0;
        try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                done += n;
                if (total > 0) progress.accept(name + " " + (done * 100 / total) + "%");
            }
        } finally {
            con.disconnect();
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format("%02x", b));
        if (!hex.toString().equalsIgnoreCase(want)) {
            f.delete();
            throw new Exception(name + ": checksum mismatch");
        }
        return f;
    }

    /** SessionParams calls that are system APIs (the app is a platform app). */
    private static void call(Object target, String method) throws Exception {
        target.getClass().getMethod(method).invoke(target);
    }

    private static int child(PackageInstaller pi, File f, boolean apex) throws Exception {
        PackageInstaller.SessionParams p =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        call(p, "setStaged");
        if (apex) call(p, "setInstallAsApex");
        p.setSize(f.length());
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id);
                InputStream in = new FileInputStream(f);
                OutputStream out = s.openWrite(f.getName(), 0, f.length())) {
            in.transferTo(out);
            s.fsync(out);
        }
        return id;
    }

    /**
     * Blocking: downloads what is newer and stages it. done gets null on
     * success (reboot to apply) or the error.
     */
    static void install(Context c, Check ck, Consumer<String> progress, Consumer<String> done) {
        try {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams parent =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            parent.setMultiPackage();
            call(parent, "setStaged");
            int parentId = pi.createSession(parent);
            try (PackageInstaller.Session s = pi.openSession(parentId)) {
                if (ck.apexNewer()) {
                    s.addChildSessionId(child(pi, download(c, ck, APEX_FILE, progress), true));
                }
                if (ck.appNewer()) {
                    s.addChildSessionId(child(pi, download(c, ck, APK_FILE, progress), false));
                }
                progress.accept("Verifying");
                c.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent i) {
                        ctx.unregisterReceiver(this);
                        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS,
                                PackageInstaller.STATUS_FAILURE);
                        done.accept(st == PackageInstaller.STATUS_SUCCESS ? null
                                : i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                    }
                }, new IntentFilter(ACTION_RESULT), Context.RECEIVER_NOT_EXPORTED);
                PendingIntent pin = PendingIntent.getBroadcast(c, parentId,
                        new Intent(ACTION_RESULT).setPackage(c.getPackageName()),
                        PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                s.commit(pin.getIntentSender());
            }
        } catch (Exception e) {
            done.accept(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }
}
