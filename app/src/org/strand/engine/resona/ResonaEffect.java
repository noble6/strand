// SPDX-License-Identifier: GPL-3.0-or-later
package org.strand.engine.resona;

import android.media.audiofx.AudioEffect;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * The libresonafx effect on the output mix (session 0). The constructor and
 * the raw parameter calls are hidden APIs, reached by reflection so this file
 * also builds against the public SDK; the app is a platform app, so they're allowed.
 */
final class ResonaEffect {
    private static final String TAG = "Resona";

    static final UUID TYPE = UUID.fromString("7c0e4a52-9d61-4f3e-8b2a-5245534f4e41");
    static final UUID IMPL = UUID.fromString("2f3c6d81-5b0e-4c7a-9e14-5245534f4e41");

    static final int KEY_PARAMS = 1000;
    static final int KEY_CORRECTION = 1001;
    static final int KEY_VERSION = 2000;
    static final int KEY_METERS = 2001;
    static final int KEY_SCOPE = 2002;
    static final int METERS = 18;
    static final int SCOPE = 1024;

    private static Constructor<AudioEffect> sCtor;
    private static Method sSet, sGet;

    private final AudioEffect mEffect;

    private ResonaEffect(AudioEffect e) {
        mEffect = e;
    }

    /** Null when the library isn't installed or the effect can't be created. */
    static ResonaEffect create() {
        try {
            if (sCtor == null) {
                sCtor = AudioEffect.class.getConstructor(UUID.class, UUID.class, int.class,
                        int.class);
                sSet = AudioEffect.class.getMethod("setParameter", byte[].class, byte[].class);
                sGet = AudioEffect.class.getMethod("getParameter", byte[].class, byte[].class);
            }
            return new ResonaEffect(sCtor.newInstance(TYPE, IMPL, 0, 0));
        } catch (Throwable t) {
            Log.w(TAG, "can't create the Resona effect", t);
            return null;
        }
    }

    static boolean installed() {
        for (AudioEffect.Descriptor d : AudioEffect.queryEffects()) {
            if (IMPL.equals(d.uuid)) return true;
        }
        return false;
    }

    boolean alive() {
        try {
            return mEffect.hasControl();
        } catch (Throwable t) {
            return false;
        }
    }

    void setEnabled(boolean on) {
        try {
            mEffect.setEnabled(on);
        } catch (Throwable t) {
            Log.w(TAG, "setEnabled", t);
        }
    }

    void release() {
        try {
            mEffect.release();
        } catch (Throwable ignored) {
        }
    }

    private static byte[] key(int k) {
        return ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(k).array();
    }

    private boolean set(int key, byte[] value) {
        try {
            Object r = sSet.invoke(mEffect, key(key), value);
            return r instanceof Integer && (Integer) r == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private float[] getFloats(int key, int count) {
        byte[] value = new byte[count * 4];
        try {
            Object r = sGet.invoke(mEffect, key(key), value);
            if (!(r instanceof Integer) || (Integer) r < count * 4) return null;
        } catch (Throwable t) {
            return null;
        }
        float[] out = new float[count];
        ByteBuffer.wrap(value).order(ByteOrder.nativeOrder()).asFloatBuffer().get(out);
        return out;
    }

    private static byte[] floats(float[] f) {
        ByteBuffer b = ByteBuffer.allocate(f.length * 4).order(ByteOrder.nativeOrder());
        b.asFloatBuffer().put(f);
        return b.array();
    }

    boolean setParams(float[] all) {
        return set(KEY_PARAMS, floats(all));
    }

    boolean setParam(int index, float value) {
        return set(index, floats(new float[] {value}));
    }

    /** Correction sections, 10 floats each: type freq gain q b0 b1 b2 a1 a2 pad. */
    boolean setCorrection(float[] sections) {
        return set(KEY_CORRECTION, floats(sections));
    }

    /** in L/R, out L/R peaks, leveler, limiter gain reduction, loudness, content level, IEQ x10. */
    float[] meters() {
        return getFloats(KEY_METERS, METERS);
    }

    /** Last SCOPE mono samples of input, then of output. */
    float[] scope() {
        return getFloats(KEY_SCOPE, SCOPE * 2);
    }
}
