package com.ludox.game.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import com.ludox.game.gl.BoardThemes;

/**
 * The match screen dressed in the board's own colours: a premium board's sky fills the whole
 * screen (so the 3D board sits in it seamlessly instead of in a box), the bars above and
 * below glow in its metal trim, and the panels and buttons take its tones. The classic board
 * keeps the app's purple look.
 */
final class MatchLook {
    final boolean themed;
    /** The board's environment colour (the 3D view clears to exactly this). */
    final int sky;
    /** Metal trim: hairlines, timer bar, match info. */
    final int accent;
    /** Status box and chat / emote panels, and their outline. */
    final int panel, stroke;
    /** Round buttons' bevel {top, bottom, side}. */
    final int[] button;

    private MatchLook(boolean themed, int sky, int accent, int panel, int stroke, int[] button) {
        this.themed = themed;
        this.sky = sky;
        this.accent = accent;
        this.panel = panel;
        this.stroke = stroke;
        this.button = button;
    }

    static final MatchLook CLASSIC = new MatchLook(false, 0xff4316a0, Ui.GOLD, 0xcc2e1065, Ui.STROKE, Fx.PURPLE);

    static MatchLook of(BoardThemes.Theme t) {
        if (t == null || !t.royal()) return CLASSIC;
        int sky = t.sky | 0xff000000, accent = t.trim | 0xff000000;
        // Panels stay dark enough for white text even on a light sky (a custom board may have one).
        int panel = Fx.blend(Fx.blend(sky, accent, 0.12f), Color.BLACK, luma(sky) > 0.45f ? 0.6f : 0.25f);
        int[] button = {
                Fx.blend(sky, accent, 0.62f),
                Fx.blend(sky, accent, 0.3f),
                Fx.blend(Fx.blend(sky, accent, 0.2f), Color.BLACK, 0.55f),
        };
        return new MatchLook(true, sky, accent, (panel & 0x00ffffff) | 0xe6000000, (accent & 0x00ffffff) | 0x99000000, button);
    }

    private static float luma(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
    }

    /**
     * The screen behind a premium board: flat sky where the board is (the 3D view clears to
     * the same colour, so there is no seam), a soft trim-tinted glow and a faint diamond
     * pattern over the bars above and below, and a fine metal line along the board's top and
     * bottom edges. Painted once per size and board position.
     */
    static final class Backdrop extends Drawable {
        private final Context c;
        private final MatchLook look;
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        private Bitmap cache;
        /** The board's top and bottom, in this drawable's coordinates (-1 = not laid out yet). */
        private int bandTop = -1, bandBottom = -1;

        Backdrop(Context c, MatchLook look) {
            this.c = c;
            this.look = look;
        }

        void setBand(int top, int bottom) {
            if (top == bandTop && bottom == bandBottom) return;
            bandTop = top;
            bandBottom = bottom;
            paint();
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(Rect b) {
            paint();
        }

        private void paint() {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            int w = b.width(), h = b.height();
            if (cache == null || cache.getWidth() != w || cache.getHeight() != h) {
                cache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            Canvas cv = new Canvas(cache);
            cv.drawColor(look.sky);
            int top = bandTop >= 0 ? bandTop : Math.round(h * 0.25f);
            int bottom = bandBottom > top ? bandBottom : Math.round(h * 0.75f);
            float d = Fx.dp(c, 1);
            Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);

            // Above the board: trim light falling from the top, fading into the sky.
            int glowTop = Fx.blend(look.sky, look.accent, 0.22f);
            q.setShader(new LinearGradient(0, 0, 0, top, glowTop, look.sky, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, top, q);
            // Below: the sky sinks into a deeper shade behind the controls.
            int deep = Fx.blend(Fx.blend(look.sky, look.accent, 0.1f), Color.BLACK, 0.35f);
            q.setShader(new LinearGradient(0, bottom, 0, h, look.sky, deep, Shader.TileMode.CLAMP));
            cv.drawRect(0, bottom, w, h, q);

            // A faint diamond lattice in the trim colour, strongest far from the board.
            Path lattice = new Path();
            float s = d * 18;
            int row = 0;
            for (float y = -s; y < h + s; y += s, row++) {
                for (float x = row % 2 == 0 ? 0 : s / 2; x < w + s; x += s) {
                    lattice.moveTo(x, y - s * 0.3f);
                    lattice.lineTo(x + s * 0.3f, y);
                    lattice.lineTo(x, y + s * 0.3f);
                    lattice.lineTo(x - s * 0.3f, y);
                    lattice.close();
                }
            }
            q.setStyle(Paint.Style.STROKE);
            q.setStrokeWidth(d * 0.6f);
            int faint = (look.accent & 0x00ffffff) | 0x26000000, none = look.accent & 0x00ffffff;
            cv.save();
            cv.clipRect(0, 0, w, top);
            q.setShader(new LinearGradient(0, 0, 0, top, faint, none, Shader.TileMode.CLAMP));
            cv.drawPath(lattice, q);
            cv.restore();
            cv.save();
            cv.clipRect(0, bottom, w, h);
            q.setShader(new LinearGradient(0, bottom, 0, h, none, faint, Shader.TileMode.CLAMP));
            cv.drawPath(lattice, q);
            cv.restore();
            q.setStyle(Paint.Style.FILL);

            // Fine metal lines along the board's edges, bright in the middle and fading out,
            // with a soft glow spilling away from the board.
            if (bandTop >= 0) {
                int lit = (look.accent & 0x00ffffff) | 0xcc000000;
                int[] line = {none, lit, lit, none};
                float[] at = {0f, 0.3f, 0.7f, 1f};
                int haze = (look.accent & 0x00ffffff) | 0x30000000;
                q.setShader(new LinearGradient(0, top - d * 10, 0, top, none, haze, Shader.TileMode.CLAMP));
                cv.drawRect(0, top - d * 10, w, top, q);
                q.setShader(new LinearGradient(0, bottom, 0, bottom + d * 10, haze, none, Shader.TileMode.CLAMP));
                cv.drawRect(0, bottom, w, bottom + d * 10, q);
                q.setShader(new LinearGradient(0, 0, w, 0, line, at, Shader.TileMode.CLAMP));
                cv.drawRect(0, top - d * 1.5f, w, top, q);
                cv.drawRect(0, bottom, w, bottom + d * 1.5f, q);
            }
        }

        @Override
        public void draw(Canvas canvas) {
            if (cache != null) canvas.drawBitmap(cache, null, getBounds(), p);
            else canvas.drawColor(look.sky);
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {}

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
