// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Material 3 Expressive decoration: a few soft polar shapes (cookie, clover,
 * flower) that turn slowly behind a header or a hero card. Animates only
 * while attached and visible.
 */
public final class Decor extends View {
    /** x, y (fraction of the view), radius (fraction of height), lobes, depth, speed, color idx */
    private final float[][] mShapes;
    private final int[] mColors;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private ValueAnimator mAnim;
    private float mT;

    public Decor(Context c, float[][] shapes, int[] colors) {
        super(c);
        mShapes = shapes;
        mColors = colors;
    }

    /** Header decoration: a big cookie and a clover at the top right. */
    public static Decor header(Context c) {
        return new Decor(c, new float[][] {
                {0.86f, 0.42f, 0.62f, 9, 0.08f, 1f, 0},
                {0.70f, 0.80f, 0.30f, 4, 0.16f, -1.6f, 1},
                {0.97f, 0.95f, 0.18f, 0, 0f, 0f, 2},
        }, new int[] {
                (Theme.color(c, R.color.primary_container) & 0x00ffffff) | 0xcc000000,
                (Theme.color(c, R.color.tertiary_container) & 0x00ffffff) | 0xdd000000,
                (Theme.color(c, R.color.secondary_container) & 0x00ffffff) | 0xee000000,
        });
    }

    /** Hero decoration: large soft shapes in the card's own color. */
    public static Decor hero(Context c) {
        int on = Theme.color(c, R.color.on_primary_container);
        return new Decor(c, new float[][] {
                {0.92f, 0.10f, 0.55f, 12, 0.07f, 0.7f, 0},
                {0.08f, 1.02f, 0.38f, 6, 0.10f, -1f, 1},
        }, new int[] {(on & 0x00ffffff) | 0x12000000, (on & 0x00ffffff) | 0x0c000000});
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAnim = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        mAnim.setDuration(60000);
        mAnim.setRepeatCount(ValueAnimator.INFINITE);
        mAnim.setInterpolator(new LinearInterpolator());
        mAnim.addUpdateListener(a -> {
            mT = (float) a.getAnimatedValue();
            invalidate();
        });
        mAnim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mAnim != null) mAnim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View v, int visibility) {
        super.onVisibilityChanged(v, visibility);
        if (mAnim == null) return;
        if (visibility == VISIBLE && isShown()) mAnim.resume();
        else mAnim.pause();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        for (float[] s : mShapes) {
            float cx = s[0] * w, cy = s[1] * h, r = s[2] * h;
            int lobes = (int) s[3];
            float depth = s[4], turn = mT * s[5];
            mPath.reset();
            for (int i = 0; i <= 96; i++) {
                double t = 2 * Math.PI * i / 96;
                double rr = r * (1 + (lobes > 0 ? depth * Math.cos(lobes * (t + turn / lobes)) : 0));
                float x = (float) (cx + rr * Math.cos(t)), y = (float) (cy + rr * Math.sin(t));
                if (i == 0) mPath.moveTo(x, y);
                else mPath.lineTo(x, y);
            }
            mPath.close();
            mPaint.setColor(mColors[(int) s[6]]);
            canvas.drawPath(mPath, mPaint);
        }
    }
}
