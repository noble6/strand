// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.app.Activity;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Strand updates: engine (APEX) and app, staged, applied on reboot. */
public class UpdateActivity extends Activity {
    private TextView mStatus, mNotes, mButton;
    private Updater.Check mCheck;
    private boolean mStaged;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout col = Ui.page(this, getString(R.string.update_title), null);
        LinearLayout card = Ui.card(this, getString(R.string.update_installed,
                Updater.apexVersion(this), Updater.appVersion(this)), null);
        mStatus = Ui.text(this, "", 14, R.color.text_dim);
        mStatus.setPadding(0, Ui.dp(this, 6), 0, 0);
        card.addView(mStatus);
        mNotes = Ui.text(this, "", 13, R.color.text_dim);
        mNotes.setPadding(0, Ui.dp(this, 8), 0, 0);
        mNotes.setVisibility(View.GONE);
        card.addView(mNotes);
        String maintainer = android.os.SystemProperties.get("ro.strand.maintainer", "");
        if (!maintainer.isEmpty()) {
            TextView m = Ui.text(this, getString(R.string.update_maintainer, maintainer), 14,
                    R.color.text_dim);
            m.setPadding(0, Ui.dp(this, 6), 0, 0);
            card.addView(m);
        }
        mButton = Ui.button(this, getString(R.string.update_check), v -> onButton());
        card.addView(mButton);
        col.addView(card);
        TextView about = Ui.text(this, getString(R.string.update_about), 13, R.color.text_dim);
        about.setPadding(Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4), 0);
        col.addView(about);
    }

    private void onButton() {
        if (mStaged) {
            getSystemService(PowerManager.class).reboot(null);
        } else if (mCheck != null && mCheck.any()) {
            install();
        } else {
            check();
        }
    }

    private void check() {
        mButton.setEnabled(false);
        mStatus.setText(R.string.update_checking);
        new Thread(() -> {
            Updater.Check ck = null;
            String err = null;
            try {
                ck = Updater.check(this);
            } catch (Exception e) {
                err = e.getMessage();
            }
            Updater.Check result = ck;
            String error = err;
            runOnUiThread(() -> {
                mButton.setEnabled(true);
                mCheck = result;
                if (result == null) {
                    mStatus.setText(getString(R.string.update_error, error));
                } else if (!result.any()) {
                    mStatus.setText(getString(R.string.update_none, result.tag));
                } else {
                    mStatus.setText(getString(R.string.update_available, result.tag,
                            result.apexOffered, result.appOffered));
                    if (!result.notes.isEmpty()) {
                        mNotes.setText(result.notes);
                        mNotes.setVisibility(View.VISIBLE);
                    }
                    mButton.setText(R.string.update_install);
                }
            });
        }).start();
    }

    private void install() {
        mButton.setEnabled(false);
        new Thread(() -> Updater.install(this, mCheck,
                p -> runOnUiThread(() -> mStatus.setText(p)),
                err -> runOnUiThread(() -> {
                    mButton.setEnabled(true);
                    if (err == null) {
                        mStaged = true;
                        mStatus.setText(R.string.update_staged);
                        mButton.setText(R.string.update_reboot);
                    } else {
                        mStatus.setText(getString(R.string.update_error, err));
                    }
                }))).start();
    }
}
