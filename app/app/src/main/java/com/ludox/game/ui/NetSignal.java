package com.ludox.game.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Four signal bars: a sweep while looking for the network, all lit once connected. Stepped by the HUD's ticker, so it costs nothing at rest. */
final class NetSignal extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private int color = 0xffff6b6b;
    private int lit = 4;
    private boolean sweeping;

    NetSignal(Context c) {
        super(c);
    }

    void set(int color, boolean sweeping) {
        this.color = color;
        this.sweeping = sweeping;
        if (!sweeping) lit = 4;
        invalidate();
    }

    void step() {
        if (!sweeping) return;
        lit = lit >= 4 ? 0 : lit + 1;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth() / 7f, h = getHeight();
        for (int i = 0; i < 4; i++) {
            paint.setColor(i < lit ? color : 0x44ffffff);
            float top = h * (0.7f - i * 0.23f), x = i * 2 * w;
            bar.set(x, top, x + w, h);
            c.drawRoundRect(bar, w / 3, w / 3, paint);
        }
    }
}
