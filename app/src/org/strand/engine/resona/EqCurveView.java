// SPDX-License-Identifier: GPL-3.0-or-later
package org.strand.engine.resona;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

/** 10-band EQ as a smooth curve with draggable band handles (+-15 dB). */
public class EqCurveView extends View {
    public interface OnChange {
        void onEq(float[] gains, boolean done);
    }

    static final String[] LABELS = {"32", "64", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"};
    private final float[] mGain = new float[10];
    private final Paint mCurve = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final float mDp;
    private int mDrag = -1;
    private OnChange mListener;

    public EqCurveView(Context c, int accent, int grid, int text) {
        super(c);
        mDp = c.getResources().getDisplayMetrics().density;
        mCurve.setStyle(Paint.Style.STROKE);
        mCurve.setStrokeWidth(3 * mDp);
        mCurve.setStrokeCap(Paint.Cap.ROUND);
        mCurve.setColor(accent);
        mDot.setColor(accent);
        mGrid.setColor(grid);
        mGrid.setStrokeWidth(mDp);
        mText.setColor(text);
        mText.setTextSize(11 * mDp);
        mText.setTextAlign(Paint.Align.CENTER);
    }

    public void setListener(OnChange l) { mListener = l; }

    public void setGains(float[] g) {
        if (mDrag >= 0) return;
        System.arraycopy(g, 0, mGain, 0, 10);
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (200 * mDp));
    }

    private float bx(int b) {
        float pad = 18 * mDp;
        return pad + (getWidth() - 2 * pad) * b / 9f;
    }

    private float gy(float g) {
        float top = 12 * mDp, bottom = getHeight() - 24 * mDp;
        return top + (bottom - top) * (15 - g) / 30f;
    }

    private float yg(float y) {
        float top = 12 * mDp, bottom = getHeight() - 24 * mDp;
        return 15 - (y - top) / (bottom - top) * 30f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        for (int g = -12; g <= 12; g += 6) canvas.drawLine(bx(0), gy(g), bx(9), gy(g), mGrid);
        mPath.reset();
        mPath.moveTo(bx(0), gy(mGain[0]));
        for (int b = 0; b < 9; b++) {
            float x0 = bx(b), x1 = bx(b + 1), mid = (x0 + x1) / 2;
            mPath.cubicTo(mid, gy(mGain[b]), mid, gy(mGain[b + 1]), x1, gy(mGain[b + 1]));
        }
        canvas.drawPath(mPath, mCurve);
        for (int b = 0; b < 10; b++) {
            canvas.drawCircle(bx(b), gy(mGain[b]), (b == mDrag ? 9 : 6) * mDp, mDot);
            canvas.drawText(LABELS[b], bx(b), getHeight() - 6 * mDp, mText);
        }
    }

    @Override
    @SuppressWarnings("fallthrough") // DOWN picks the band, then moves it like MOVE
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int best = 0;
                for (int b = 1; b < 10; b++)
                    if (Math.abs(bx(b) - e.getX()) < Math.abs(bx(best) - e.getX())) best = b;
                mDrag = best;
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            // fall through
            case MotionEvent.ACTION_MOVE: // picked band follows the finger
                if (mDrag >= 0) {
                    float g = Math.round(Math.max(-15, Math.min(15, yg(e.getY()))) * 2) / 2f;
                    if (g != mGain[mDrag]) {
                        mGain[mDrag] = g;
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                        if (mListener != null) mListener.onEq(mGain.clone(), false);
                    }
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mDrag >= 0 && mListener != null) mListener.onEq(mGain.clone(), true);
                mDrag = -1;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }
}
