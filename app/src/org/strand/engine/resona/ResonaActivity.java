// SPDX-License-Identifier: GPL-3.0-or-later
package org.strand.engine.resona;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import org.strand.engine.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resona: one switch, a profile, and every effect when you want it. */
public class ResonaActivity extends Activity implements ResonaController.Listener {

    /** One effect card: an on/off key, sliders, and optional choice chips. */
    private static final class Fx {
        final String title, desc, onKey, prefix;
        final List<Object[]> sliders = new ArrayList<>(); // key, label, min, max
        String chipsKey;
        String[] chips;
        boolean headphonesOnly;

        Fx(String title, String desc, String onKey, String prefix) {
            this.title = title;
            this.desc = desc;
            this.onKey = onKey;
            this.prefix = prefix;
        }

        Fx slider(String key, String label, float min, float max) {
            sliders.add(new Object[] {key, label, min, max});
            return this;
        }

        Fx chips(String key, String[] names) {
            chipsKey = key;
            chips = names;
            return this;
        }
    }

    private static final List<Fx> EFFECTS = new ArrayList<>();
    static {
        EFFECTS.add(new Fx("DeepBass", "Harmonics your ear hears as deep bass, so even small speakers sound big",
                "bass_on", "bass_").slider("bass_amt", "Amount", 0, 1).slider("bass_punch", "Punch", 0, 1)
                .chips("bass_type", ResonaPresets.BASS_TYPES));
        EFFECTS.add(new Fx("Clarity", "Restores the air and detail lossy streaming takes away",
                "clar_on", "clar_").slider("clar_amt", "Amount", 0, 1));
        EFFECTS.add(new Fx("Voice Focus", "Lifts dialogue in the centre so it cuts through",
                "voice_on", "voice_").slider("voice_amt", "Amount", 0, 1));
        EFFECTS.add(new Fx("3D Stage", "Wider, deeper stage that keeps the bass centred",
                "stage_on", "stage_").slider("stage_width", "Width", 0, 2)
                .slider("stage_depth", "Depth", 0, 1));
        Fx spatial = new Fx("Spatial", "Virtual speakers in front of you, rendered for your ears",
                "hrtf_on", "hrtf_").slider("hrtf_angle", "Speaker angle", 10, 70)
                .slider("hrtf_amt", "Amount", 0, 1);
        spatial.headphonesOnly = true;
        EFFECTS.add(spatial);
        Fx xfeed = new Fx("Crossfeed", "Natural speaker-like blend, less fatigue on headphones",
                "xfeed_on", "xfeed_").slider("xfeed_amt", "Amount", 0, 1);
        xfeed.headphonesOnly = true;
        EFFECTS.add(xfeed);
        EFFECTS.add(new Fx("Intelligent EQ", "Listens to what plays and steers the tonal balance",
                "ieq_on", "ieq_").slider("ieq_amt", "Amount", 0, 1)
                .chips("ieq_target", ResonaPresets.IEQ_TARGETS));
        EFFECTS.add(new Fx("Ambience", "A small room around the sound", "amb_on", "amb_")
                .slider("amb_mix", "Mix", 0, 1).slider("amb_size", "Size", 0, 1));
        EFFECTS.add(new Fx("Tube Warmth", "The even-harmonic glow of a valve amp", "tube_on", "tube_")
                .slider("tube_drive", "Drive", 0, 1));
        EFFECTS.add(new Fx("Smart Leveler", "No more volume jumps between songs, ads and scenes",
                "lev_on", "lev_").slider("lev_amt", "Amount", 0, 1));
        EFFECTS.add(new Fx("Adaptive Loudness", "Full bass and air even at low volume",
                "loud_on", "loud_").slider("loud_amt", "Amount", 0, 1));
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ResonaController mCtl;
    private MorphButton mPower;
    private SpectrumView mSpectrum;
    private EqCurveView mEq;
    private TextView mSubtitle, mMeters;
    private LinearLayout mProfiles, mEqPresets, mEffects;
    private boolean mResumed;
    private boolean mBuilding;

    private final Runnable mFrame = new Runnable() {
        @Override
        public void run() {
            if (!mResumed) return;
            new Thread(() -> {
                float[] scope = mCtl.isEnabled() ? mCtl.scope() : null;
                float[] m = mCtl.isEnabled() ? mCtl.meters() : null;
                runOnUiThread(() -> frame(scope, m));
            }).start();
            mHandler.postDelayed(this, 33);
        }
    };

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(color));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card);
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView section(String s) {
        TextView t = text(s, 14, R.color.accent);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(4), dp(22), 0, dp(8));
        return t;
    }

    private TextView chip(String label, boolean on, View.OnClickListener l) {
        TextView c = text(label, 14, R.color.text);
        c.setBackgroundResource(on ? R.drawable.chip_on : R.drawable.chip);
        c.setPadding(dp(16), dp(9), dp(16), dp(9));
        c.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        c.setLayoutParams(lp);
        return c;
    }

    private HorizontalScrollView chipRow(LinearLayout row) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        row.setOrientation(LinearLayout.HORIZONTAL);
        h.addView(row);
        return h;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mCtl = ResonaController.get(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        // A soft wash of the wallpaper accent behind the header
        GradientDrawable wash = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {(getColor(R.color.accent) & 0x00ffffff) | 0x33000000, getColor(R.color.bg),
                        getColor(R.color.bg)});
        scroll.setBackground(wash);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(28), dp(20), dp(32));
        scroll.addView(col);
        setContentView(scroll);

        TextView title = text("Resona", 40, R.color.text);
        col.addView(title);
        mSubtitle = text("", 15, R.color.text_dim);
        mSubtitle.setPadding(0, dp(2), 0, dp(4));
        col.addView(mSubtitle);

        if (!mCtl.available()) {
            TextView t = text("The Resona effect isn't installed in this build.", 16, R.color.text_dim);
            t.setPadding(0, dp(24), 0, 0);
            col.addView(t);
            return;
        }

        mPower = new MorphButton(this, getColor(R.color.accent), getColor(R.color.surface_high),
                getColor(R.color.on_accent), getColor(R.color.text_dim));
        mPower.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            mCtl.setEnabled(!mCtl.isEnabled());
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.topMargin = dp(8);
        col.addView(mPower, plp);

        TextView compare = text("Hold to compare", 14, R.color.text_dim);
        compare.setBackgroundResource(R.drawable.chip);
        compare.setPadding(dp(18), dp(8), dp(18), dp(8));
        compare.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                mCtl.compare(true);
                compare.setText("Original");
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                mCtl.compare(false);
                compare.setText("Hold to compare");
            }
            return true;
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER_HORIZONTAL;
        clp.topMargin = dp(6);
        col.addView(compare, clp);

        LinearLayout spec = card();
        spec.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) spec.getLayoutParams()).topMargin = dp(16);
        mSpectrum = new SpectrumView(this, getColor(R.color.accent), getColor(R.color.text_dim));
        spec.addView(mSpectrum);
        mMeters = text("", 12, R.color.text_dim);
        mMeters.setPadding(0, dp(8), 0, 0);
        spec.addView(mMeters);
        col.addView(spec);

        col.addView(section("Profile"));
        mProfiles = new LinearLayout(this);
        col.addView(chipRow(mProfiles));

        col.addView(section("Equalizer"));
        LinearLayout eqCard = card();
        mEq = new EqCurveView(this, getColor(R.color.accent), getColor(R.color.graph_grid),
                getColor(R.color.text_dim));
        mEq.setListener((gains, done) -> {
            if (done) mCtl.setEq(gains);
            else for (int b = 0; b < 10; b++) mCtl.set("eq" + b, gains[b]);
        });
        eqCard.addView(mEq);
        mEqPresets = new LinearLayout(this);
        HorizontalScrollView eqRow = chipRow(mEqPresets);
        eqRow.setPadding(0, dp(8), 0, 0);
        eqCard.addView(eqRow);
        col.addView(eqCard);

        col.addView(section("Effects"));
        mEffects = new LinearLayout(this);
        mEffects.setOrientation(LinearLayout.VERTICAL);
        col.addView(mEffects);

        TextView credits = text("About and credits", 14, R.color.accent);
        credits.setPadding(dp(4), dp(18), 0, 0);
        credits.setOnClickListener(v -> showCredits());
        col.addView(credits);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mPower == null) return;
        mResumed = true;
        mCtl.addListener(this);
        rebuild();
        mPower.setOn(mCtl.isEnabled(), false);
        mHandler.post(mFrame);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mResumed = false;
        mHandler.removeCallbacks(mFrame);
        if (mCtl != null) mCtl.removeListener(this);
    }

    @Override
    public void onResonaChanged() {
        if (mBuilding) return;
        mPower.setOn(mCtl.isEnabled(), true);
        rebuild();
    }

    private void frame(float[] scope, float[] m) {
        mSpectrum.update(scope);
        float out = m == null ? 0 : Math.max(m[2], m[3]);
        mPower.tick(out);
        if (m != null) {
            mMeters.setText(String.format(Locale.getDefault(),
                    "Leveler %+.1f dB   Limiter %.1f dB   Loudness +%.1f dB", m[4], m[5], m[6]));
        } else {
            mMeters.setText(mCtl.isEnabled() ? "" : "Off");
        }
    }

    private String deviceName() {
        switch (mCtl.device()) {
            case HEADPHONES: return "Headphones";
            case EXTERNAL: return "External speakers";
            default: return "Speakers";
        }
    }

    /** Re-reads every value from the controller (profile, device or effect changed). */
    private void rebuild() {
        mBuilding = true;
        String prof = mCtl.profile(), eff = mCtl.effectiveProfile();
        mSubtitle.setText(deviceName() + "  ·  "
                + (ResonaPresets.AUTO.equals(prof) ? "Auto: " + eff : prof));
        ResonaPresets.Profile p = ResonaPresets.PROFILES.get(eff);
        mPower.setLobes(p == null ? 9 : p.petals);

        mProfiles.removeAllViews();
        mProfiles.addView(chip(ResonaPresets.AUTO, ResonaPresets.AUTO.equals(prof),
                v -> mCtl.setProfile(ResonaPresets.AUTO)));
        for (Map.Entry<String, ResonaPresets.Profile> e : ResonaPresets.PROFILES.entrySet()) {
            String name = e.getKey();
            TextView c = chip(name, name.equals(prof), v -> mCtl.setProfile(name));
            c.setOnLongClickListener(v -> {
                blurDialog(new AlertDialog.Builder(this).setTitle(name)
                        .setMessage(e.getValue().description).setPositiveButton("OK", null));
                return true;
            });
            mProfiles.addView(c);
        }

        float[] gains = new float[10];
        for (int b = 0; b < 10; b++) gains[b] = mCtl.get("eq" + b);
        mEq.setGains(gains);
        mEqPresets.removeAllViews();
        for (Map.Entry<String, float[]> e : ResonaPresets.EQ.entrySet()) {
            boolean match = java.util.Arrays.equals(e.getValue(), gains);
            mEqPresets.addView(chip(e.getKey(), match, v -> mCtl.setEq(e.getValue())));
        }

        mEffects.removeAllViews();
        for (Fx fx : EFFECTS) {
            if (fx.headphonesOnly && mCtl.device() != ResonaPresets.Device.HEADPHONES) continue;
            mEffects.addView(effectCard(fx));
        }
        mBuilding = false;
    }

    private View effectCard(Fx fx) {
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(fx.title, 18, R.color.text);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        texts.addView(t);
        texts.addView(text(fx.desc, 13, R.color.text_dim));
        head.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        boolean on = mCtl.get(fx.onKey) > 0.5f;
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((b, v) -> {
            mCtl.set(fx.onKey, v ? 1f : 0f);
            rebuild();
        });
        head.addView(sw);
        c.addView(head);
        if (!on) return c;

        for (Object[] s : fx.sliders) {
            String key = (String) s[0];
            float min = (Float) s[2], max = (Float) s[3];
            TextView label = text((String) s[1], 13, R.color.text_dim);
            label.setPadding(0, dp(12), 0, dp(2));
            c.addView(label);
            SeekBar bar = new SeekBar(this);
            bar.setMax(1000);
            bar.setProgressDrawable(getDrawable(R.drawable.seek_track));
            bar.setThumb(getDrawable(R.drawable.seek_thumb));
            bar.setSplitTrack(false);
            bar.setProgress(Math.round((mCtl.get(key) - min) / (max - min) * 1000));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int v, boolean user) {
                    if (user) mCtl.set(key, min + (max - min) * v / 1000f);
                }

                @Override public void onStartTrackingTouch(SeekBar sb) {}

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                    sb.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    rebuild();
                }
            });
            c.addView(bar);
        }
        if (fx.chips != null) {
            LinearLayout row = new LinearLayout(this);
            int cur = Math.round(mCtl.get(fx.chipsKey));
            for (int i = 0; i < fx.chips.length; i++) {
                final int idx = i;
                row.addView(chip(fx.chips[i], i == cur, v -> {
                    mCtl.set(fx.chipsKey, idx);
                    rebuild();
                }));
            }
            HorizontalScrollView h = chipRow(row);
            h.setPadding(0, dp(12), 0, 0);
            c.addView(h);
        }
        boolean changed = mCtl.overridden(fx.onKey) || mCtl.overridden(fx.chipsKey == null ? "-" : fx.chipsKey);
        for (Object[] s : fx.sliders) changed |= mCtl.overridden((String) s[0]);
        if (changed) {
            TextView reset = text("Reset to profile", 14, R.color.accent);
            reset.setPadding(0, dp(12), 0, 0);
            reset.setOnClickListener(v -> mCtl.reset(fx.prefix));
            c.addView(reset);
        }
        return c;
    }

    /** Dialogs float over a blurred page; SurfaceFlinger's Strand blur keeps that cheap. */
    private void blurDialog(AlertDialog.Builder b) {
        AlertDialog d = b.create();
        d.show();
        if (d.getWindow() != null) {
            d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            WindowManager.LayoutParams lp = d.getWindow().getAttributes();
            lp.setBlurBehindRadius(dp(24));
            d.getWindow().setAttributes(lp);
        }
    }

    private void showCredits() {
        blurDialog(new AlertDialog.Builder(this).setTitle("Resona")
                .setMessage("The Resona sound engine, the same DSP as Resona for Linux, "
                        + "built into Strand as an open audio effect: no proprietary blobs, "
                        + "no extra services.\n\n"
                        + "Resona by noble6 and rajdeep-3305, "
                        + "github.com/noble6/resona, GPL-3.0-or-later.\n\n"
                        + "Thanks to the Lunaris Dolby project (nekoshirro and contributors) "
                        + "for the reference integration of a system sound app.")
                .setPositiveButton("OK", null));
    }
}
