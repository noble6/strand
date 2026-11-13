// SPDX-License-Identifier: GPL-3.0-or-later
package org.strand.engine.resona;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/**
 * Live before/after spectrum from the engine's scope: the input as a thin
 * line, Resona's output as a filled curve, on a log frequency axis.
 */
public class SpectrumView extends View {
    private static final int N = 1024;
    private static final int BANDS = 64;
    private final float[] mWindow = new float[N];
    private final float[] mRe = new float[N], mIm = new float[N];
    private final float[] mIn = new float[BANDS], mOut = new float[BANDS];
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final int mAccent;
    private float mRate = 48000;

    public SpectrumView(Context c, int accent, int dim) {
        super(c);
        mAccent = accent;
        for (int i = 0; i < N; i++) mWindow[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1)));
        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeWidth(c.getResources().getDisplayMetrics().density * 1.2f);
        mLine.setColor(dim);
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(c.getResources().getDisplayMetrics().density * 2f);
        mStroke.setColor(accent);
        mFill.setStyle(Paint.Style.FILL);
        for (int b = 0; b < BANDS; b++) mIn[b] = mOut[b] = -90;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        mFill.setShader(new LinearGradient(0, 0, 0, h, (mAccent & 0x00ffffff) | 0x90000000,
                mAccent & 0x00ffffff, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w),
                (int) (getResources().getDisplayMetrics().density * 150));
    }

    /** scope: SCOPE input samples then SCOPE output samples. */
    public void update(float[] scope) {
        if (scope == null || scope.length < 2 * N) {
            for (int b = 0; b < BANDS; b++) {
                mIn[b] += (-90 - mIn[b]) * 0.15f;
                mOut[b] += (-90 - mOut[b]) * 0.15f;
            }
        } else {
            analyse(scope, 0, mIn);
            analyse(scope, N, mOut);
        }
        invalidate();
    }

    private void analyse(float[] s, int off, float[] bands) {
        for (int i = 0; i < N; i++) {
            mRe[i] = s[off + i] * mWindow[i];
            mIm[i] = 0;
        }
        fft(mRe, mIm);
        double fMin = 30, fMax = 18000;
        for (int b = 0; b < BANDS; b++) {
            double f0 = fMin * Math.pow(fMax / fMin, (double) b / BANDS);
            double f1 = fMin * Math.pow(fMax / fMin, (double) (b + 1) / BANDS);
            int k0 = Math.max(1, (int) (f0 * N / mRate)), k1 = Math.max(k0 + 1, (int) (f1 * N / mRate));
            double e = 0;
            for (int k = k0; k < k1 && k < N / 2; k++) e += mRe[k] * mRe[k] + mIm[k] * mIm[k];
            float db = (float) (10 * Math.log10(e / (k1 - k0) + 1e-12)) - 20;
            // fast attack, slow fall, like a meter
            bands[b] += (db - bands[b]) * (db > bands[b] ? 0.6f : 0.12f);
        }
    }

    private static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1, ci = 0;
                for (int j = 0; j < len / 2; j++) {
                    int a = i + j, b = a + len / 2;
                    float xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    float nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }

    private float y(float db) {
        float t = Math.max(0, Math.min(1, (db + 80) / 80f));
        return getHeight() * (1 - t);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        mPath.reset();
        for (int b = 0; b < BANDS; b++) {
            float x = w * b / (BANDS - 1);
            if (b == 0) mPath.moveTo(x, y(mOut[b]));
            else mPath.lineTo(x, y(mOut[b]));
        }
        canvas.drawPath(mPath, mStroke);
        mPath.lineTo(w, h);
        mPath.lineTo(0, h);
        mPath.close();
        canvas.drawPath(mPath, mFill);

        mPath.reset();
        for (int b = 0; b < BANDS; b++) {
            float x = w * b / (BANDS - 1);
            if (b == 0) mPath.moveTo(x, y(mIn[b]));
            else mPath.lineTo(x, y(mIn[b]));
        }
        canvas.drawPath(mPath, mLine);
    }
}
