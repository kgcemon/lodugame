package com.ludox.game.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import com.ludox.game.LudoApp;
import com.ludox.game.gl.DiceArt;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A board's gentle ambient effect drifting over it: floating hearts, rose petals, twinkling
 * stars, snow, embers, bubbles, leaves, fireflies or sparkles. Kept light (a couple of dozen
 * particles at most) and purely for looks; it never takes touches, and players can turn
 * board effects off in Settings ({@link #enabled}).
 */
final class BoardAmbient extends View {
    static final String PREF = "board_effects";

    /** Whether board ambient effects are on (Settings → Board effects). */
    static boolean enabled() {
        return Effects.ambience() && LudoApp.get().prefs.getBoolean(PREF, true);
    }

    private static final class Bit {
        float x, y, vx, vy, size, rot, vr, life, phase;
        int color;
    }

    private final String kind;
    private final List<Bit> bits = new ArrayList<>();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Random rnd = new Random();
    private final float d;
    private final int max;
    private long last;
    private boolean running;

    BoardAmbient(Context c, String kind) {
        super(c);
        this.kind = kind == null ? "none" : kind;
        d = c.getResources().getDisplayMetrics().density;
        max = "stars".equals(kind) || "fireflies".equals(kind) || "sparkles".equals(kind) ? 22 : 16;
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    static boolean has(String kind) {
        return kind != null && !kind.isEmpty() && !"none".equals(kind);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = has(kind);
        last = System.nanoTime();
        if (running) postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
        bits.clear();
    }

    private void spawn(float w, float h, boolean anywhere) {
        Bit b = new Bit();
        b.life = 1f;
        b.phase = rnd.nextFloat() * 6.28f;
        b.rot = rnd.nextFloat() * 360f;
        b.vr = (rnd.nextFloat() - 0.5f) * 60f;
        switch (kind) {
            case "hearts":
                b.x = rnd.nextFloat() * w;
                b.y = anywhere ? rnd.nextFloat() * h : h + 20 * d;
                b.vy = -(16 + rnd.nextFloat() * 16) * d;
                b.size = (6 + rnd.nextFloat() * 7) * d;
                b.color = rnd.nextBoolean() ? 0xccff5c8a : 0xbbff9cc0;
                break;
            case "stars":
            case "sparkles":
            case "fireflies":
                b.x = rnd.nextFloat() * w;
                b.y = rnd.nextFloat() * h;
                b.vx = (rnd.nextFloat() - 0.5f) * 6 * d;
                b.vy = (rnd.nextFloat() - 0.5f) * 6 * d;
                b.size = (2 + rnd.nextFloat() * 3) * d;
                b.color = "fireflies".equals(kind) ? (rnd.nextBoolean() ? 0xffffe27a : 0xffc8ff7a) : "sparkles".equals(kind) ? 0xffffffff : 0xfffff3c4;
                break;
            case "embers":
            case "bubbles":
                b.x = rnd.nextFloat() * w;
                b.y = anywhere ? rnd.nextFloat() * h : h + 10 * d;
                b.vy = -(20 + rnd.nextFloat() * 24) * d;
                b.vx = (rnd.nextFloat() - 0.5f) * 8 * d;
                b.size = (2 + rnd.nextFloat() * ("bubbles".equals(kind) ? 6 : 3)) * d;
                b.color = "embers".equals(kind) ? (rnd.nextBoolean() ? 0xffffa040 : 0xffff5a1e) : 0x99ffffff;
                break;
            default: // petals, snow, leaves: falling
                b.x = rnd.nextFloat() * w;
                b.y = anywhere ? rnd.nextFloat() * h : -20 * d;
                b.vy = (14 + rnd.nextFloat() * 18) * d;
                b.vx = (rnd.nextFloat() - 0.3f) * 14 * d;
                b.size = (4 + rnd.nextFloat() * 5) * d;
                b.color = "snow".equals(kind) ? 0xeeffffff : "leaves".equals(kind) ? (rnd.nextBoolean() ? 0xcc4aa85a : 0xccd0a040) : (rnd.nextBoolean() ? 0xddff8fb3 : 0xddffc4d8);
                break;
        }
        bits.add(b);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!running) return;
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            postInvalidateOnAnimation();
            return;
        }
        if (bits.isEmpty()) for (int i = 0; i < max / 2; i++) spawn(w, h, true);
        if (bits.size() < max && rnd.nextFloat() < dt * 3f) spawn(w, h, false);
        boolean twinkle = "stars".equals(kind) || "sparkles".equals(kind) || "fireflies".equals(kind);
        for (int i = bits.size() - 1; i >= 0; i--) {
            Bit b = bits.get(i);
            b.phase += dt * (twinkle ? 2.2f : 1.4f);
            b.x += (b.vx + (float) Math.sin(b.phase) * 8 * d) * dt;
            b.y += b.vy * dt;
            b.rot += b.vr * dt;
            if (twinkle) b.life -= dt * 0.12f;
            if (b.y < -40 * d || b.y > h + 40 * d || b.life <= 0) {
                bits.remove(i);
                continue;
            }
            float a = twinkle ? (0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(b.phase * 2))) * Math.min(1f, b.life * 3) : 1f;
            draw(c, b, a);
        }
        postInvalidateOnAnimation();
    }

    private void draw(Canvas c, Bit b, float a) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(b.color);
        p.setAlpha((int) (Color.alpha(b.color) * a));
        switch (kind) {
            case "hearts":
                c.save();
                c.rotate(b.rot * 0.2f, b.x, b.y);
                c.drawPath(DiceArt.heart(b.x, b.y, b.size), p);
                c.restore();
                break;
            case "petals":
            case "leaves":
                c.save();
                c.rotate(b.rot, b.x, b.y);
                c.drawOval(new RectF(b.x - b.size, b.y - b.size * 0.5f, b.x + b.size, b.y + b.size * 0.5f), p);
                c.restore();
                break;
            case "snow":
                c.drawCircle(b.x, b.y, b.size * 0.45f, p);
                break;
            case "bubbles":
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(d);
                c.drawCircle(b.x, b.y, b.size, p);
                break;
            case "fireflies":
            case "embers": {
                int col = b.color;
                p.setColor(Color.BLACK);
                p.setShader(new RadialGradient(b.x, b.y, b.size * 3, (col & 0x00ffffff) | ((int) (200 * a) << 24), col & 0x00ffffff, Shader.TileMode.CLAMP));
                c.drawCircle(b.x, b.y, b.size * 3, p);
                p.setShader(null);
                break;
            }
            default: {
                // A four-pointed twinkle.
                float r = b.size * 1.6f;
                path.reset();
                path.moveTo(b.x, b.y - r);
                path.quadTo(b.x, b.y, b.x + r, b.y);
                path.quadTo(b.x, b.y, b.x, b.y + r);
                path.quadTo(b.x, b.y, b.x - r, b.y);
                path.quadTo(b.x, b.y, b.x, b.y - r);
                c.drawPath(path, p);
                break;
            }
        }
    }
}
