// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * An icon inside a Material 3 Expressive shape: a polar "cookie" with n
 * lobes (0 = circle). The shape turns a little and springs when selected.
 */
public final class ShapeIcon extends View {
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final Drawable mIcon;
    private final int mLobes;
    private final float mDepth;
    private float mTurn, mScale = 1f;
    private ValueAnimator mAnim;

    public ShapeIcon(Context c, int iconRes, int lobes, float depth) {
        super(c);
        mIcon = c.getDrawable(iconRes).mutate();
        mLobes = lobes;
        mDepth = depth;
    }

    public void setColors(int shape, int icon) {
        mFill.setColor(shape);
        mIcon.setTint(icon);
        invalidate();
    }

    /** Selection: a springy quarter-lobe turn and a short pop. */
    public void pop() {
        if (mAnim != null) mAnim.cancel();
        float from = mTurn;
        float to = mTurn + (mLobes > 0 ? (float) (Math.PI / mLobes) : 0f);
        mAnim = ValueAnimator.ofFloat(0f, 1f);
        mAnim.setDuration(520);
        mAnim.setInterpolator(new OvershootInterpolator(2.5f));
        mAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            mTurn = from + (to - from) * t;
            mScale = 1f + 0.08f * (float) Math.sin(Math.PI * Math.min(1f, t));
            invalidate();
        });
        mAnim.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float base = Math.min(cx, cy) * 0.92f / (1 + mDepth) * mScale;
        mPath.reset();
        int steps = 120;
        for (int i = 0; i <= steps; i++) {
            double t = 2 * Math.PI * i / steps;
            double r = base * (1 + (mLobes > 0 ? mDepth * Math.cos(mLobes * (t + mTurn)) : 0));
            float x = (float) (cx + r * Math.cos(t)), y = (float) (cy + r * Math.sin(t));
            if (i == 0) mPath.moveTo(x, y);
            else mPath.lineTo(x, y);
        }
        mPath.close();
        canvas.drawPath(mPath, mFill);
        int half = (int) (Math.min(cx, cy) * 0.48f);
        mIcon.setBounds((int) cx - half, (int) cy - half, (int) cx + half, (int) cy + half);
        mIcon.draw(canvas);
    }
}
