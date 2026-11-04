package com.ludox.game.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import com.ludox.game.gl.GameRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Capture and home effects, drawn over the 3D board: shockwaves, particles, a light burst and
 * floating words. It only decorates what the server's events already decided. It takes no
 * touches, nothing waits for it, and it draws nothing when there is nothing to show.
 */
final class BoardFx extends View {
    private static final int PARTICLES = 140;

    private final GameRenderer renderer;
    private final float density;
    private final Random rnd = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint word = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** x, y, vx, vy, gravity, life left (0..1), fade per second, radius, colour. */
    private final float[][] parts = new float[PARTICLES][9];
    private int partNext;
    private final List<Ring> rings = new ArrayList<>();
    private final List<Glow> glows = new ArrayList<>();
    private final List<Words> words = new ArrayList<>();
    private int trailPlayer = -1, trailToken, trailColor;
    private long trailUntil;
    private long lastFrame;
    private boolean running;

    private static final class Ring {
        float x, y, from, to, width;
        int color;
        long at, ms;
    }

    private static final class Glow {
        float x, y, radius;
        Shader shader;
        long at, ms;
    }

    private static final class Words {
        String top, sub;
        float x, y, size;
        int[] fill;
        long at, ms;
    }

    BoardFx(Context c, GameRenderer renderer) {
        super(c);
        this.renderer = renderer;
        density = c.getResources().getDisplayMetrics().density;
        word.setTextAlign(Paint.Align.CENTER);
        word.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD_ITALIC));
        setClickable(false);
        setFocusable(false);
    }

    private float cell() {
        return getWidth() / 15f;
    }

    // ------------------------------------------------------------------ the two moments

    /** The flash and shockwave where a token was caught, its trail on the way home, and the words. */
    void capture(float x, float y, int victim, int victimToken, int victimColor, String top, String sub, boolean bold) {
        int level = Effects.level();
        long now = SystemClock.uptimeMillis();
        if (level != Effects.REDUCED) {
            float u = cell();
            ring(x, y, u * 0.4f, u * 2.6f, density * 4, 0xffffd34d, now, 460);
            glow(x, y, u * 2.1f, 0xffffffff, 0xffffa90a, now, 300);
            if (level == Effects.FULL) {
                ring(x, y, u * 0.2f, u * 1.9f, density * 3, victimColor, now + 90, 460);
                ring(x, y, u * 0.9f, u * 1.15f, density * 2.5f, 0xffffffff, now + 40, 640);
            }
            burst(x, y, level == Effects.FULL ? 22 : 7, victimColor, 0xffffd34d, 190, 0, 1.5f);
            if (level == Effects.FULL) {
                trailPlayer = victim;
                trailToken = victimToken;
                trailColor = victimColor;
                trailUntil = now + 620;
            }
        }
        say(top, sub, x, y - cell() * 1.4f, bold ? 26 : 15, bold ? new int[]{0xfffff7b0, 0xffff9f0a} : new int[]{0xffffffff, 0xffd6dbf5}, level == Effects.REDUCED ? 900 : 1250);
        wake();
    }

    /** 1st to 4th token home: each one a step bigger than the last. */
    void home(float x, float y, int count, int color, String top, String sub, boolean bold) {
        int level = Effects.level();
        long now = SystemClock.uptimeMillis();
        if (level != Effects.REDUCED) {
            float u = cell();
            float grow = 1f + 0.35f * (count - 1);
            glow(x, y, u * 2f * grow, 0xfffff3a8, 0xffffb300, now, 760);
            ring(x, y, u * 0.5f, u * 2.2f * grow, density * 4, 0xffffd34d, now + 60, 560);
            int rising = level == Effects.FULL ? 8 + count * 7 : 4 + count;
            rise(x, y, rising, 0xffffd34d);
            if (level == Effects.FULL) {
                if (count >= 2) ring(x, y, u * 0.3f, u * 2.8f * grow, density * 3, color, now + 180, 620);
                if (count >= 3) burst(x, y, 14 + (count - 3) * 16, 0xffffffff, color, 250, 0, 1.2f);
                if (count >= 4) {
                    glow(x, y, u * 7f, 0xeeffffff, 0xffffc94a, now + 120, 900);
                    ring(x, y, u * 1f, u * 8f, density * 5, 0xffffffff, now + 150, 800);
                    burst(x, y, 34, 0xffffd34d, color, 340, 220, 0.9f);
                }
            }
        }
        float size = (bold ? 24 : 14) + (bold ? (count - 1) * 3 : 0);
        say(top, sub, x, y - cell() * 1.6f, size, new int[]{0xfffff7b0, 0xffffa90a}, level == Effects.REDUCED ? 900 : 1100 + count * 150L);
        wake();
    }

    // ------------------------------------------------------------------ the pieces

    private void ring(float x, float y, float from, float to, float width, int color, long at, long ms) {
        Ring r = new Ring();
        r.x = x;
        r.y = y;
        r.from = from;
        r.to = to;
        r.width = width;
        r.color = color;
        r.at = at;
        r.ms = ms;
        rings.add(r);
    }

    private void glow(float x, float y, float radius, int inner, int outer, long at, long ms) {
        Glow g = new Glow();
        g.x = x;
        g.y = y;
        g.radius = radius;
        g.shader = new RadialGradient(0, 0, 1f, new int[]{inner, (outer & 0x00ffffff) | 0x99000000, outer & 0x00ffffff}, new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP);
        g.at = at;
        g.ms = ms;
        glows.add(g);
    }

    private void say(String top, String sub, float x, float y, float sizeSp, int[] fill, long ms) {
        if (top == null) return;
        Words w = new Words();
        w.top = top;
        w.sub = sub;
        w.size = sizeSp * getResources().getDisplayMetrics().scaledDensity;
        word.setTextSize(w.size);
        float half = word.measureText(top) / 2f + density * 8;
        w.x = getWidth() > half * 2 ? Math.max(half, Math.min(getWidth() - half, x)) : getWidth() / 2f;
        w.y = Math.max(w.size * 1.2f, y);
        w.fill = fill;
        w.at = SystemClock.uptimeMillis();
        w.ms = ms;
        words.add(w);
    }

    private float[] particle() {
        float[] p = parts[partNext];
        partNext = (partNext + 1) % PARTICLES;
        return p;
    }

    private void burst(float x, float y, int n, int a, int b, float speedDp, float gravityDp, float fade) {
        for (int i = 0; i < n; i++) {
            float[] p = particle();
            double ang = rnd.nextDouble() * Math.PI * 2;
            float sp = density * speedDp * (0.35f + rnd.nextFloat() * 0.65f);
            p[0] = x;
            p[1] = y;
            p[2] = (float) Math.cos(ang) * sp;
            p[3] = (float) Math.sin(ang) * sp;
            p[4] = density * gravityDp;
            p[5] = 1f;
            p[6] = fade * (0.8f + rnd.nextFloat() * 0.5f);
            p[7] = density * (1.8f + rnd.nextFloat() * 2.6f);
            p[8] = i % 3 == 0 ? b : a;
        }
    }

    private void rise(float x, float y, int n, int color) {
        for (int i = 0; i < n; i++) {
            float[] p = particle();
            p[0] = x + (rnd.nextFloat() - 0.5f) * cell() * 1.4f;
            p[1] = y + (rnd.nextFloat() - 0.5f) * cell() * 0.6f;
            p[2] = (rnd.nextFloat() - 0.5f) * density * 26;
            p[3] = -density * (55 + rnd.nextFloat() * 85);
            p[4] = 0;
            p[5] = 1f;
            p[6] = 0.75f + rnd.nextFloat() * 0.5f;
            p[7] = density * (1.8f + rnd.nextFloat() * 2.2f);
            p[8] = color;
        }
    }

    private void wake() {
        if (!running) {
            running = true;
            lastFrame = SystemClock.uptimeMillis();
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean alive = false;

        for (int i = glows.size() - 1; i >= 0; i--) {
            Glow g = glows.get(i);
            long age = now - g.at;
            if (age >= g.ms) {
                glows.remove(i);
                continue;
            }
            alive = true;
            if (age < 0) continue;
            float t = age / (float) g.ms;
            float a = t < 0.2f ? t / 0.2f : 1f - (t - 0.2f) / 0.8f;
            c.save();
            c.translate(g.x, g.y);
            float s = g.radius * (0.6f + 0.4f * t);
            c.scale(s, s);
            paint.setShader(g.shader);
            paint.setAlpha((int) (255 * a));
            c.drawCircle(0, 0, 1f, paint);
            paint.setShader(null);
            c.restore();
        }

        paint.setStyle(Paint.Style.STROKE);
        for (int i = rings.size() - 1; i >= 0; i--) {
            Ring r = rings.get(i);
            long age = now - r.at;
            if (age >= r.ms) {
                rings.remove(i);
                continue;
            }
            alive = true;
            if (age < 0) continue;
            float t = age / (float) r.ms;
            float e = 1 - (1 - t) * (1 - t);
            paint.setStrokeWidth(r.width * (1 - t * 0.7f));
            paint.setColor(r.color);
            paint.setAlpha((int) (235 * (1 - t)));
            c.drawCircle(r.x, r.y, r.from + (r.to - r.from) * e, paint);
        }
        paint.setStyle(Paint.Style.FILL);

        // The caught token's trail, following it all the way to its yard.
        if (trailPlayer >= 0) {
            if (now < trailUntil) {
                float[] at = renderer.tokenScreen(trailPlayer, trailToken);
                float[] p = particle();
                p[0] = at[0] + (rnd.nextFloat() - 0.5f) * density * 6;
                p[1] = at[1] + (rnd.nextFloat() - 0.5f) * density * 6;
                p[2] = 0;
                p[3] = 0;
                p[4] = 0;
                p[5] = 1f;
                p[6] = 2.4f;
                p[7] = density * 4.2f;
                p[8] = trailColor;
                alive = true;
            } else {
                trailPlayer = -1;
            }
        }

        for (float[] p : parts) {
            if (p[5] <= 0) continue;
            alive = true;
            p[5] -= p[6] * dt;
            p[3] += p[4] * dt;
            p[0] += p[2] * dt;
            p[1] += p[3] * dt;
            if (p[5] <= 0) continue;
            paint.setColor((int) p[8]);
            paint.setAlpha((int) (255 * p[5]));
            c.drawCircle(p[0], p[1], p[7] * (0.5f + 0.5f * p[5]), paint);
        }

        for (int i = words.size() - 1; i >= 0; i--) {
            Words w = words.get(i);
            long age = now - w.at;
            if (age >= w.ms) {
                words.remove(i);
                continue;
            }
            alive = true;
            float t = age / (float) w.ms;
            float pop = age < 200 ? (age / 200f) * (1.3f - 0.3f * age / 200f) : 1f;
            float a = t > 0.72f ? 1f - (t - 0.72f) / 0.28f : 1f;
            float lift = density * 24 * t;
            c.save();
            c.translate(w.x, w.y - lift);
            c.scale(pop, pop);
            outlined(c, w.top, 0, w.size, w.fill, a);
            if (w.sub != null) outlined(c, w.sub, w.size * 0.95f, w.size * 0.55f, new int[]{0xffffffff, 0xffffe9a8}, a);
            c.restore();
        }
        paint.setAlpha(255);
        if (alive) postInvalidateOnAnimation();
        else running = false;
    }

    private void outlined(Canvas c, String s, float y, float size, int[] fill, float alpha) {
        word.setTextSize(size);
        word.setShader(null);
        word.setStyle(Paint.Style.STROKE);
        word.setStrokeJoin(Paint.Join.ROUND);
        word.setStrokeWidth(size * 0.18f);
        word.setColor(0xff3a1500);
        word.setAlpha((int) (255 * alpha));
        c.drawText(s, 0, y, word);
        word.setStyle(Paint.Style.FILL);
        word.setColor(Color.WHITE);
        word.setAlpha((int) (255 * alpha));
        word.setShader(new LinearGradient(0, y - size, 0, y, fill[0], fill[1], Shader.TileMode.CLAMP));
        c.drawText(s, 0, y, word);
        word.setShader(null);
    }
}
