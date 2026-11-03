package com.ludox.game.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.ludox.game.gl.DiceArt;

import java.util.Random;

/**
 * A big, live 3D die for the shop's preview. It turns slowly by itself; a finger (or mouse)
 * turns it any way, and it keeps spinning a little after you let go. {@link #showFace} turns
 * a face to you; {@link #testRoll} throws it: up, tumbling, and down with a bounce on a face
 * picked here (a test only: nothing is sent anywhere and no real roll happens).
 */
final class LiveDie extends View {
    interface OnRolled {
        void rolled(int value);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final DiceView.Skin skin;
    private final DiceArt.Look look;
    private final Random rnd = new Random();
    private final int aura;

    /** Current orientation (3x3). */
    private float[] m = DiceArt.mul(DiceArt.rot(new float[]{1, 0, 0}, 22f), DiceArt.mul(DiceArt.rot(new float[]{0, 1, 0}, -30f), DiceArt.face(5)));
    /** Spin left over from a flick (degrees per second about screen x and y). */
    private float spinX, spinY;
    private float lastX, lastY;
    private long lastMove;
    private boolean dragging;
    /** Auto-rotation resumes a moment after the last touch. */
    private long idleSince;
    private ValueAnimator ticker;
    private long lastFrame;
    private float sheen = 0f;
    private float glowPulse;

    // Throw state.
    private boolean rolling;
    private float rollT;
    private long rollStart;
    private float[] rollFrom, rollAxis;
    private float rollTurns;
    private int rollResult;
    private OnRolled onRolled;
    /** Turning to a chosen face. */
    private float[] turnFrom, turnTo;
    private long turnStart;

    LiveDie(Context c, DiceView.Skin skin) {
        super(c);
        this.skin = skin;
        this.look = skin.look();
        this.aura = look.aura != 0 ? look.aura : DiceLux.color(skin.rarity);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setContentDescription(skin.name + " dice, turning. Drag to turn it.");
    }

    void setOnRolled(OnRolled r) {
        onRolled = r;
    }

    boolean isRolling() {
        return rolling;
    }

    /** Throw the die for show; lands on a random face and calls back with it. */
    void testRoll() {
        if (rolling) return;
        rolling = true;
        rollT = 0;
        rollStart = System.nanoTime();
        rollResult = 1 + rnd.nextInt(6);
        rollAxis = new float[]{rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f};
        if (Math.abs(rollAxis[0]) + Math.abs(rollAxis[1]) + Math.abs(rollAxis[2]) < 0.2f) rollAxis[0] = 1;
        rollTurns = 900f + rnd.nextFloat() * 540f;
        // Land showing the result to the viewer, slightly tilted so the die still reads as 3D.
        rollFrom = DiceArt.mul(DiceArt.rot(new float[]{1, 0, 0}, 18f), DiceArt.mul(DiceArt.rot(new float[]{0, 1, 0}, -24f), DiceArt.face(rollResult)));
        spinX = spinY = 0;
        turnTo = null;
    }

    /** Turn the face showing `value` toward the viewer. */
    void showFace(int value) {
        if (rolling) return;
        turnFrom = m;
        turnTo = DiceArt.mul(DiceArt.rot(new float[]{1, 0, 0}, 14f), DiceArt.mul(DiceArt.rot(new float[]{0, 1, 0}, -16f), DiceArt.face(value)));
        turnStart = System.nanoTime();
        spinX = spinY = 0;
        idleSince = System.currentTimeMillis() + 2500;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ticker = ValueAnimator.ofFloat(0f, 1f);
        ticker.setDuration(1000);
        ticker.setRepeatCount(ValueAnimator.INFINITE);
        ticker.setInterpolator(new LinearInterpolator());
        ticker.addUpdateListener(a -> step());
        lastFrame = System.nanoTime();
        ticker.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (ticker != null) ticker.cancel();
    }

    private void step() {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        sheen = (sheen + dt * 0.35f) % 1.4f;
        glowPulse = (glowPulse + dt) % 6.283f;
        if (rolling) {
            rollT = Math.min(1f, (now - rollStart) / 1.15e9f);
            float k = 1 - rollT;
            m = DiceArt.mul(rollFrom, DiceArt.rot(rollAxis, rollTurns * k * k * k));
            if (rollT >= 1f) {
                rolling = false;
                m = rollFrom;
                idleSince = System.currentTimeMillis() + 2200;
                if (onRolled != null) onRolled.rolled(rollResult);
            }
        } else if (turnTo != null) {
            float t = Math.min(1f, (now - turnStart) / 0.45e9f);
            float e = 1 - (1 - t) * (1 - t) * (1 - t);
            m = slerpish(turnFrom, turnTo, e);
            if (t >= 1f) turnTo = null;
        } else if (!dragging) {
            if (Math.abs(spinX) + Math.abs(spinY) > 4f) {
                turn(spinX * dt, spinY * dt);
                spinX *= (1 - dt * 2.5f);
                spinY *= (1 - dt * 2.5f);
            } else if (System.currentTimeMillis() > idleSince) {
                // Slow showroom turn about the vertical axis.
                m = DiceArt.mul(DiceArt.rot(new float[]{0, 1, 0}, 28f * dt), m);
            }
        }
        invalidate();
    }

    /** Blend two orientations (good enough between nearby rotations; re-orthonormalised). */
    private static float[] slerpish(float[] a, float[] b, float t) {
        float[] o = new float[9];
        for (int i = 0; i < 9; i++) o[i] = a[i] + (b[i] - a[i]) * t;
        // Gram-Schmidt on the rows.
        float[] r0 = {o[0], o[1], o[2]}, r1 = {o[3], o[4], o[5]};
        normalize(r0);
        float d = r0[0] * r1[0] + r0[1] * r1[1] + r0[2] * r1[2];
        for (int i = 0; i < 3; i++) r1[i] -= r0[i] * d;
        normalize(r1);
        float[] r2 = {r0[1] * r1[2] - r0[2] * r1[1], r0[2] * r1[0] - r0[0] * r1[2], r0[0] * r1[1] - r0[1] * r1[0]};
        return new float[]{r0[0], r0[1], r0[2], r1[0], r1[1], r1[2], r2[0], r2[1], r2[2]};
    }

    private static void normalize(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (l < 1e-6f) return;
        v[0] /= l;
        v[1] /= l;
        v[2] /= l;
    }

    /** Turn by screen-space degrees: dx about the vertical axis, dy about the horizontal one. */
    private void turn(float dx, float dy) {
        m = DiceArt.mul(DiceArt.rot(new float[]{0, 1, 0}, dx), m);
        m = DiceArt.mul(DiceArt.rot(new float[]{1, 0, 0}, dy), m);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (rolling) return true;
        float deg = 360f / Math.max(1, getWidth());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                turnTo = null;
                spinX = spinY = 0;
                lastX = e.getX();
                lastY = e.getY();
                lastMove = System.nanoTime();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = (e.getX() - lastX) * deg, dy = (e.getY() - lastY) * deg;
                turn(dx, dy);
                long now = System.nanoTime();
                float dt = Math.max(0.008f, (now - lastMove) / 1e9f);
                spinX = dx / dt * 0.6f;
                spinY = dy / dt * 0.6f;
                lastX = e.getX();
                lastY = e.getY();
                lastMove = now;
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (System.nanoTime() - lastMove > 80_000_000L) spinX = spinY = 0;
                idleSince = System.currentTimeMillis() + 2500;
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), s = Math.min(w, h);
        float half = s * 0.24f;
        // Throw: up and back down with a little bounce at the end.
        float lift = 0f, squash = 1f;
        if (rolling) {
            float t = rollT;
            lift = (float) Math.sin(Math.PI * Math.min(1f, t / 0.85f)) * s * 0.22f;
            if (t > 0.85f) {
                float b = (t - 0.85f) / 0.15f;
                squash = 1f - 0.08f * (float) Math.sin(Math.PI * b);
            }
        }
        float cx = w / 2f, cy = h * 0.45f - lift;
        float ground = h * 0.45f + half * 1.25f;
        // Aura: a soft pulsing glow in the die's colour.
        float pulse = 0.75f + 0.25f * (float) Math.sin(glowPulse * 1.5f);
        paint.setColor(0xff000000);
        paint.setShader(new RadialGradient(cx, cy, half * 2.4f, (aura & 0x00ffffff) | ((int) (0x77 * pulse) << 24), aura & 0x00ffffff, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, half * 2.4f, paint);
        paint.setShader(null);
        // Contact shadow: tighter and darker the lower the die is.
        float k = 1f - Math.min(1f, lift / (s * 0.22f));
        DiceArt.contactShadow(c, paint, cx, ground, half * (1.2f + 0.5f * (1 - k)), 0.35f + 0.65f * k);
        c.save();
        c.scale(1f / squash, squash, cx, cy + half);
        DiceArt.drawCube(c, paint, path, cx, cy, half, m, true, look, look.sheen ? sheen : -1f);
        c.restore();
    }
}
