// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Circular gauge: a thick rounded arc over a track, a value in the middle, a label under it. */
public final class RingGauge extends View {
    private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mArc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mValue = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mOval = new RectF();
    private final float mDp;
    private float mFraction;
    private String mText = "-", mCaption;
    private ValueAnimator mAnim;

    public RingGauge(Context c, String caption, int arcColor, int trackColor, int textColor) {
        super(c);
        mDp = c.getResources().getDisplayMetrics().density;
        mCaption = caption;
        mTrack.setStyle(Paint.Style.STROKE);
        mTrack.setStrokeCap(Paint.Cap.ROUND);
        mTrack.setStrokeWidth(10 * mDp);
        mTrack.setColor(trackColor);
        mArc.setStyle(Paint.Style.STROKE);
        mArc.setStrokeCap(Paint.Cap.ROUND);
        mArc.setStrokeWidth(10 * mDp);
        mArc.setColor(arcColor);
        mValue.setColor(textColor);
        mValue.setTextAlign(Paint.Align.CENTER);
        mValue.setTypeface(Ui.font(600));
        mValue.setTextSize(18 * mDp);
        mLabel.setColor((textColor & 0x00ffffff) | 0xb0000000);
        mLabel.setTextAlign(Paint.Align.CENTER);
        mLabel.setTypeface(Ui.font(500));
        mLabel.setTextSize(12 * mDp);
    }

    /** fraction 0..1 of the ring, text shown in the middle. */
    public void set(float fraction, String text) {
        mText = text;
        float to = Math.max(0f, Math.min(1f, fraction));
        if (mAnim != null) mAnim.cancel();
        mAnim = ValueAnimator.ofFloat(mFraction, to);
        mAnim.setDuration(600);
        mAnim.setInterpolator(new DecelerateInterpolator(2f));
        mAnim.addUpdateListener(a -> {
            mFraction = (float) a.getAnimatedValue();
            invalidate();
        });
        mAnim.start();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = (int) (88 * mDp);
        setMeasuredDimension(resolveSize(size, w), (int) (size + 22 * mDp));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float s = Math.min(getWidth(), getHeight() - 22 * mDp);
        float cx = getWidth() / 2f, r = s / 2f - 8 * mDp, cy = s / 2f;
        mOval.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(mOval, 135, 270, false, mTrack);
        if (mFraction > 0.002f) canvas.drawArc(mOval, 135, 270 * mFraction, false, mArc);
        Paint.FontMetrics fm = mValue.getFontMetrics();
        canvas.drawText(mText, cx, cy - (fm.ascent + fm.descent) / 2, mValue);
        canvas.drawText(mCaption, cx, getHeight() - 4 * mDp, mLabel);
    }
}
