package com.ludox.game.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.ludox.game.gl.BoardThemes;

import java.util.HashMap;
import java.util.Map;

/**
 * A board theme drawn flat, exactly as it looks in a match, inside its rim (wood, or gold
 * on royal boards). Royal boards get a slow shine sweeping across when `shine` is on.
 */
public final class BoardPreview extends View {
    /** Painted board faces, one per theme (painting is slow, previews are many). */
    private static final Map<String, Bitmap> FACES = new HashMap<>();

    private final BoardThemes.Theme theme;
    private final boolean shine;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();
    private final RectF r = new RectF();
    private ValueAnimator sweep;
    private float sweepAt = -1f;

    public BoardPreview(Context c, String id, boolean shine) {
        super(c);
        this.theme = BoardThemes.get(id);
        this.shine = shine && theme.royal();
    }

    private static Bitmap face(BoardThemes.Theme t) {
        Bitmap b = FACES.get(t.id);
        if (b == null) {
            b = BoardThemes.bitmap(t, 512);
            FACES.put(t.id, b);
        }
        return b;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!shine) return;
        sweep = ValueAnimator.ofFloat(-0.6f, 1.6f);
        sweep.setDuration(2600);
        sweep.setStartDelay(400);
        sweep.setRepeatCount(ValueAnimator.INFINITE);
        sweep.setRepeatMode(ValueAnimator.RESTART);
        sweep.setInterpolator(new LinearInterpolator());
        sweep.addUpdateListener(a -> {
            sweepAt = (float) a.getAnimatedValue();
            invalidate();
        });
        sweep.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (sweep != null) sweep.cancel();
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        float x = (getWidth() - s) / 2f, y = (getHeight() - s) / 2f;
        float rim = s * 0.045f, round = s * 0.06f;
        int rimColor = rgb(theme.rim), rimDark = rgb(new float[]{theme.rim[0] * 0.6f, theme.rim[1] * 0.6f, theme.rim[2] * 0.6f});

        // Rim, lit from the top.
        r.set(x, y, x + s, y + s);
        p.setShader(new LinearGradient(x, y, x, y + s, rimColor, rimDark, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, round, round, p);
        p.setShader(null);

        // The board face.
        r.set(x + rim, y + rim, x + s - rim, y + s - rim);
        c.save();
        clip.reset();
        clip.addRoundRect(r, round * 0.5f, round * 0.5f, Path.Direction.CW);
        c.clipPath(clip);
        c.drawBitmap(face(theme), null, r, p);
        if (sweepAt > -1f) {
            float cx = r.left + r.width() * sweepAt, w = r.width() * 0.22f;
            p.setShader(new LinearGradient(cx - w, r.top, cx + w, r.bottom,
                    new int[]{0x00ffffff, 0x66ffffff, 0x00ffffff}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(r, p);
            p.setShader(null);
        }
        c.restore();
    }

    private static int rgb(float[] f) {
        return 0xff000000 | (Math.round(Math.min(1f, f[0]) * 255) << 16) | (Math.round(Math.min(1f, f[1]) * 255) << 8) | Math.round(Math.min(1f, f[2]) * 255);
    }
}
