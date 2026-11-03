package com.ludox.game.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A countdown drawn clockwise from 12 o'clock: either a translucent pie over an avatar (the
 * turn timer) or a thin ring (the re-roll offer). `fraction` is the time left, 0..1.
 */
final class TimerRing extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final boolean pie;
    private float fraction;
    private int color;

    TimerRing(Context c, boolean pie, int color) {
        super(c);
        this.pie = pie;
        this.color = color;
    }

    void setFraction(float f, int color) {
        f = Math.max(0f, Math.min(1f, f));
        if (f == fraction && color == this.color) return;
        fraction = f;
        this.color = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (fraction <= 0) return;
        float w = getWidth(), h = getHeight();
        if (pie) {
            oval.set(0, 0, w, h);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawArc(oval, -90, 360 * fraction, true, paint);
        } else {
            float sw = Math.max(3f, w * 0.1f);
            oval.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(sw);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(color);
            canvas.drawArc(oval, -90, 360 * fraction, false, paint);
        }
    }
}
