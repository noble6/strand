// SPDX-License-Identifier: GPL-3.0-or-later
package org.strand.engine.resona;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Owns the Resona effect: which profile and device are in force, the user's
 * changes on top of them (kept per device and profile, like Resona on Linux),
 * the volume the engine needs for its level-aware stages, and recovery when
 * audioserver restarts. Lives in the app's persistent process.
 */
public final class ResonaController {

    public interface Listener {
        void onResonaChanged();
    }

    private static ResonaController sInstance;

    private final Context mContext;
    private final AudioManager mAudio;
    private final SharedPreferences mPrefs;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> mListeners = new ArrayList<>();

    private ResonaEffect mEffect;
    private ResonaPresets.Device mDevice = ResonaPresets.Device.SPEAKER;
    private int mDeviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
    private String mAutoProfile = "Dynamic";
    private String mPendingAuto;
    private boolean mComparing;
    private boolean mStarted;
    private Boolean mInstalled;
    private SpeakerTuning mTuning;
    private boolean mLandscape = true;

    public static synchronized ResonaController get(Context c) {
        if (sInstance == null) sInstance = new ResonaController(c.getApplicationContext());
        return sInstance;
    }

    private ResonaController(Context c) {
        mContext = c;
        mAudio = c.getSystemService(AudioManager.class);
        mPrefs = c.getSharedPreferences("resona", Context.MODE_PRIVATE);
    }

    /** Called once from the persistent process. */
    public void start() {
        if (mStarted) return;
        mStarted = true;
        mAudio.registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) { routeChanged(); }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { routeChanged(); }
        }, mHandler);
        mAudio.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                playbackChanged(configs);
            }
        }, mHandler);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) { pushVolume(); }
        }, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"), Context.RECEIVER_EXPORTED);
        mTuning = SpeakerTuning.load();
        DisplayManager dm = mContext.getSystemService(DisplayManager.class);
        dm.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) {}
            @Override public void onDisplayRemoved(int id) {}

            @Override
            public void onDisplayChanged(int id) {
                if (id == Display.DEFAULT_DISPLAY) orientationChanged();
            }
        }, mHandler);
        mLandscape = landscape();
        routeChanged();
        mHandler.post(mWatchdog);
    }

    // --- factory speaker calibration -----------------------------------------

    /** True when the device ships a speaker calibration (resona_speaker.json). */
    public boolean hasFactoryTuning() { return mTuning != null; }
    public boolean isFactoryTuning() { return mPrefs.getBoolean("factory_tuning", true); }
    public boolean isLandscape() { return mLandscape; }

    public void setFactoryTuning(boolean on) {
        mPrefs.edit().putBoolean("factory_tuning", on).apply();
        apply();
    }

    private SpeakerTuning.Curve speakerCurve() {
        if (mTuning == null || !isFactoryTuning() || mDevice != ResonaPresets.Device.SPEAKER)
            return null;
        SpeakerTuning.Curve c = mTuning.forOrientation(mLandscape);
        return c != null && c.sections.length > 0 ? c : null;
    }

    private boolean landscape() {
        Display d = mContext.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (d == null) return true;
        Display.Mode m = d.getMode();
        int w = m.getPhysicalWidth(), h = m.getPhysicalHeight();
        int r = d.getRotation();
        boolean swapped = r == android.view.Surface.ROTATION_90 || r == android.view.Surface.ROTATION_270;
        return swapped ? h > w : w > h;
    }

    private void orientationChanged() {
        boolean l = landscape();
        if (l == mLandscape) return;
        mLandscape = l;
        if (mTuning != null && mDevice == ResonaPresets.Device.SPEAKER) apply();
    }

    // --- state the UI reads ------------------------------------------------

    public boolean available() {
        if (mInstalled == null) mInstalled = ResonaEffect.installed();
        return mInstalled;
    }
    public boolean isEnabled() { return mPrefs.getBoolean("enabled", true); }
    public String profile() { return mPrefs.getString("profile", ResonaPresets.AUTO); }
    public String effectiveProfile() {
        String p = profile();
        return ResonaPresets.AUTO.equals(p) ? mAutoProfile : p;
    }
    public ResonaPresets.Device device() { return mDevice; }

    /** Current value of a parameter: profile, device, then the user's changes. */
    public float get(String key) {
        int i = ResonaParams.index(key);
        return i < 0 ? 0 : params()[i];
    }

    public boolean overridden(String key) {
        return overrides().has(key);
    }

    public float[] meters() {
        ResonaEffect e = mEffect;
        return e == null ? null : e.meters();
    }

    public float[] scope() {
        ResonaEffect e = mEffect;
        return e == null ? null : e.scope();
    }

    public void addListener(Listener l) { mListeners.add(l); }
    public void removeListener(Listener l) { mListeners.remove(l); }

    // --- changes -----------------------------------------------------------

    public void setEnabled(boolean on) {
        mPrefs.edit().putBoolean("enabled", on).apply();
        apply();
        // The quick settings tile shows the new state even when the change came from the app
        android.service.quicksettings.TileService.requestListeningState(mContext,
                new ComponentName(mContext, ResonaTileService.class));
    }

    public void setProfile(String name) {
        mPrefs.edit().putString("profile", name).apply();
        apply();
    }

    /** A user change for the current device and profile. */
    public void set(String key, float value) {
        JSONObject o = overrides();
        try {
            o.put(key, (double) value);
        } catch (JSONException ignored) {
        }
        saveOverrides(o);
        int i = ResonaParams.index(key);
        if (i >= 0 && mEffect != null && !mComparing) mEffect.setParam(i, value);
        // No notify: the UI that made the change already shows it (and may be mid-drag).
    }

    public void setEq(float[] gains) {
        JSONObject o = overrides();
        try {
            for (int b = 0; b < 10; b++) o.put("eq" + b, (double) gains[b]);
        } catch (JSONException ignored) {
        }
        saveOverrides(o);
        apply();
    }

    /** Puts one effect (keys with this prefix) back to how the profile tunes it. */
    public void reset(String prefix) {
        JSONObject o = overrides();
        List<String> drop = new ArrayList<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            if (k.startsWith(prefix)) drop.add(k);
        }
        for (String k : drop) o.remove(k);
        saveOverrides(o);
        apply();
    }

    /** Hold to compare: the untouched signal while held. */
    public void compare(boolean holding) {
        mComparing = holding;
        if (mEffect != null) mEffect.setParam(ResonaParams.ENABLE, holding ? 0f : 1f);
    }

    // --- internals ---------------------------------------------------------

    private String overridesKey() {
        return "ov." + mDevice.name().toLowerCase() + "." + effectiveProfile();
    }

    private JSONObject overrides() {
        try {
            return new JSONObject(mPrefs.getString(overridesKey(), "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private void saveOverrides(JSONObject o) {
        mPrefs.edit().putString(overridesKey(), o.toString()).apply();
    }

    private float[] params() {
        float[] p = ResonaPresets.params(effectiveProfile(), mDevice);
        JSONObject o = overrides();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            int i = ResonaParams.index(k);
            if (i >= 0) p[i] = (float) o.optDouble(k, p[i]);
        }
        SpeakerTuning.Curve curve = speakerCurve();
        if (curve != null) {
            p[ResonaParams.CORR_ON] = 1f;
            p[ResonaParams.CORR_PREAMP] = curve.preamp;
        } else if (mDevice == ResonaPresets.Device.SPEAKER) {
            p[ResonaParams.CORR_ON] = 0f;
        }
        // DeepBass works where the factory tuning hands bass to virtual bass
        if (mDevice == ResonaPresets.Device.SPEAKER && mTuning != null && mTuning.bassHigh > 0
                && !o.has("bass_freq")) {
            p[ResonaParams.BASS_FREQ] = mTuning.bassHigh;
        }
        p[ResonaParams.SYSVOL_DB] = volumeDb();
        p[ResonaParams.ENABLE] = mComparing ? 0f : 1f;
        p[ResonaParams.LAYOUT] = 0f;
        return p;
    }

    private void apply() {
        if (!available()) return;
        if (!isEnabled()) {
            // Off means off: no effect on the mix, so offloaded playback comes back.
            if (mEffect != null) {
                mEffect.setEnabled(false);
                mEffect.release();
                mEffect = null;
            }
            notifyListeners();
            return;
        }
        if (mEffect == null || !mEffect.alive()) {
            if (mEffect != null) mEffect.release();
            mEffect = ResonaEffect.create();
        }
        if (mEffect != null) {
            SpeakerTuning.Curve curve = speakerCurve();
            if (curve != null) mEffect.setCorrection(curve.sections);
            mEffect.setParams(params());
            mEffect.setEnabled(true);
        }
        notifyListeners();
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<>(mListeners)) l.onResonaChanged();
    }

    /** Media follows Android's routing priority: headset, then external, then speaker. */
    private void routeChanged() {
        ResonaPresets.Device dev = ResonaPresets.Device.SPEAKER;
        int type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        for (AudioDeviceInfo d : mAudio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                case AudioDeviceInfo.TYPE_HEARING_AID:
                    dev = ResonaPresets.Device.HEADPHONES;
                    type = d.getType();
                    break;
                case AudioDeviceInfo.TYPE_HDMI:
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_LINE_ANALOG:
                case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                    if (dev == ResonaPresets.Device.SPEAKER) {
                        dev = ResonaPresets.Device.EXTERNAL;
                        type = d.getType();
                    }
                    break;
                default:
                    break;
            }
        }
        boolean changed = dev != mDevice;
        mDevice = dev;
        mDeviceType = type;
        if (changed || mEffect == null) apply();
        else pushVolume();
    }

    private float volumeDb() {
        try {
            int idx = mAudio.getStreamVolume(AudioManager.STREAM_MUSIC);
            float db = mAudio.getStreamVolumeDb(AudioManager.STREAM_MUSIC, idx, mDeviceType);
            return Float.isFinite(db) ? Math.max(-120f, Math.min(0f, db)) : -120f;
        } catch (Throwable t) {
            return 0f;
        }
    }

    private void pushVolume() {
        if (mEffect != null) mEffect.setParam(ResonaParams.SYSVOL_DB, volumeDb());
    }

    /** Auto: the profile that fits what is playing (games, films, music, speech). */
    private void playbackChanged(List<AudioPlaybackConfiguration> configs) {
        int best = 0;
        String pick = null;
        for (AudioPlaybackConfiguration c : configs) {
            AudioAttributes a = c.getAudioAttributes();
            String p = null;
            int rank = 0;
            if (a.getUsage() == AudioAttributes.USAGE_GAME) {
                p = "Gaming";
                rank = 5;
            } else if (a.getUsage() == AudioAttributes.USAGE_VOICE_COMMUNICATION) {
                p = "Voice";
                rank = 4;
            } else if (a.getUsage() == AudioAttributes.USAGE_MEDIA
                    || a.getUsage() == AudioAttributes.USAGE_UNKNOWN) {
                switch (a.getContentType()) {
                    case AudioAttributes.CONTENT_TYPE_MOVIE: p = "Movie"; rank = 3; break;
                    case AudioAttributes.CONTENT_TYPE_MUSIC: p = "Music"; rank = 2; break;
                    case AudioAttributes.CONTENT_TYPE_SPEECH: p = "Voice"; rank = 2; break;
                    default: p = "Dynamic"; rank = 1; break;
                }
            }
            if (p != null && rank > best) {
                best = rank;
                pick = p;
            }
        }
        if (pick == null || pick.equals(mAutoProfile)) {
            mPendingAuto = null;
            return;
        }
        // Debounce: a notification sound or a short pause shouldn't flip the profile.
        mPendingAuto = pick;
        mHandler.removeCallbacks(mAutoSwitch);
        mHandler.postDelayed(mAutoSwitch, 1500);
    }

    private final Runnable mAutoSwitch = () -> {
        if (mPendingAuto == null) return;
        mAutoProfile = mPendingAuto;
        mPendingAuto = null;
        if (ResonaPresets.AUTO.equals(profile())) apply();
    };

    /** audioserver restarts drop every effect: put Resona back. */
    private final Runnable mWatchdog = new Runnable() {
        @Override
        public void run() {
            if (isEnabled() && (mEffect == null || !mEffect.alive())) apply();
            mHandler.postDelayed(this, 5000);
        }
    };
}
