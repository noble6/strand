// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strand updates: engine (APEX) and app, staged, applied on reboot. Checks on
 * open and always shows the latest release notes.
 */
public class UpdateActivity extends Activity {
    private TextView mState, mStatus, mButton, mNotes, mNotesTitle, mNotesOpen;
    private ProgressBar mProgress;
    private LinearLayout mNotesCard;
    private Updater.Check mCheck;
    private boolean mStaged, mBusy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout col = Ui.page(this, getString(R.string.update_title),
                getString(R.string.update_summary));

        // Hero: what is installed, where it stands, who builds it
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setBackground(Ui.rounded(this, R.color.surface, 28));
        int p = Ui.dp(this, 20);
        hero.setPadding(p, p, p, p);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView badge = Ui.badge(this, R.drawable.ic_update, R.color.primary_container,
                R.color.on_primary_container, 56);
        ((LinearLayout.LayoutParams) badge.getLayoutParams()).setMarginEnd(Ui.dp(this, 16));
        head.addView(badge);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(Ui.text(this, getString(R.string.app_name), 24, R.color.text, 500));
        mState = Ui.text(this, "", 13, R.color.on_secondary_container, 600);
        mState.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 6);
        names.addView(mState, slp);
        head.addView(names, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        hero.addView(head);

        LinearLayout versions = new LinearLayout(this);
        versions.setOrientation(LinearLayout.HORIZONTAL);
        versions.setPadding(0, Ui.dp(this, 18), 0, 0);
        versions.addView(stat(getString(R.string.update_engine),
                String.valueOf(Updater.apexVersion(this))), weight(Ui.dp(this, 4)));
        versions.addView(stat(getString(R.string.update_app),
                String.valueOf(Updater.appVersion(this))), weight(0));
        ((LinearLayout.LayoutParams) versions.getChildAt(1).getLayoutParams())
                .setMarginStart(Ui.dp(this, 4));
        hero.addView(versions);

        String maintainer = android.os.SystemProperties.get("ro.strand.maintainer", "");
        if (!maintainer.isEmpty()) {
            LinearLayout m = new LinearLayout(this);
            m.setOrientation(LinearLayout.HORIZONTAL);
            m.setGravity(Gravity.CENTER_VERTICAL);
            m.setPadding(0, Ui.dp(this, 16), 0, 0);
            ImageView mi = new ImageView(this);
            mi.setImageResource(R.drawable.ic_sparkle);
            mi.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
            int s = Ui.dp(this, 18);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(s, s);
            ilp.setMarginEnd(Ui.dp(this, 8));
            m.addView(mi, ilp);
            m.addView(Ui.text(this, getString(R.string.update_maintainer, maintainer), 14,
                    R.color.text_dim, 500));
            hero.addView(m);
        }

        mStatus = Ui.text(this, "", 14, R.color.text_dim);
        mStatus.setPadding(0, Ui.dp(this, 16), 0, 0);
        mStatus.setLineSpacing(0, 1.1f);
        hero.addView(mStatus);

        mProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgress.setProgressTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        mProgress.setIndeterminateTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        mProgress.setProgressBackgroundTintList(
                ColorStateList.valueOf(Ui.color(this, R.color.surface_highest)));
        mProgress.setMax(100);
        mProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = Ui.dp(this, 10);
        hero.addView(mProgress, plp);

        mButton = Ui.button(this, getString(R.string.update_check), v -> onButton());
        LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) mButton.getLayoutParams();
        blp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        blp.topMargin = Ui.dp(this, 18);
        hero.addView(mButton);
        col.addView(hero);

        // Release notes of the latest release, whether or not it is newer
        mNotesTitle = Ui.section(this, getString(R.string.update_notes));
        mNotesTitle.setVisibility(View.GONE);
        col.addView(mNotesTitle);
        mNotesCard = new LinearLayout(this);
        mNotesCard.setOrientation(LinearLayout.VERTICAL);
        mNotesCard.setBackground(Ui.rounded(this, R.color.surface, 28));
        mNotesCard.setPadding(p, p, p, p);
        mNotesCard.setVisibility(View.GONE);
        mNotes = Ui.text(this, "", 15, R.color.text);
        mNotes.setLineSpacing(0, 1.2f);
        mNotes.setTextIsSelectable(true);
        mNotesCard.addView(mNotes);
        mNotesOpen = Ui.tonalButton(this, getString(R.string.update_notes_open), v -> {
            if (mCheck != null && !mCheck.page.isEmpty()) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(mCheck.page)));
            }
        });
        mNotesCard.addView(mNotesOpen);
        col.addView(mNotesCard);

        TextView about = Ui.text(this, getString(R.string.update_about), 13, R.color.text_dim);
        about.setPadding(Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8), 0);
        col.addView(about);

        check();
    }

    private LinearLayout.LayoutParams weight(int end) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMarginEnd(end);
        return lp;
    }

    /** A tonal box with a small label over a big value. */
    private LinearLayout stat(String label, String value) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(Ui.rounded(this, R.color.surface_high, 20));
        int p = Ui.dp(this, 14);
        b.setPadding(Ui.dp(this, 16), p, Ui.dp(this, 16), p);
        b.addView(Ui.text(this, label, 13, R.color.text_dim, 500));
        TextView v = Ui.text(this, value, 26, R.color.text, 500);
        v.setPadding(0, Ui.dp(this, 2), 0, 0);
        b.addView(v);
        return b;
    }

    /** The status pill: tertiary for news, secondary otherwise. */
    private void setState(int text, boolean highlight) {
        mState.setText(text);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 12));
        g.setColor(Ui.color(this, highlight ? R.color.tertiary_container : R.color.secondary_container));
        mState.setBackground(g);
        mState.setTextColor(Ui.color(this,
                highlight ? R.color.on_tertiary_container : R.color.on_secondary_container));
    }

    private void busy(boolean on) {
        mBusy = on;
        mButton.setEnabled(!on);
        mButton.setAlpha(on ? 0.5f : 1f);
        mProgress.setIndeterminate(true);
        mProgress.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void onButton() {
        if (mBusy) return;
        if (mStaged) {
            getSystemService(PowerManager.class).reboot(null);
        } else if (mCheck != null && mCheck.any()) {
            install();
        } else {
            check();
        }
    }

    private void check() {
        busy(true);
        setState(R.string.update_state_checking, false);
        mStatus.setText(R.string.update_checking);
        new Thread(() -> {
            Updater.Check ck = null;
            String err = null;
            try {
                ck = Updater.check(this);
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            Updater.Check result = ck;
            String error = err;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                busy(false);
                mCheck = result;
                if (result == null) {
                    setState(R.string.update_state_error, false);
                    mStatus.setText(getString(R.string.update_error, error));
                    mButton.setText(R.string.update_check);
                    return;
                }
                showNotes(result);
                if (result.any()) {
                    setState(R.string.update_state_available, true);
                    mStatus.setText(getString(R.string.update_available, result.tag,
                            result.apexOffered, result.appOffered));
                    mButton.setText(R.string.update_install);
                } else {
                    setState(R.string.update_state_current, false);
                    mStatus.setText(getString(R.string.update_none, result.tag));
                    mButton.setText(R.string.update_check);
                }
            });
        }).start();
    }

    private void install() {
        busy(true);
        mStatus.setText(R.string.update_installing);
        new Thread(() -> Updater.install(this, mCheck,
                msg -> runOnUiThread(() -> progress(msg)),
                err -> runOnUiThread(() -> {
                    busy(false);
                    if (err == null) {
                        mStaged = true;
                        setState(R.string.update_state_ready, true);
                        mStatus.setText(R.string.update_staged);
                        mButton.setText(R.string.update_reboot);
                    } else {
                        setState(R.string.update_state_error, false);
                        mStatus.setText(getString(R.string.update_error, err));
                    }
                }))).start();
    }

    /** Updater progress: "file NN%" while downloading, a word otherwise. */
    private void progress(String msg) {
        mStatus.setText(msg);
        Matcher m = Pattern.compile("(\\d+)%$").matcher(msg);
        if (m.find()) {
            mProgress.setIndeterminate(false);
            mProgress.setProgress(Integer.parseInt(m.group(1)));
        } else {
            mProgress.setIndeterminate(true);
        }
    }

    private void showNotes(Updater.Check ck) {
        StringBuilder title = new StringBuilder(getString(R.string.update_notes));
        String when = date(ck.published);
        title.append(" · ").append(ck.tag);
        if (!when.isEmpty()) title.append(" · ").append(when);
        mNotesTitle.setText(title);
        mNotesTitle.setVisibility(View.VISIBLE);
        mNotesCard.setVisibility(View.VISIBLE);
        mNotes.setText(ck.notes.trim().isEmpty() ? getString(R.string.update_notes_none)
                : markdown(ck.notes));
        mNotesOpen.setVisibility(ck.page.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static String date(String iso) {
        try {
            return OffsetDateTime.parse(iso).toLocalDate()
                    .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The GitHub release body as text: headings, bullets, **bold** and `code`.
     * Enough for release notes without a markdown library.
     */
    private CharSequence markdown(String md) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = md.replace("\r", "").split("\n");
        boolean lastBlank = true;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                if (!lastBlank) out.append("\n");
                lastBlank = true;
                continue;
            }
            lastBlank = false;
            int start = out.length();
            if (line.startsWith("#")) {
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') level++;
                inline(out, line.substring(level).trim());
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(level <= 1 ? 1.3f : level == 2 ? 1.15f : 1.05f),
                        start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append("\n");
            } else if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) {
                inline(out, line.substring(2).trim());
                out.append("\n");
                out.setSpan(new BulletSpan(Ui.dp(this, 10), Ui.color(this, R.color.accent),
                        Ui.dp(this, 3)), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                inline(out, line);
                out.append("\n");
            }
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.delete(out.length() - 1, out.length());
        }
        return out;
    }

    private void inline(SpannableStringBuilder out, String s) {
        Matcher m = Pattern.compile("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^\\]]+)\\]\\([^)]+\\)").matcher(s);
        int pos = 0;
        while (m.find()) {
            out.append(s, pos, m.start());
            int a = out.length();
            if (m.group(1) != null) {
                out.append(m.group(1));
                out.setSpan(new StyleSpan(Typeface.BOLD), a, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(2) != null) {
                out.append(m.group(2));
                out.setSpan(new TypefaceSpan("monospace"), a, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(Ui.color(this, R.color.surface_high)), a,
                        out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                out.append(m.group(3));
            }
            pos = m.end();
        }
        out.append(s, pos, s.length());
    }
}
