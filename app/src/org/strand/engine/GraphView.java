// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Rolling line graph for one or more series, auto-scaled. */
public class GraphView extends View {
    private static final int SAMPLES = 120;

    private final List<float[]> mSeries = new ArrayList<>();
    private final List<Integer> mColors = new ArrayList<>();
    private int mCount;
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGrid = new Paint();
    private final Paint mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private float mFixedMax;

    public GraphView(Context c, int series, float fixedMax) {
        super(c);
        mFixedMax = fixedMax;
        int[] palette = {
            Theme.color(c, R.color.accent),
            Theme.color(c, R.color.warn),
            Theme.color(c, R.color.text_dim),
        };
        for (int i = 0; i < series; i++) {
            mSeries.add(new float[SAMPLES]);
            mColors.add(palette[i % palette.length]);
        }
        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeWidth(Ui.dp(c, 2));
        mLine.setStrokeJoin(Paint.Join.ROUND);
        mFill.setStyle(Paint.Style.FILL);
        mGrid.setColor(Theme.color(c, R.color.graph_grid));
        mGrid.setStrokeWidth(1);
        mLabel.setColor(Theme.color(c, R.color.text_dim));
        mLabel.setTextSize(Ui.dp(c, 11));
    }

    /** Adds one sample per series; NaN leaves a gap value of 0. */
    public void add(float... values) {
        for (int i = 0; i < mSeries.size(); i++) {
            float[] s = mSeries.get(i);
            System.arraycopy(s, 1, s, 0, SAMPLES - 1);
            s[SAMPLES - 1] = i < values.length && !Float.isNaN(values[i]) ? values[i] : 0;
        }
        mCount = Math.min(SAMPLES, mCount + 1);
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(getContext(), 120));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float top = Ui.dp(getContext(), 14);
        for (int i = 0; i <= 3; i++) {
            float y = top + (h - top) * i / 3f;
            canvas.drawLine(0, y, w, y, mGrid);
        }
        float max = mFixedMax;
        if (max <= 0) {
            for (float[] s : mSeries)
                for (int i = SAMPLES - mCount; i < SAMPLES; i++) max = Math.max(max, s[i]);
            max = max <= 0 ? 1 : max * 1.15f;
        }
        canvas.drawText(String.format(Locale.getDefault(), max >= 10 ? "%.0f" : "%.2f", max),
                0, top - Ui.dp(getContext(), 3), mLabel);
        if (mCount < 2) return;
        float step = w / (SAMPLES - 1);
        for (int k = 0; k < mSeries.size(); k++) {
            float[] s = mSeries.get(k);
            int color = mColors.get(k);
            mPath.reset();
            int first = SAMPLES - mCount;
            for (int i = first; i < SAMPLES; i++) {
                float x = i * step;
                float y = h - (h - top) * Math.min(1f, s[i] / max);
                if (i == first) mPath.moveTo(x, y);
                else mPath.lineTo(x, y);
            }
            mLine.setColor(color);
            canvas.drawPath(mPath, mLine);
            if (k == 0) {
                mPath.lineTo(w, h);
                mPath.lineTo(first * step, h);
                mPath.close();
                mFill.setColor((color & 0x00ffffff) | 0x30000000);
                canvas.drawPath(mPath, mFill);
            }
        }
    }
}
