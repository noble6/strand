// SPDX-License-Identifier: GPL-3.0-or-later
package org.strand.engine.resona;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioMixerAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * What the sound is going through, like the Hardware page of Resona on Linux:
 * the output, what it accepts, the mixer rate, the Bluetooth codec, and the
 * Hi-Res mixer modes a USB DAC offers.
 */
final class ResonaHardware {
    private ResonaHardware() {}

    static final AudioAttributes MEDIA = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

    /** The output media plays on, by Android's routing priority. */
    static AudioDeviceInfo mediaOutput(AudioManager am) {
        AudioDeviceInfo speaker = null, external = null, headset = null;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                case AudioDeviceInfo.TYPE_HEARING_AID:
                    headset = d;
                    break;
                case AudioDeviceInfo.TYPE_HDMI:
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_LINE_ANALOG:
                case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                    external = d;
                    break;
                case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                    speaker = d;
                    break;
                default:
                    break;
            }
        }
        return headset != null ? headset : external != null ? external : speaker;
    }

    static String typeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "Built-in speakers";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "Wired headphones";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Wired headset";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB headset";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB audio (DAC)";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth";
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return "Bluetooth LE Audio";
            case AudioDeviceInfo.TYPE_BLE_SPEAKER: return "Bluetooth LE speaker";
            case AudioDeviceInfo.TYPE_HEARING_AID: return "Hearing aid";
            case AudioDeviceInfo.TYPE_HDMI: return "HDMI";
            case AudioDeviceInfo.TYPE_LINE_ANALOG: return "Line out";
            default: return "Output " + type;
        }
    }

    static String encodingName(int e) {
        switch (e) {
            case AudioFormat.ENCODING_PCM_16BIT: return "16-bit";
            case AudioFormat.ENCODING_PCM_24BIT_PACKED: return "24-bit";
            case AudioFormat.ENCODING_PCM_32BIT: return "32-bit";
            case AudioFormat.ENCODING_PCM_FLOAT: return "32-bit float";
            case AudioFormat.ENCODING_PCM_8BIT: return "8-bit";
            case AudioFormat.ENCODING_AC3: return "Dolby Digital";
            case AudioFormat.ENCODING_E_AC3: return "Dolby Digital Plus";
            case AudioFormat.ENCODING_DTS: return "DTS";
            default: return null;
        }
    }

    private static String rates(int[] r) {
        if (r == null || r.length == 0) return "any";
        TreeSet<Integer> s = new TreeSet<>();
        for (int x : r) s.add(x);
        List<String> out = new ArrayList<>();
        for (int x : s) out.add(x % 1000 == 0 ? (x / 1000) + "" : String.format(Locale.ROOT, "%.1f", x / 1000f));
        return String.join(", ", out) + " kHz";
    }

    /** Label/value rows describing the current output. */
    static List<String[]> describe(Context c, AudioDeviceInfo d) {
        AudioManager am = c.getSystemService(AudioManager.class);
        List<String[]> rows = new ArrayList<>();
        if (d == null) {
            rows.add(new String[] {"Output", "none"});
            return rows;
        }
        String name = d.getProductName() == null ? "" : d.getProductName().toString();
        rows.add(new String[] {"Output", typeName(d.getType())
                + (name.isEmpty() || name.equals(android.os.Build.MODEL) ? "" : ": " + name)});

        List<String> enc = new ArrayList<>();
        for (int e : d.getEncodings()) {
            String n = encodingName(e);
            if (n != null && !enc.contains(n)) enc.add(n);
        }
        if (!enc.isEmpty()) rows.add(new String[] {"Formats", String.join(", ", enc)});
        rows.add(new String[] {"Sample rates", rates(d.getSampleRates())});
        int[] ch = d.getChannelCounts();
        if (ch != null && ch.length > 0) {
            int max = 0;
            for (int x : ch) max = Math.max(max, x);
            rows.add(new String[] {"Channels", max == 1 ? "mono" : max == 2 ? "stereo" : max + " channels"});
        }
        String rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
        String frames = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
        if (rate != null) {
            rows.add(new String[] {"Mixer", rates(new int[] {Integer.parseInt(rate)})
                    + (frames != null ? ", " + frames + " frames per burst" : "")});
        }
        AudioMixerAttributes hires = bestMixer(am, d, false);
        if (hires != null) {
            rows.add(new String[] {"Hi-Res capable", describe(hires)});
        }
        return rows;
    }

    static String describe(AudioMixerAttributes m) {
        AudioFormat f = m.getFormat();
        String bits = encodingName(f.getEncoding());
        return rates(new int[] {f.getSampleRate()}) + (bits != null ? " / " + bits : "")
                + (m.getMixerBehavior() == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                        ? ", bit-perfect" : "");
    }

    /**
     * Highest-rate mixer setup the output offers. bitPerfect false keeps the
     * normal mixer (and Resona) running at that rate; true bypasses both.
     * Android offers these for USB outputs.
     */
    static AudioMixerAttributes bestMixer(AudioManager am, AudioDeviceInfo d, boolean bitPerfect) {
        if (d == null) return null;
        List<AudioMixerAttributes> list;
        try {
            list = am.getSupportedMixerAttributes(d);
        } catch (Throwable t) {
            return null;
        }
        AudioMixerAttributes best = null;
        for (AudioMixerAttributes m : list) {
            boolean bp = m.getMixerBehavior() == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT;
            if (bp != bitPerfect) continue;
            if (best == null || m.getFormat().getSampleRate() > best.getFormat().getSampleRate()) best = m;
        }
        return best != null && best.getFormat().getSampleRate() > 48000 ? best : null;
    }

    /** Hi-Res for this output: the best non bit-perfect mixer, or the default when off. */
    static boolean setHiRes(Context c, AudioDeviceInfo d, boolean on) {
        AudioManager am = c.getSystemService(AudioManager.class);
        try {
            if (!on) {
                am.clearPreferredMixerAttributes(MEDIA, d);
                return true;
            }
            AudioMixerAttributes m = bestMixer(am, d, false);
            return m != null && am.setPreferredMixerAttributes(MEDIA, d, m);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Bit-perfect: the file's own rate and bits straight to the DAC; Android skips all effects. */
    static boolean setBitPerfect(Context c, AudioDeviceInfo d, boolean on) {
        AudioManager am = c.getSystemService(AudioManager.class);
        try {
            if (!on) {
                am.clearPreferredMixerAttributes(MEDIA, d);
                return true;
            }
            AudioMixerAttributes m = bestMixer(am, d, true);
            return m != null && am.setPreferredMixerAttributes(MEDIA, d, m);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean bitPerfectOn(Context c, AudioDeviceInfo d) {
        try {
            AudioMixerAttributes m = c.getSystemService(AudioManager.class)
                    .getPreferredMixerAttributes(MEDIA, d);
            return m != null && m.getMixerBehavior() == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean hiResOn(Context c, AudioDeviceInfo d) {
        try {
            AudioMixerAttributes m = c.getSystemService(AudioManager.class)
                    .getPreferredMixerAttributes(MEDIA, d);
            return m != null && m.getFormat().getSampleRate() > 48000
                    && m.getMixerBehavior() != AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The codec a connected A2DP device is using, delivered on the main thread. */
    static void bluetoothCodec(Context c, Consumer<String> done) {
        BluetoothManager bm = c.getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null) {
            done.accept(null);
            return;
        }
        boolean ok = ad.getProfileProxy(c, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                String result = null;
                try {
                    BluetoothA2dp a2dp = (BluetoothA2dp) proxy;
                    // getCodecStatus is a system API; the app is a platform app
                    java.lang.reflect.Method status = BluetoothA2dp.class.getMethod("getCodecStatus",
                            BluetoothDevice.class);
                    for (BluetoothDevice dev : a2dp.getConnectedDevices()) {
                        BluetoothCodecStatus st = (BluetoothCodecStatus) status.invoke(a2dp, dev);
                        if (st == null) continue;
                        BluetoothCodecConfig cfg = st.getCodecConfig();
                        if (cfg == null) continue;
                        android.bluetooth.BluetoothCodecType type = cfg.getExtendedCodecType();
                        result = (type != null ? type.getCodecName() : "unknown") + ", "
                                + sampleRate(cfg.getSampleRate()) + ", " + bits(cfg.getBitsPerSample());
                    }
                } catch (Throwable t) {
                    result = null;
                }
                ad.closeProfileProxy(BluetoothProfile.A2DP, proxy);
                done.accept(result);
            }

            @Override
            public void onServiceDisconnected(int profile) {}
        }, BluetoothProfile.A2DP);
        if (!ok) done.accept(null);
    }

    private static String sampleRate(int r) {
        switch (r) {
            case BluetoothCodecConfig.SAMPLE_RATE_44100: return "44.1 kHz";
            case BluetoothCodecConfig.SAMPLE_RATE_48000: return "48 kHz";
            case BluetoothCodecConfig.SAMPLE_RATE_88200: return "88.2 kHz";
            case BluetoothCodecConfig.SAMPLE_RATE_96000: return "96 kHz";
            case BluetoothCodecConfig.SAMPLE_RATE_176400: return "176.4 kHz";
            case BluetoothCodecConfig.SAMPLE_RATE_192000: return "192 kHz";
            default: return "?";
        }
    }

    private static String bits(int b) {
        switch (b) {
            case BluetoothCodecConfig.BITS_PER_SAMPLE_16: return "16-bit";
            case BluetoothCodecConfig.BITS_PER_SAMPLE_24: return "24-bit";
            case BluetoothCodecConfig.BITS_PER_SAMPLE_32: return "32-bit";
            default: return "?";
        }
    }
}
