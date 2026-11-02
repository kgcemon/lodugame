package com.ludo3d.game.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Random;

/**
 * The dice box on a player card: a dark rounded box with a gold rim and a little pointer
 * toward the avatar, holding a real 3D die (a cube seen slightly from above). On a roll the
 * die jumps out of the box, grows and tumbles in 3D, then drops back in showing the value the
 * server rolled; the box keeps showing that last roll afterwards. When it is my turn to roll
 * the rim glows and a green arrow bounces above the box.
 */
final class DiceView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Random rng = new Random();
    /** The pointer sits on the side facing the avatar. */
    private final boolean pointLeft;

    /** The look of this player's die (bought in the dice shop). */
    private Skin skin = Skin.CLASSIC;
    /** Face shown (the last roll; 1 before anyone has rolled). */
    private int value = 1;
    /** Current player's box: bright gold rim and a white die; otherwise dimmed. */
    private boolean active;
    /** Mine to roll: pulsing rim and the bouncing arrow. */
    private boolean ready;
    private float glow;
    /** True while the die is out of the box (flying). */
    private boolean out;

    private ValueAnimator glowAnim;
    private View arrow;
    private View flyer;
    private ValueAnimator flight;

    DiceView(Context c, boolean pointLeft) {
        super(c);
        this.pointLeft = pointLeft;
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    void setSkin(Skin skin) {
        this.skin = skin != null ? skin : Skin.CLASSIC;
        invalidate();
    }

    void set(int value, boolean active, boolean ready) {
        if (!out && value >= 1 && value <= 6) this.value = value;
        this.active = active;
        setReady(ready);
        invalidate();
    }

    /** Glow and show the arrow while it's mine to roll. */
    void setReady(boolean ready) {
        if (this.ready == ready) return;
        this.ready = ready;
        if (ready) startGlow();
        else stopGlow();
        post(this::syncArrow);
        invalidate();
    }

    // ---------------------------------------------------------------- roll

    /** Throw: the die pops out, grows and tumbles for `ms`, then lands on `result`. */
    void roll(int result, long ms) {
        if (!isAttachedToWindow() || getWidth() == 0) {
            // The card was just rebuilt and isn't laid out yet: throw as soon as it is,
            // for whatever is left of the roll, instead of silently skipping the animation.
            value = result;
            invalidate();
            long until = android.os.SystemClock.uptimeMillis() + ms;
            addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    removeOnLayoutChangeListener(this);
                    long left = until - android.os.SystemClock.uptimeMillis();
                    if (left > 150) post(() -> roll(result, left));
                }
            });
            return;
        }
        ViewGroup overlay = overlay();
        if (overlay == null) {
            value = result;
            invalidate();
            return;
        }
        if (flight != null) flight.cancel();
        removeFlyer();
        out = true;
        active = true;
        invalidate();
        removeArrow();

        int[] loc = new int[2];
        int[] oloc = new int[2];
        getLocationInWindow(loc);
        overlay.getLocationInWindow(oloc);
        float box = boxSize();
        float size = box * 1.0f;
        float cx = loc[0] - oloc[0] + boxLeft() + box / 2f;
        float cy = loc[1] - oloc[1] + getHeight() / 2f;

        Die d = new Die(getContext(), result, rng, skin);
        // Placed with margins: translation and scale belong to the throw.
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams((int) size, (int) size);
        dlp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        dlp.leftMargin = (int) (cx - size / 2f);
        dlp.topMargin = (int) (cy - size / 2f);
        overlay.addView(d, dlp);
        flyer = d;

        // Out toward the board: cards below it throw up, cards above throw down; a little
        // sideways, away from the avatar.
        int[] screen = new int[2];
        getLocationOnScreen(screen);
        boolean belowMiddle = screen[1] > getResources().getDisplayMetrics().heightPixels / 2;
        float lift = (belowMiddle ? -1 : 1) * box * 0.9f;
        float side = (pointLeft ? 1 : -1) * box * 0.35f;
        flight = ValueAnimator.ofFloat(0f, 1f);
        flight.setDuration(Math.max(500, ms));
        flight.setInterpolator(new LinearInterpolator());
        flight.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            // Jump out fast, hang big while tumbling, drop back into the box.
            float arc = (float) Math.sin(Math.PI * Math.pow(t, 0.8));
            float s = 0.7f + 1.1f * arc;
            d.setScaleX(s);
            d.setScaleY(s);
            d.setTranslationX(side * arc);
            d.setTranslationY(lift * arc);
            d.setProgress(t);
        });
        flight.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                removeFlyer();
                out = false;
                value = result;
                invalidate();
                if (cancelled) return;
                // Landing thump in the box.
                setScaleX(1.16f);
                setScaleY(0.88f);
                animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(new OvershootInterpolator(3f)).start();
                post(DiceView.this::syncArrow);
            }
        });
        flight.start();
    }

    private ViewGroup overlay() {
        View r = getRootView();
        return r instanceof FrameLayout ? (ViewGroup) r : null;
    }

    private void removeFlyer() {
        if (flyer != null) detachLater(flyer);
        flyer = null;
    }

    /**
     * Take a view that lives in the window's overlay (the flying die, the arrow) off screen.
     * It is hidden now and removed on the next loop: this also runs while the window is
     * tearing down its views (leaving a match), and removing a sibling in the middle of that
     * crashes Android's detach pass.
     */
    private static void detachLater(View v) {
        v.setVisibility(GONE);
        com.ludo3d.game.LudoApp.MAIN.post(() -> {
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        });
    }

    // ---------------------------------------------------------------- my-turn glow and arrow

    private void startGlow() {
        stopGlow();
        glowAnim = ValueAnimator.ofFloat(0f, 1f);
        glowAnim.setDuration(600);
        glowAnim.setRepeatCount(ValueAnimator.INFINITE);
        glowAnim.setRepeatMode(ValueAnimator.REVERSE);
        glowAnim.addUpdateListener(a -> {
            glow = (float) a.getAnimatedValue();
            invalidate();
            placeArrow();
        });
        glowAnim.start();
    }

    private void stopGlow() {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = null;
        glow = 0;
    }

    private void syncArrow() {
        if (!ready || out || !isAttachedToWindow()) {
            removeArrow();
            return;
        }
        if (arrow != null) return;
        ViewGroup overlay = overlay();
        if (overlay == null || getWidth() == 0) return;
        float box = boxSize();
        int w = (int) (box * 0.5f), h = (int) (box * 0.62f);
        Arrow a = new Arrow(getContext());
        overlay.addView(a, new FrameLayout.LayoutParams(w, h));
        arrow = a;
        placeArrow();
        ObjectAnimator bob = ObjectAnimator.ofFloat(a, "translationY", 0f, -box * 0.22f);
        bob.setDuration(420);
        bob.setRepeatCount(ValueAnimator.INFINITE);
        bob.setRepeatMode(ValueAnimator.REVERSE);
        bob.start();
        a.setTag(bob);
    }

    /** Keep the arrow just above the box (the card may still be moving into place). */
    private void placeArrow() {
        ViewGroup overlay = overlay();
        if (arrow == null || overlay == null) return;
        int[] loc = new int[2];
        int[] oloc = new int[2];
        getLocationInWindow(loc);
        overlay.getLocationInWindow(oloc);
        float box = boxSize();
        // Placed with margins: translationY belongs to the bounce.
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) arrow.getLayoutParams();
        int x = (int) (loc[0] - oloc[0] + boxLeft() + box / 2f - lp.width / 2f);
        int y = (int) (loc[1] - oloc[1] + (getHeight() - box) / 2f - lp.height - box * 0.05f);
        if (lp.leftMargin != x || lp.topMargin != y) {
            lp.leftMargin = x;
            lp.topMargin = y;
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            arrow.setLayoutParams(lp);
        }
    }

    private void removeArrow() {
        if (arrow == null) return;
        Object bob = arrow.getTag();
        if (bob instanceof Animator) ((Animator) bob).cancel();
        detachLater(arrow);
        arrow = null;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (ready) {
            startGlow();
            post(this::syncArrow);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopGlow();
        removeArrow();
        if (flight != null) flight.cancel();
        removeFlyer();
    }

    // ---------------------------------------------------------------- drawing

    private float boxSize() {
        return Math.min(getHeight(), getWidth() * 0.86f);
    }

    /** Left edge of the box (the pointer takes the rest of the width). */
    private float boxLeft() {
        return pointLeft ? getWidth() - boxSize() : 0;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float box = boxSize();
        float x0 = boxLeft(), y0 = (h - box) / 2f;
        float r = box * 0.22f;

        // Pointer toward the avatar.
        int fill = active ? 0xff3a1d6e : 0xff2a1450;
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ready ? 0xffffe14d : active ? 0xffffb547 : 0xff8a6a3a);
        path.reset();
        float py = h / 2f, ps = box * 0.17f;
        if (pointLeft) {
            path.moveTo(x0 + 1, py - ps);
            path.lineTo(0, py);
            path.lineTo(x0 + 1, py + ps);
        } else {
            path.moveTo(x0 + box - 1, py - ps);
            path.lineTo(w, py);
            path.lineTo(x0 + box - 1, py + ps);
        }
        path.close();
        canvas.drawPath(path, paint);

        // Outer glow while it's mine to roll.
        if (ready && glow > 0) {
            paint.setColor(0xffffe14d);
            paint.setAlpha((int) (70 + 110 * glow));
            canvas.drawRoundRect(x0 - box * 0.05f, y0 - box * 0.05f, x0 + box * 1.05f, y0 + box * 1.05f, r * 1.2f, r * 1.2f, paint);
            paint.setAlpha(255);
        }

        // Box with a gold rim.
        paint.setColor(ready ? 0xffffe14d : active ? 0xffffb547 : 0xff8a6a3a);
        canvas.drawRoundRect(x0, y0, x0 + box, y0 + box, r, r, paint);
        float rim = box * (ready ? 0.075f : 0.06f);
        paint.setShader(new LinearGradient(0, y0, 0, y0 + box, Fx.blend(fill, Color.BLACK, 0.25f), fill, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(x0 + rim, y0 + rim, x0 + box - rim, y0 + box - rim, r * 0.8f, r * 0.8f, paint);
        paint.setShader(null);

        if (out) return;
        float d = box * 0.64f;
        drawDie(canvas, paint, path, x0 + (box - d) / 2f, y0 + (box - d) / 2f - box * 0.02f, d, value, active || ready, skin);
    }

    private static final float[] AT = {0.27f, 0.5f, 0.73f};
    private static final int[][][] FACE_PIPS = {
            {},
            {{1, 1}},
            {{2, 0}, {0, 2}},
            {{2, 0}, {1, 1}, {0, 2}},
            {{0, 0}, {2, 0}, {0, 2}, {2, 2}},
            {{0, 0}, {2, 0}, {1, 1}, {0, 2}, {2, 2}},
            {{0, 0}, {2, 0}, {0, 1}, {2, 1}, {0, 2}, {2, 2}},
    };

    /**
     * A resting die seen face-on, fitted in the square at x, y: soft drop shadow, a rounded
     * white face with a gentle top-lit gradient and a thin darker rim, and slightly recessed
     * pips. Its outline matches the last frame of the 3D throw, so the landing is seamless.
     */
    static void drawDie(Canvas canvas, Paint paint, Path path, float x, float y, float s, int value, boolean lit) {
        drawDie(canvas, paint, path, x, y, s, value, lit, Skin.CLASSIC);
    }

    static void drawDie(Canvas canvas, Paint paint, Path path, float x, float y, float s, int value, boolean lit, Skin skin) {
        float r = s * 0.22f;
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        // Shadow.
        paint.setColor(0x40000000);
        canvas.drawRoundRect(x + s * 0.03f, y + s * 0.07f, x + s * 1.03f, y + s * 1.07f, r, r, paint);
        // Rim, then the face.
        int top = skin.shade(skin.face, lit);
        int bottom = skin.shade(skin.faceBottom, lit);
        paint.setColor(skin.shade(skin.rim, lit));
        canvas.drawRoundRect(x, y, x + s, y + s, r, r, paint);
        float in = s * 0.035f;
        paint.setShader(new LinearGradient(0, y, 0, y + s, top, bottom, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(x + in, y + in, x + s - in, y + s - in * 1.6f, r * 0.85f, r * 0.85f, paint);
        paint.setShader(null);
        // A soft highlight across the upper part of the face.
        paint.setColor(0x33ffffff);
        canvas.drawRoundRect(x + s * 0.12f, y + s * 0.08f, x + s * 0.88f, y + s * 0.4f, r * 0.6f, r * 0.6f, paint);
        if (value < 1 || value > 6) return;
        float pr = s * (value == 1 ? 0.12f : 0.088f);
        int pip = skin.shade(skin.pip, lit);
        for (int[] p : FACE_PIPS[value]) {
            float cx = x + s * AT[p[0]], cy = y + s * AT[p[1]];
            paint.setColor(0x55ffffff);
            canvas.drawCircle(cx, cy + pr * 0.28f, pr, paint);
            paint.setColor(value == 1 ? skin.shade(skin.one, lit) : pip);
            canvas.drawCircle(cx, cy, pr, paint);
        }
    }

    // ---------------------------------------------------------------- the 3D cube

    /**
     * A die as a real cube: six faces with pips, rotated by a 3x3 matrix, drawn with
     * perspective and simple lighting (back faces culled, the rest painted far to near).
     * Opposite faces sum to seven.
     */
    static final class Cube {
        // Face normal, u axis, v axis (v points "up" on the face), and its value.
        private static final float[][] N = {{0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
        private static final float[][] U = {{1, 0, 0}, {-1, 0, 0}, {0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {1, 0, 0}};
        private static final float[][] V = {{0, 1, 0}, {0, 1, 0}, {0, 1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
        private static final int[] VAL = {1, 6, 2, 5, 3, 4};
        private static final float[][][] PIPS = {
                {},
                {{0, 0}},
                {{-0.5f, 0.5f}, {0.5f, -0.5f}},
                {{-0.5f, 0.5f}, {0, 0}, {0.5f, -0.5f}},
                {{-0.5f, 0.5f}, {0.5f, 0.5f}, {-0.5f, -0.5f}, {0.5f, -0.5f}},
                {{-0.5f, 0.5f}, {0.5f, 0.5f}, {0, 0}, {-0.5f, -0.5f}, {0.5f, -0.5f}},
                {{-0.5f, 0.5f}, {0.5f, 0.5f}, {-0.5f, 0}, {0.5f, 0}, {-0.5f, -0.5f}, {0.5f, -0.5f}},
        };
        private static final float[] LIGHT = norm(new float[]{-0.35f, 0.65f, 0.7f});
        /** Orientation that shows `value` on the front face (where a throw comes to rest). */
        static float[] rest(int value) {
            return face(value);
        }

        /** Rotation that turns the face with `value` toward the viewer (+z). */
        static float[] face(int value) {
            switch (value) {
                case 6: return rot(new float[]{0, 1, 0}, 180f);
                case 2: return rot(new float[]{0, 1, 0}, -90f);
                case 5: return rot(new float[]{0, 1, 0}, 90f);
                case 3: return rot(new float[]{1, 0, 0}, 90f);
                case 4: return rot(new float[]{1, 0, 0}, -90f);
                default: return rot(new float[]{0, 0, 1}, 0f);
            }
        }

        static void draw(Canvas c, Paint paint, Path path, float cx, float cy, float half, float[] m, boolean lit, Skin skin) {
            float persp = 9f;
            // Order the visible faces far to near.
            int[] order = new int[6];
            float[] depth = new float[6];
            int count = 0;
            for (int f = 0; f < 6; f++) {
                float[] n = apply(m, N[f]);
                if (n[2] <= 0.02f) continue;
                order[count] = f;
                depth[count] = n[2];
                count++;
            }
            for (int i = 1; i < count; i++) {
                for (int j = i; j > 0 && depth[j - 1] > depth[j]; j--) {
                    float td = depth[j];
                    depth[j] = depth[j - 1];
                    depth[j - 1] = td;
                    int to = order[j];
                    order[j] = order[j - 1];
                    order[j - 1] = to;
                }
            }
            int base = skin.shade(skin.face, lit);
            int pipColor = skin.shade(skin.pip, lit);
            float[] p = new float[2];
            for (int k = 0; k < count; k++) {
                int f = order[k];
                float[] n = apply(m, N[f]);
                float[] u = apply(m, U[f]);
                float[] v = apply(m, V[f]);
                float shade = 0.62f + 0.38f * Math.max(0f, dot(n, LIGHT));
                int col = Color.rgb((int) (Color.red(base) * shade), (int) (Color.green(base) * shade), (int) (Color.blue(base) * shade));
                path.reset();
                float[][] corners = {{1, 1}, {-1, 1}, {-1, -1}, {1, -1}};
                for (int i = 0; i < 4; i++) {
                    project(n, u, v, corners[i][0] * 0.94f, corners[i][1] * 0.94f, 1f, cx, cy, half, persp, p);
                    if (i == 0) path.moveTo(p[0], p[1]);
                    else path.lineTo(p[0], p[1]);
                }
                path.close();
                // Fill plus a fat round-joined stroke of the same colour: rounded edges.
                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeWidth(half * 0.22f);
                paint.setColor(col);
                c.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);

                // Pips, foreshortened with the face.
                int value = VAL[f];
                float pr = value == 1 ? 0.27f : 0.19f;
                for (float[] pip : PIPS[value]) {
                    path.reset();
                    for (int i = 0; i < 14; i++) {
                        double ang = Math.PI * 2 * i / 14;
                        project(n, u, v, pip[0] * 1.15f + (float) Math.cos(ang) * pr, pip[1] * 1.15f + (float) Math.sin(ang) * pr, 1.002f,
                                cx, cy, half, persp, p);
                        if (i == 0) path.moveTo(p[0], p[1]);
                        else path.lineTo(p[0], p[1]);
                    }
                    path.close();
                    paint.setColor(value == 1 ? skin.shade(skin.one, lit) : pipColor);
                    c.drawPath(path, paint);
                }
            }
        }

        /** Screen position of the point n*out + u*a + v*b on a cube of half-size 1. */
        private static void project(float[] n, float[] u, float[] v, float a, float b, float outward, float cx, float cy,
                                    float half, float persp, float[] out) {
            float x = n[0] * outward + u[0] * a + v[0] * b;
            float y = n[1] * outward + u[1] * a + v[1] * b;
            float z = n[2] * outward + u[2] * a + v[2] * b;
            float k = persp / (persp - z);
            out[0] = cx + x * half * k;
            out[1] = cy - y * half * k;
        }

        // ---- tiny 3x3 matrix helpers (row-major)

        static float[] rot(float[] axis, float deg) {
            float[] a = norm(axis);
            double r = Math.toRadians(deg);
            float c = (float) Math.cos(r), s = (float) Math.sin(r), t = 1 - c;
            float x = a[0], y = a[1], z = a[2];
            return new float[]{
                    t * x * x + c, t * x * y - s * z, t * x * z + s * y,
                    t * x * y + s * z, t * y * y + c, t * y * z - s * x,
                    t * x * z - s * y, t * y * z + s * x, t * z * z + c,
            };
        }

        static float[] mul(float[] a, float[] b) {
            float[] o = new float[9];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    o[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
                }
            }
            return o;
        }

        static float[] apply(float[] m, float[] v) {
            return new float[]{
                    m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
                    m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
                    m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
            };
        }

        private static float dot(float[] a, float[] b) {
            return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        }

        private static float[] norm(float[] v) {
            float l = (float) Math.sqrt(dot(v, v));
            return new float[]{v[0] / l, v[1] / l, v[2] / l};
        }
    }

    /**
     * The die in flight: it tumbles about a random axis, the spin dying away so it settles
     * exactly on the rolled face.
     */
    private static final class Die extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float[] rest;
        private final float[] axis;
        private final float turns;
        private final int result;
        private final Skin skin;
        private float[] m;
        private float t;

        Die(Context c, int result, Random rng, Skin skin) {
            super(c);
            this.skin = skin;
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            setElevation(40);
            this.result = result;
            rest = Cube.rest(result);
            axis = new float[]{rng.nextFloat() - 0.5f, rng.nextFloat() - 0.5f, rng.nextFloat() - 0.5f};
            if (Math.abs(axis[0]) + Math.abs(axis[1]) + Math.abs(axis[2]) < 0.2f) axis[0] = 1;
            turns = 900f + rng.nextFloat() * 360f;
            setProgress(0);
        }

        void setProgress(float t) {
            // Final orientation times a decaying spin: lands exactly on the result.
            this.t = t;
            float k = 1 - t;
            m = Cube.mul(rest, Cube.rot(axis, turns * k * k * k));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float s = Math.min(getWidth(), getHeight());
            if (t > 0.95f) {
                // Settled: the same face-on die the box shows, so the hand-over is invisible.
                float d = s * 0.64f;
                drawDie(canvas, paint, path, (getWidth() - d) / 2f, (getHeight() - d) / 2f, d, result, true, skin);
                return;
            }
            Cube.draw(canvas, paint, path, getWidth() / 2f, getHeight() / 2f, s * 0.3f, m, true, skin);
        }
    }

    /**
     * A dice skin's colours (shared/skins.ts, sent in /config as diceSkins): face gradient,
     * rim, pips and the single pip of the 1. Unlit dice (not this player's turn) are dimmed.
     */
    static final class Skin {
        static final Skin CLASSIC = new Skin("classic", "Classic", 0, 0xffffffff, 0xffe4e1ee, 0xffc9c5d8, 0xff23202e, 0xffd62839);
        private static final java.util.Map<String, Skin> CACHE = new java.util.HashMap<>();

        final String id, name;
        final int price, face, faceBottom, rim, pip, one;

        Skin(String id, String name, int price, int face, int faceBottom, int rim, int pip, int one) {
            this.id = id;
            this.name = name;
            this.price = price;
            this.face = face;
            this.faceBottom = faceBottom;
            this.rim = rim;
            this.pip = pip;
            this.one = one;
        }

        int shade(int color, boolean lit) {
            return lit ? color : Fx.blend(color, 0xff5f5b75, 0.4f);
        }

        static Skin from(JSONObject o) {
            try {
                return new Skin(o.getString("id"), o.optString("name", o.getString("id")), o.optInt("price"),
                        Color.parseColor(o.getString("face")), Color.parseColor(o.getString("faceBottom")),
                        Color.parseColor(o.getString("rim")), Color.parseColor(o.getString("pip")), Color.parseColor(o.getString("one")));
            } catch (Exception e) {
                return null;
            }
        }

        /** Every skin the server sells, in catalogue order. */
        static java.util.List<Skin> all() {
            java.util.List<Skin> out = new java.util.ArrayList<>();
            JSONObject cfg = com.ludo3d.game.LudoApp.get().config;
            JSONArray arr = cfg != null ? cfg.optJSONArray("diceSkins") : null;
            for (int i = 0; arr != null && i < arr.length(); i++) {
                Skin k = from(arr.optJSONObject(i) != null ? arr.optJSONObject(i) : new JSONObject());
                if (k != null) out.add(k);
            }
            if (out.isEmpty()) out.add(CLASSIC);
            return out;
        }

        /** The skin with this id, or the classic one for unknown ids (an older catalogue). */
        static Skin byId(String id) {
            if (id == null || id.isEmpty() || CLASSIC.id.equals(id)) return CLASSIC;
            synchronized (CACHE) {
                Skin k = CACHE.get(id);
                if (k != null) return k;
                for (Skin x : all()) CACHE.put(x.id, x);
                k = CACHE.get(id);
                return k != null ? k : CLASSIC;
            }
        }
    }

    /** The bouncing "tap here" arrow above my dice box. */
    private static final class Arrow extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Arrow(Context c) {
            super(c);
            setElevation(30);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            path.reset();
            path.moveTo(w * 0.3f, 0);
            path.lineTo(w * 0.7f, 0);
            path.lineTo(w * 0.7f, h * 0.5f);
            path.lineTo(w, h * 0.5f);
            path.lineTo(w * 0.5f, h);
            path.lineTo(0, h * 0.5f);
            path.lineTo(w * 0.3f, h * 0.5f);
            path.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, 0, 0, h, 0xffb6ff6b, 0xff2fb52a, Shader.TileMode.CLAMP));
            canvas.drawPath(path, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, w * 0.06f));
            paint.setColor(0xff1d6e14);
            canvas.drawPath(path, paint);
        }
    }
}
