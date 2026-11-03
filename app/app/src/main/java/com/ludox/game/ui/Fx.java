package com.ludox.game.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.ludox.game.audio.Sfx;

/**
 * Hand-drawn "3D" game UI pieces for the home screens: chunky tiles that press down,
 * glossy pawns, gold title text, a die and a small board. Everything is drawn with
 * gradients and highlights, so the app needs no image assets.
 */
public final class Fx {
    private Fx() {}

    // Palette (purple game theme).
    public static final int BG_TOP = 0xff5b21b6, BG_BOTTOM = 0xff2a0b63;
    public static final int BAR = 0xff7c3aed, BAR_EDGE = 0xff4c1d95;
    public static final int[] GREEN = {0xff7ee04a, 0xff36a914, 0xff1f6d0a};
    public static final int[] RED = {0xffff6b6b, 0xffd61f2c, 0xff8a0d16};
    public static final int[] ORANGE = {0xffffa25e, 0xffef5a24, 0xff9c3410};
    public static final int[] BLUE = {0xff4fd8ff, 0xff1b8fe8, 0xff0b5a9e};
    public static final int[] YELLOW = {0xffffe27a, 0xfff5b400, 0xffa66f00};
    public static final int[] PURPLE = {0xff9f6bff, 0xff6d35e0, 0xff3e1a8a};
    public static final int[] PURPLE_DARK = {0xff5b2bb5, 0xff3d1885, 0xff26104f};

    static float dp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    /**
     * The purple screen background with a soft glow and a faint diamond pattern. It is
     * painted once into a bitmap per size, so scrolling and redraws stay cheap.
     */
    public static Drawable background(Context c) {
        return new Drawable() {
            final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            android.graphics.Bitmap cache;

            @Override
            protected void onBoundsChange(android.graphics.Rect b) {
                if (b.width() <= 0 || b.height() <= 0) return;
                if (cache != null && cache.getWidth() == b.width() && cache.getHeight() == b.height()) return;
                // Half resolution is plenty for a soft background.
                int w = Math.max(1, b.width() / 2), h = Math.max(1, b.height() / 2);
                cache = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565);
                Canvas cv = new Canvas(cache);
                Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
                q.setShader(new LinearGradient(0, 0, 0, h, new int[]{BG_TOP, 0xff4316a0, BG_BOTTOM}, null, Shader.TileMode.CLAMP));
                cv.drawRect(0, 0, w, h, q);
                q.setShader(new RadialGradient(w / 2f, h * 0.3f, Math.max(w, h) * 0.6f, 0x33ffffff, 0x00ffffff, Shader.TileMode.CLAMP));
                cv.drawRect(0, 0, w, h, q);
                q.setShader(null);
                q.setStyle(Paint.Style.STROKE);
                q.setStrokeWidth(dp(c, 0.5f));
                q.setColor(0x12ffffff);
                float s = dp(c, 17);
                Path d = new Path();
                int row = 0;
                for (float y = -s; y < h + s; y += s, row++) {
                    for (float x = row % 2 == 0 ? 0 : s / 2; x < w + s; x += s) {
                        d.moveTo(x, y - s * 0.3f);
                        d.lineTo(x + s * 0.3f, y);
                        d.lineTo(x, y + s * 0.3f);
                        d.lineTo(x - s * 0.3f, y);
                        d.close();
                    }
                }
                cv.drawPath(d, q);
            }

            @Override
            public void draw(Canvas canvas) {
                if (cache != null) canvas.drawBitmap(cache, null, getBounds(), p);
            }

            @Override
            public void setAlpha(int alpha) {}

            @Override
            public void setColorFilter(android.graphics.ColorFilter cf) {}

            @Override
            public int getOpacity() {
                return android.graphics.PixelFormat.OPAQUE;
            }
        };
    }

    public static final int[] PANEL = {0xff5a32b8, 0xff43208f, 0xff26104f};
    public static final int[] FIELD = {0xff2a1162, 0xff341776};

    /**
     * A 3D bevel background for any view: darker side under a gradient face, gloss and rim
     * light. When the view is pressed the side shrinks; pair with {@link #pressMotion} so
     * the whole view (text included) sinks. Shaders are rebuilt only on size changes.
     */
    public static final class Bevel extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] colors;
        private final float radius, depth;
        private final boolean pressable;
        private boolean pressed;
        private Shader faceShader, glossShader;

        public Bevel(Context c, int[] colors, float radiusDp, float depthDp, boolean pressable) {
            this.colors = colors;
            this.radius = dp(c, radiusDp);
            this.depth = dp(c, depthDp);
            this.pressable = pressable;
        }

        public float depth() {
            return depth;
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect b) {
            float faceH = b.height() - depth;
            faceShader = new LinearGradient(0, b.top, 0, b.top + faceH, colors[0], colors[1], Shader.TileMode.CLAMP);
            glossShader = new LinearGradient(0, b.top, 0, b.top + Math.min(faceH * 0.5f, dp1 * 56), 0x5cffffff, 0x05ffffff, Shader.TileMode.CLAMP);
        }

        @Override
        public boolean isStateful() {
            return pressable;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            boolean now = false;
            for (int s : state) if (s == android.R.attr.state_pressed) now = true;
            if (now == pressed) return false;
            pressed = now;
            invalidateSelf();
            return true;
        }

        @Override
        public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            if (faceShader == null) onBoundsChange(b);
            float side = pressed ? depth * 0.35f : depth;
            float faceBottom = b.bottom - depth;
            p.setShader(null);
            p.setColor(colors[2]);
            c.drawRoundRect(b.left, faceBottom - radius, b.right, faceBottom + side, radius, radius, p);
            p.setColor(Color.BLACK);
            p.setShader(faceShader);
            c.drawRoundRect(b.left, b.top, b.right, faceBottom, radius, radius, p);
            p.setShader(glossShader);
            float in = Math.min(radius * 0.35f, dp1 * 4);
            c.drawRoundRect(b.left + in, b.top + in * 0.6f, b.right - in, b.top + Math.min((faceBottom - b.top) * 0.5f, dp1 * 56), radius * 0.8f, radius * 0.8f, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp1 * 1.4f);
            p.setColor(0x55ffffff);
            float s = p.getStrokeWidth() / 2;
            c.drawRoundRect(b.left + s, b.top + s, b.right - s, faceBottom - s, radius, radius, p);
            p.setStyle(Paint.Style.FILL);
        }

        private final float dp1 = android.content.res.Resources.getSystem().getDisplayMetrics().density;

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {}

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** Make a view sink by `px` while pressed (text and all), with a quick spring back. */
    public static void pressMotion(View v, float px) {
        android.animation.StateListAnimator sla = new android.animation.StateListAnimator();
        android.animation.ObjectAnimator down = android.animation.ObjectAnimator.ofFloat(v, "translationY", px).setDuration(60);
        android.animation.ObjectAnimator up = android.animation.ObjectAnimator.ofFloat(v, "translationY", 0).setDuration(140);
        sla.addState(new int[]{android.R.attr.state_pressed}, down);
        sla.addState(new int[]{}, up);
        v.setStateListAnimator(sla);
    }

    /** Give a view the 3D bevel look; returns the bevel depth so callers can pad for it. */
    public static int bevel(View v, int[] colors, float radiusDp, float depthDp, boolean pressable) {
        Bevel b = new Bevel(v.getContext(), colors, radiusDp, depthDp, pressable);
        v.setBackground(b);
        if (pressable) pressMotion(v, b.depth() * 0.65f);
        return (int) b.depth();
    }

    /**
     * A chunky 3D tile: a darker "side" below the face, a gradient face with gloss and a rim
     * light. It sinks when pressed (children move with it). colors = {top, bottom, side}.
     */
    public static class Tile extends FrameLayout {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] colors;
        private final float depth, radius;
        private float press;

        public Tile(Context c, int[] colors, float radiusDp, float depthDp) {
            super(c);
            this.colors = colors;
            this.depth = dp(c, depthDp);
            this.radius = dp(c, radiusDp);
            setWillNotDraw(false);
            setClickable(true);
            setPadding(0, 0, 0, (int) depth);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            float next = isPressed() ? depth * 0.75f : 0f;
            if (next != press) {
                press = next;
                invalidate();
            }
        }

        @Override
        public boolean performClick() {
            Sfx s = Sfx.get();
            if (s != null) s.play(Sfx.S.CLICK);
            return super.performClick();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), faceH = h - depth;
            p.setShader(null);
            // Contact shadow.
            p.setColor(0x40000000);
            canvas.drawRoundRect(new RectF(dp(getContext(), 2), depth * 1.4f, w - dp(getContext(), 2), h + dp(getContext(), 2)), radius, radius, p);
            // Side.
            p.setColor(colors[2]);
            canvas.drawRoundRect(new RectF(0, depth, w, h), radius, radius, p);
            // Face.
            RectF face = new RectF(0, press, w, faceH + press);
            p.setShader(new LinearGradient(0, face.top, 0, face.bottom, colors[0], colors[1], Shader.TileMode.CLAMP));
            canvas.drawRoundRect(face, radius, radius, p);
            // Gloss on the upper half.
            float inset = dp(getContext(), 4);
            RectF gloss = new RectF(inset, face.top + inset * 0.7f, w - inset, face.top + Math.min(faceH * 0.48f, dp(getContext(), 64)));
            p.setShader(new LinearGradient(0, gloss.top, 0, gloss.bottom, 0x70ffffff, 0x08ffffff, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(gloss, radius * 0.75f, radius * 0.75f, p);
            // Rim light.
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(getContext(), 1.6f));
            p.setColor(0x66ffffff);
            float s = p.getStrokeWidth() / 2;
            canvas.drawRoundRect(new RectF(s, face.top + s, w - s, face.bottom - s), radius, radius, p);
            p.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            canvas.save();
            canvas.translate(0, press);
            super.dispatchDraw(canvas);
            canvas.restore();
        }
    }

    /** A row of glossy pawns (front ones bigger), used as tile artwork. */
    public static class Pawns extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] pawnColors;

        public Pawns(Context c, int... argb) {
            super(c);
            pawnColors = argb;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int n = pawnColors.length;
            float w = getWidth(), h = getHeight();
            for (int i = 0; i < n; i++) {
                // Back row first: alternate rows for 4 pawns.
                boolean back = n > 2 && i < n / 2;
                float size = h * (back ? 0.62f : 0.78f);
                float slot = w / (back ? Math.max(1, n / 2) : Math.max(1, n - (n > 2 ? n / 2 : 0)));
                int idx = back ? i : i - (n > 2 ? n / 2 : 0);
                float cx = slot * (idx + 0.5f) + (back ? 0 : 0);
                float bottom = back ? h * 0.66f : h;
                drawPawn(canvas, cx, bottom, size, pawnColors[i]);
            }
        }

        private void drawPawn(Canvas c, float cx, float bottom, float size, int color) {
            float r = size * 0.24f;
            // Shadow.
            p.setShader(null);
            p.setColor(0x55000000);
            c.drawOval(new RectF(cx - size * 0.34f, bottom - size * 0.09f, cx + size * 0.34f, bottom + size * 0.02f), p);
            // Shaders are multiplied by the paint's alpha: back to opaque after the shadow.
            p.setColor(Color.BLACK);
            int light = blend(color, Color.WHITE, 0.45f), dark = blend(color, Color.BLACK, 0.45f);
            // Base.
            RectF base = new RectF(cx - size * 0.32f, bottom - size * 0.2f, cx + size * 0.32f, bottom - size * 0.02f);
            p.setShader(new LinearGradient(base.left, 0, base.right, 0, new int[]{light, color, dark}, null, Shader.TileMode.CLAMP));
            c.drawOval(base, p);
            // Body.
            Path body = new Path();
            body.moveTo(cx - size * 0.26f, bottom - size * 0.12f);
            body.quadTo(cx - size * 0.13f, bottom - size * 0.45f, cx - size * 0.1f, bottom - size * 0.62f);
            body.lineTo(cx + size * 0.1f, bottom - size * 0.62f);
            body.quadTo(cx + size * 0.13f, bottom - size * 0.45f, cx + size * 0.26f, bottom - size * 0.12f);
            body.close();
            p.setShader(new LinearGradient(cx - size * 0.26f, 0, cx + size * 0.26f, 0, new int[]{light, color, dark}, new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
            c.drawPath(body, p);
            // Collar.
            p.setShader(new LinearGradient(cx - size * 0.18f, 0, cx + size * 0.18f, 0, new int[]{light, color, dark}, null, Shader.TileMode.CLAMP));
            c.drawOval(new RectF(cx - size * 0.18f, bottom - size * 0.68f, cx + size * 0.18f, bottom - size * 0.58f), p);
            // Head.
            float hy = bottom - size * 0.78f;
            p.setShader(new RadialGradient(cx - r * 0.35f, hy - r * 0.4f, r * 1.3f, new int[]{Color.WHITE, light, color, dark}, new float[]{0, 0.18f, 0.6f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, hy, r, p);
            p.setShader(null);
        }
    }

    static int blend(int a, int b, float t) {
        return Color.rgb(
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** Big gold text with a dark outline and drop shadow (titles, tile labels). */
    public static class GoldText extends TextView {
        private final int[] fill;
        private final int stroke;

        public GoldText(Context c, CharSequence text, float sp) {
            this(c, text, sp, new int[]{0xfffff7b0, 0xffffd23f, 0xffff9f0a}, 0xff6b2b00);
        }

        /** White text with a coloured outline, like game buttons. */
        public static GoldText label(Context c, CharSequence text, float sp, int outline) {
            return new GoldText(c, text, sp, new int[]{Color.WHITE, Color.WHITE, 0xffeef2ff}, outline);
        }

        public GoldText(Context c, CharSequence text, float sp, int[] fill, int stroke) {
            super(c);
            this.fill = fill;
            this.stroke = stroke;
            setText(text);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC));
            setTextColor(Color.WHITE);
            setShadowLayer(dp(c, 3), 0, dp(c, 2.5f), 0x99000000);
            setIncludeFontPadding(false);
            int pad = (int) dp(c, 4);
            setPadding(pad, pad / 2, pad, pad);
        }

        /**
         * Draws the text layout directly (outline pass, then gradient fill). Changing the text
         * colour inside onDraw would invalidate the view and redraw it forever.
         */
        @Override
        protected void onDraw(Canvas canvas) {
            android.text.Layout layout = getLayout();
            if (layout == null) {
                super.onDraw(canvas);
                return;
            }
            Paint paint = getPaint();
            canvas.save();
            float vOffset = getExtendedPaddingTop() + Math.max(0, (getHeight() - getExtendedPaddingTop() - getExtendedPaddingBottom() - layout.getHeight()) / 2f);
            canvas.translate(getCompoundPaddingLeft(), vOffset);
            // Outline (with the drop shadow).
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(getTextSize() * 0.16f);
            paint.setColor(stroke);
            paint.setShadowLayer(getShadowRadius(), getShadowDx(), getShadowDy(), getShadowColor());
            layout.draw(canvas);
            // Gradient fill.
            paint.clearShadowLayer();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setShader(new LinearGradient(0, 0, 0, layout.getHeight(), fill, null, Shader.TileMode.CLAMP));
            layout.draw(canvas);
            paint.setShader(null);
            canvas.restore();
        }
    }

    /** A white die drawn in three-quarter view, with pips on the top and two sides. */
    public static class Die extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Die(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float s = Math.min(getWidth(), getHeight());
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float a = s * 0.42f;
            // Points of an isometric cube.
            float[] top = {cx, cy - a, cx + a * 0.87f, cy - a * 0.5f, cx, cy, cx - a * 0.87f, cy - a * 0.5f};
            float[] left = {cx - a * 0.87f, cy - a * 0.5f, cx, cy, cx, cy + a, cx - a * 0.87f, cy + a * 0.5f};
            float[] right = {cx, cy, cx + a * 0.87f, cy - a * 0.5f, cx + a * 0.87f, cy + a * 0.5f, cx, cy + a};
            p.setColor(0x55000000);
            canvas.drawOval(new RectF(cx - a * 0.9f, cy + a * 0.75f, cx + a * 0.9f, cy + a * 1.15f), p);
            face(canvas, left, 0xffd6d9e6);
            face(canvas, right, 0xffb9bdd0);
            face(canvas, top, 0xffffffff);
            p.setColor(0xffe53945);
            canvas.drawCircle(cx, cy - a * 0.5f, a * 0.13f, p); // 1 on top
            p.setColor(0xff1b1f3b);
            // 2 on the left face, 3 on the right face.
            pip(canvas, left, 0.3f, 0.3f, a);
            pip(canvas, left, 0.7f, 0.7f, a);
            pip(canvas, right, 0.25f, 0.25f, a);
            pip(canvas, right, 0.5f, 0.5f, a);
            pip(canvas, right, 0.75f, 0.75f, a);
        }

        private void face(Canvas c, float[] q, int color) {
            Path path = new Path();
            path.moveTo(q[0], q[1]);
            for (int i = 2; i < 8; i += 2) path.lineTo(q[i], q[i + 1]);
            path.close();
            p.setColor(color);
            c.drawPath(path, p);
        }

        private void pip(Canvas c, float[] q, float u, float v, float a) {
            // Bilinear point inside the quad (corners 0..3).
            float x = q[0] + (q[2] - q[0]) * u + (q[6] - q[0]) * v;
            float y = q[1] + (q[3] - q[1]) * u + (q[7] - q[1]) * v;
            c.drawCircle(x, y, a * 0.085f, p);
        }
    }

    /**
     * Two smiling friends sitting behind a small Ludo board, with an invite bubble ("+")
     * between them: the art for "play with friends".
     */
    public static class Friends extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Friends(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float s = Math.min(w, h * 1.25f);
            float cx = w / 2f;
            float top = (h - s / 1.25f) / 2f;
            // Friends first (the board sits in front of them).
            friend(canvas, cx - s * 0.22f, top + s * 0.28f, s * 0.3f, 0xff2f7cf0, 0xff5b3417, false);
            friend(canvas, cx + s * 0.22f, top + s * 0.28f, s * 0.3f, 0xffe53945, 0xff1b1f3b, true);
            board(canvas, cx, top + s * 0.62f, s * 0.36f, s * 0.13f);
            bubble(canvas, cx, top + s * 0.1f, s * 0.11f);
        }

        /** A friend: shoulders in a shirt colour, a round smiling face and hair. */
        private void friend(Canvas c, float cx, float headY, float size, int shirt, int hair, boolean bow) {
            float r = size * 0.36f;
            int light = blend(shirt, Color.WHITE, 0.4f), dark = blend(shirt, Color.BLACK, 0.4f);
            // Shoulders.
            RectF body = new RectF(cx - size * 0.62f, headY + r * 0.75f, cx + size * 0.62f, headY + r * 0.75f + size * 1.1f);
            p.setShader(new LinearGradient(body.left, 0, body.right, 0, new int[]{light, shirt, dark}, new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
            c.drawRoundRect(body, size * 0.5f, size * 0.5f, p);
            // Collar.
            p.setShader(null);
            p.setColor(0xffffffff);
            Path v = new Path();
            v.moveTo(cx - r * 0.5f, headY + r * 0.8f);
            v.lineTo(cx, headY + r * 1.35f);
            v.lineTo(cx + r * 0.5f, headY + r * 0.8f);
            v.close();
            c.drawPath(v, p);
            // Face.
            p.setShader(new RadialGradient(cx - r * 0.3f, headY - r * 0.35f, r * 1.4f, new int[]{0xffffe3c8, 0xfff5c08f, 0xffc98a55}, new float[]{0, 0.6f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, headY, r, p);
            p.setShader(null);
            // Hair: a cap over the top of the head.
            p.setColor(hair);
            c.drawArc(new RectF(cx - r * 1.04f, headY - r * 1.08f, cx + r * 1.04f, headY + r * 0.6f), 180, 180, true, p);
            if (bow) {
                p.setColor(0xffff4d9d);
                c.drawCircle(cx + r * 0.75f, headY - r * 0.7f, r * 0.24f, p);
                c.drawCircle(cx + r * 1.05f, headY - r * 0.55f, r * 0.24f, p);
            }
            // Eyes and a big smile.
            p.setColor(0xff2b1a10);
            c.drawCircle(cx - r * 0.33f, headY + r * 0.05f, r * 0.11f, p);
            c.drawCircle(cx + r * 0.33f, headY + r * 0.05f, r * 0.11f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(new RectF(cx - r * 0.42f, headY - r * 0.05f, cx + r * 0.42f, headY + r * 0.6f), 20, 140, false, p);
            p.setStyle(Paint.Style.FILL);
            // Rosy cheeks.
            p.setColor(0x55ff6b6b);
            c.drawCircle(cx - r * 0.6f, headY + r * 0.3f, r * 0.16f, p);
            c.drawCircle(cx + r * 0.6f, headY + r * 0.3f, r * 0.16f, p);
        }

        /** The board between them, seen at an angle, with a die on it. */
        private void board(Canvas c, float cx, float cy, float rx, float ry) {
            float depth = ry * 0.35f;
            p.setColor(0x55000000);
            c.drawOval(new RectF(cx - rx, cy + ry * 0.4f, cx + rx, cy + ry + depth * 1.6f), p);
            p.setShader(new LinearGradient(0, cy, 0, cy + ry + depth, 0xffffc94a, 0xffb36b00, Shader.TileMode.CLAMP));
            c.drawPath(MiniBoard.diamond(cx, cy + depth, rx, ry), p);
            p.setShader(null);
            p.setColor(0xffffd969);
            c.drawPath(MiniBoard.diamond(cx, cy, rx, ry), p);
            float k = 0.84f;
            int[] cols = {0xffe53945, 0xff22b46b, 0xfff6bf26, 0xff2f7cf0};
            float[][] pts = {{cx, cy - ry * k}, {cx + rx * k, cy}, {cx, cy + ry * k}, {cx - rx * k, cy}};
            for (int i = 0; i < 4; i++) {
                Path q = new Path();
                q.moveTo(cx, cy);
                q.lineTo(pts[i][0], pts[i][1]);
                q.lineTo(pts[(i + 1) % 4][0], pts[(i + 1) % 4][1]);
                q.close();
                p.setColor(cols[i]);
                c.drawPath(q, p);
            }
            // A white die in the middle.
            float d = ry * 0.42f;
            p.setColor(0xffffffff);
            c.drawRoundRect(new RectF(cx - d, cy - d * 1.1f, cx + d, cy + d * 0.9f), d * 0.35f, d * 0.35f, p);
            p.setColor(0xff1b1f3b);
            c.drawCircle(cx - d * 0.45f, cy - d * 0.55f, d * 0.18f, p);
            c.drawCircle(cx, cy - d * 0.1f, d * 0.18f, p);
            c.drawCircle(cx + d * 0.45f, cy + d * 0.35f, d * 0.18f, p);
        }

        /** A green "+" bubble: invite a friend. */
        private void bubble(Canvas c, float cx, float cy, float r) {
            p.setColor(0x55000000);
            c.drawCircle(cx, cy + r * 0.15f, r * 1.05f, p);
            p.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.4f, r * 1.4f, new int[]{0xffb8ff8a, 0xff36a914, 0xff1f6d0a}, new float[]{0, 0.6f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
            p.setShader(null);
            p.setColor(0xffffffff);
            float t = r * 0.22f, l = r * 0.58f;
            c.drawRoundRect(new RectF(cx - l, cy - t, cx + l, cy + t), t, t, p);
            c.drawRoundRect(new RectF(cx - t, cy - l, cx + t, cy + l), t, t, p);
        }
    }

    /** A small board seen from above at an angle: four coloured quarters on a gold rim. */
    public static class MiniBoard extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public MiniBoard(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2, cy = h * 0.42f, rx = w * 0.46f, ry = h * 0.38f;
            float depth = h * 0.12f;
            // Rim side.
            Path side = diamond(cx, cy + depth, rx, ry);
            p.setShader(new LinearGradient(0, cy, 0, cy + ry + depth, 0xffffc94a, 0xffb36b00, Shader.TileMode.CLAMP));
            canvas.drawPath(side, p);
            p.setShader(null);
            // Top: gold rim then four quarters.
            p.setColor(0xffffd969);
            canvas.drawPath(diamond(cx, cy, rx, ry), p);
            float k = 0.86f;
            int[] cols = {0xffe53945, 0xff22b46b, 0xfff6bf26, 0xff2f7cf0};
            float[][] pts = {{cx, cy - ry * k}, {cx + rx * k, cy}, {cx, cy + ry * k}, {cx - rx * k, cy}};
            for (int i = 0; i < 4; i++) {
                Path q = new Path();
                q.moveTo(cx, cy);
                q.lineTo(pts[i][0], pts[i][1]);
                q.lineTo(pts[(i + 1) % 4][0], pts[(i + 1) % 4][1]);
                q.close();
                p.setColor(cols[i]);
                canvas.drawPath(q, p);
            }
            p.setColor(0xccffffff);
            canvas.drawPath(diamond(cx, cy, rx * 0.32f, ry * 0.32f), p);
        }

        private static Path diamond(float cx, float cy, float rx, float ry) {
            Path d = new Path();
            d.moveTo(cx, cy - ry);
            d.lineTo(cx + rx, cy);
            d.lineTo(cx, cy + ry);
            d.lineTo(cx - rx, cy);
            d.close();
            return d;
        }
    }

    /** Round glossy icon badge (emoji on a 3D disc), for side buttons and the top bar. */
    public static Tile badge(Context c, String emoji, int[] colors, float sizeDp, View.OnClickListener onClick) {
        Tile t = new Tile(c, colors, sizeDp / 2, 4);
        TextView e = Ui.text(c, emoji, sizeDp * 0.42f, Color.WHITE);
        e.setGravity(android.view.Gravity.CENTER);
        t.addView(e, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        t.setOnClickListener(onClick);
        return t;
    }
}
