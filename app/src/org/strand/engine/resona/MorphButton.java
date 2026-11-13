// SPDX-License-Identifier: GPL-3.0-or-later
package org.strand.engine.resona;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * The power button: a polar "cookie" shape r(t) = R (1 + k cos(n t)) that
 * blooms out of a circle when Resona turns on, slowly turns while sound is
 * playing, and swells with the output level. Springy, like Resona on Linux.
 */
public final class MorphButton extends View {
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mIcon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private float mMorph;     // 0 circle, 1 full cookie
    private float mRotation;  // radians
    private float mLevel;     // 0..1 output level, smoothed
    private int mLobes = 9;
    private boolean mOn;
    private ValueAnimator mAnim;
    private final int mOnColor, mOffColor, mOnIcon, mOffIcon;

    public MorphButton(Context c, int onColor, int offColor, int onIcon, int offIcon) {
        super(c);
        mOnColor = onColor;
        mOffColor = offColor;
        mOnIcon = onIcon;
        mOffIcon = offIcon;
        mIcon.setStyle(Paint.Style.STROKE);
        mIcon.setStrokeCap(Paint.Cap.ROUND);
        setClickable(true);
    }

    public void setLobes(int lobes) {
        if (lobes == mLobes) return;
        mLobes = Math.max(0, lobes);
        invalidate();
    }

    public void setOn(boolean on, boolean animate) {
        if (on == mOn && animate) return;
        mOn = on;
        if (mAnim != null) mAnim.cancel();
        if (!animate) {
            mMorph = on ? 1 : 0;
            invalidate();
            return;
        }
        mAnim = ValueAnimator.ofFloat(mMorph, on ? 1 : 0);
        mAnim.setDuration(520);
        mAnim.setInterpolator(new OvershootInterpolator(2.2f));
        mAnim.addUpdateListener(a -> {
            mMorph = (float) a.getAnimatedValue();
            invalidate();
        });
        mAnim.start();
    }

    /** Called each frame with the output peak; drives the turn and the swell. */
    public void tick(float level) {
        mLevel += (Math.min(1f, level) - mLevel) * 0.25f;
        if (mOn) mRotation += 0.004f + 0.03f * mLevel;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int s = Math.min(MeasureSpec.getSize(w), (int) (getResources().getDisplayMetrics().density * 200));
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float base = Math.min(cx, cy) * 0.78f * (1f + 0.06f * mLevel * mMorph);
        float k = 0.075f * Math.max(0, mMorph);
        float m = Math.max(0f, Math.min(1.1f, mMorph));
        int color = blend(mOffColor, mOnColor, Math.min(1f, m));

        if (mOn) {
            mGlow.setColor((color & 0x00ffffff) | ((int) (40 + 60 * mLevel) << 24));
            canvas.drawCircle(cx, cy, base * (1.12f + 0.08f * mLevel), mGlow);
        }

        mPath.reset();
        int steps = 180;
        for (int i = 0; i <= steps; i++) {
            double t = 2 * Math.PI * i / steps;
            double r = base * (1 + (mLobes > 0 ? k * Math.cos(mLobes * (t + mRotation)) : 0));
            float x = (float) (cx + r * Math.cos(t)), y = (float) (cy + r * Math.sin(t));
            if (i == 0) mPath.moveTo(x, y);
            else mPath.lineTo(x, y);
        }
        mPath.close();
        mFill.setColor(color);
        canvas.drawPath(mPath, mFill);

        // power glyph
        float r = base * 0.32f;
        mIcon.setColor(mOn ? mOnIcon : mOffIcon);
        mIcon.setStrokeWidth(base * 0.075f);
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -60, 300, false, mIcon);
        canvas.drawLine(cx, cy - r * 1.25f, cx, cy - r * 0.2f, mIcon);
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        return 0xff000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8)
                | (int) (ab + (bb - ab) * t);
    }
}
