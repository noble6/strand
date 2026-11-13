// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AppProfilesActivity extends Activity {

    private static final class App {
        String pkg;
        String label;
        Drawable icon;
        LinearLayout row;
        TextView profile;
    }

    private LinearLayout mList;
    private final List<App> mApps = new ArrayList<>();
    private final Map<String, String> mAssigned = new HashMap<>();
    private List<Profiles.Profile> mProfiles = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout col = Ui.page(this, getString(R.string.apps_title),
                getString(R.string.apps_summary));

        EditText search = new EditText(this);
        search.setHint(R.string.apps_search);
        search.setSingleLine(true);
        search.setBackgroundResource(R.drawable.card);
        int p = Ui.dp(this, 16);
        search.setPadding(p, p, p, p);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filter(s.toString()); }
        });
        col.addView(search);

        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        mList.setPadding(0, Ui.dp(this, 12), 0, 0);
        col.addView(mList);

        new Thread(this::load).start();
    }

    private void load() {
        mProfiles = Profiles.load();
        StrandClient.Reply r = StrandClient.call("apps");
        if (r != null && r.ok) mAssigned.putAll(r.map());

        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, App> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || byPkg.containsKey(pkg)) continue;
            App a = new App();
            a.pkg = pkg;
            a.label = ri.loadLabel(pm).toString();
            a.icon = ri.loadIcon(pm);
            byPkg.put(pkg, a);
        }
        List<App> apps = new ArrayList<>(byPkg.values());
        Collator coll = Collator.getInstance();
        // Apps with a profile first, then by name
        apps.sort((x, y) -> {
            boolean ax = mAssigned.containsKey(x.pkg), ay = mAssigned.containsKey(y.pkg);
            if (ax != ay) return ax ? -1 : 1;
            return coll.compare(x.label, y.label);
        });
        runOnUiThread(() -> {
            mApps.clear();
            mApps.addAll(apps);
            for (App a : apps) mList.addView(row(a));
        });
    }

    private LinearLayout row(App a) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.card);
        int p = Ui.dp(this, 14);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 6);
        row.setLayoutParams(lp);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(a.icon);
        int s = Ui.dp(this, 40);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(s, s);
        ilp.setMarginEnd(Ui.dp(this, 14));
        row.addView(icon, ilp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, a.label, 16, R.color.text));
        a.profile = Ui.text(this, "", 13, R.color.text_dim);
        texts.addView(a.profile);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        a.row = row;
        updateRow(a);
        row.setOnClickListener(v -> choose(a));
        return row;
    }

    private void updateRow(App a) {
        String prof = mAssigned.get(a.pkg);
        a.profile.setText(prof == null ? getString(R.string.apps_default)
                : Profiles.label(mProfiles, prof));
        a.profile.setTextColor(getColor(prof == null ? R.color.text_dim : R.color.accent));
    }

    private void choose(App a) {
        String[] items = new String[mProfiles.size() + 1];
        items[0] = getString(R.string.apps_default);
        int checked = 0;
        String cur = mAssigned.get(a.pkg);
        for (int i = 0; i < mProfiles.size(); i++) {
            items[i + 1] = mProfiles.get(i).label;
            if (mProfiles.get(i).name.equals(cur)) checked = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle(a.label)
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    String name = which == 0 ? null : mProfiles.get(which - 1).name;
                    if (name == null) mAssigned.remove(a.pkg);
                    else mAssigned.put(a.pkg, name);
                    updateRow(a);
                    StrandClient.async("app " + a.pkg + " " + (name == null ? "none" : name),
                            null);
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void filter(String q) {
        String needle = q.trim().toLowerCase(Locale.getDefault());
        for (App a : mApps) {
            boolean show = needle.isEmpty()
                    || a.label.toLowerCase(Locale.getDefault()).contains(needle)
                    || a.pkg.contains(needle);
            a.row.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }
}
