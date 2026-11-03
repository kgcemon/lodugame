package com.ludox.game.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The little show a premium die puts on: a burst when it lands (gold dust, crystal sparkle,
 * flames, snow, cosmic swirl, a dragon's golden shockwave, hearts, lightning, neon rings or
 * the legendary burst) and, in the shop's preview, a gentle ambient drift of the same.
 * Effects are short, light and never touch the roll itself.
 */
final class DiceFx extends View {
    /** One particle: position, velocity, life left (1..0), size, spin, kind. */
    private static final class Bit {
        float x, y, vx, vy, life, size, rot, vr, decay;
        int color;
        int kind;
    }

    private static final int DOT = 0, SPARK = 1, HEART = 2, FLAKE = 3, SQUARE = 4, RING = 5, BOLT = 6, GLOW = 7;

    private final String effect;
    private final List<Bit> bits = new ArrayList<>();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Random rnd = new Random();
    private final float density;
    private boolean ambient;
    private long last;
    private float ambientX = 0.5f, ambientY = 0.5f, ambientR = 0.25f;

    DiceFx(Context c, String effect) {
        super(c);
        this.effect = effect == null ? "none" : effect;
        density = c.getResources().getDisplayMetrics().density;
        setClickable(false);
        setFocusable(false);
    }

    static boolean has(String effect) {
        return effect != null && !effect.isEmpty() && !"none".equals(effect);
    }

    /** Keep a soft drift of this effect around (x, y) (fractions of the view), radius r (fraction of width). */
    void ambient(float x, float y, float r) {
        ambient = true;
        ambientX = x;
        ambientY = y;
        ambientR = r;
        kick();
    }

    /**
     * A one-off burst on a die that just landed at (x, y) in `parent` (a FrameLayout covering
     * the screen): the effect view removes itself when it's done.
     */
    static void burstIn(ViewGroup parent, String effect, float x, float y, float size) {
        if (!has(effect) || parent == null) return;
        DiceFx fx = new DiceFx(parent.getContext(), effect);
        fx.setElevation(60);
        parent.addView(fx, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fx.post(() -> fx.burst(x, y, size, true));
    }

    /** A burst centred at (x, y) px; `size` is the die's size in px. */
    void burst(float x, float y, float size, boolean removeWhenDone) {
        this.removeWhenDone = removeWhenDone;
        int n;
        switch (effect) {
            case "gold":
                n = 34;
                for (int i = 0; i < n; i++) spawnOut(x, y, size, rnd.nextBoolean() ? SQUARE : SPARK, pick(0xffffe27a, 0xffffc94a, 0xfffff4c4), 0.9f);
                ring(x, y, size, 0xccffd54a);
                break;
            case "sparkle":
                for (int i = 0; i < 30; i++) spawnOut(x, y, size, SPARK, pick(0xffffffff, 0xffbfeaff, 0xff7fdcff), 0.8f);
                ring(x, y, size, 0xaa9fe6ff);
                glow(x, y, size * 1.4f, 0x667fdcff);
                break;
            case "flame":
                for (int i = 0; i < 34; i++) {
                    Bit b = spawnOut(x, y, size * 0.6f, GLOW, pick(0xffffe08a, 0xffff8a1e, 0xffff4500), 0.5f);
                    b.vy -= size * 2.2f;
                }
                break;
            case "snow":
                for (int i = 0; i < 26; i++) spawnOut(x, y, size, FLAKE, pick(0xffffffff, 0xffd2f2ff), 0.6f);
                ring(x, y, size, 0x99cfefff);
                break;
            case "cosmic":
                for (int i = 0; i < 40; i++) {
                    Bit b = spawnOut(x, y, size, DOT, pick(0xffc77dff, 0xff7f5cff, 0xff9ef0ff, 0xffffffff), 0.7f);
                    float t = b.vx;
                    b.vx = b.vy;
                    b.vy = -t; // a swirl: sideways round the die
                }
                glow(x, y, size * 1.6f, 0x669b4dff);
                break;
            case "dragon":
                ring(x, y, size, 0xeeffb020);
                ring(x, y, size * 0.6f, 0xccff4500);
                for (int i = 0; i < 26; i++) spawnOut(x, y, size, GLOW, pick(0xffffcf40, 0xffff8a00), 0.8f);
                break;
            case "hearts":
                for (int i = 0; i < 18; i++) {
                    Bit b = spawnOut(x, y, size * 0.8f, HEART, pick(0xffff4f8a, 0xffff7aa8, 0xffe91e63), 0.6f);
                    b.vy -= size * 1.2f;
                }
                break;
            case "lightning":
                for (int i = 0; i < 5; i++) {
                    Bit b = add(x, y, BOLT, 0xffeaffff);
                    b.rot = rnd.nextFloat() * 360f;
                    b.size = size * (0.9f + rnd.nextFloat() * 0.6f);
                    b.decay = 3.5f;
                }
                glow(x, y, size * 1.5f, 0x887fd8ff);
                for (int i = 0; i < 16; i++) spawnOut(x, y, size, SPARK, 0xffeaffff, 0.9f);
                break;
            case "neon":
                ring(x, y, size, 0xee39ffd6);
                ring(x, y, size * 0.7f, 0xeeff3df2);
                for (int i = 0; i < 16; i++) spawnOut(x, y, size, DOT, pick(0xff39ffd6, 0xffff3df2), 0.7f);
                break;
            case "legend":
                glow(x, y, size * 2.2f, 0x99c77dff);
                ring(x, y, size * 1.2f, 0xffffcf40);
                ring(x, y, size * 0.7f, 0xffc77dff);
                for (int i = 0; i < 48; i++) spawnOut(x, y, size * 1.2f, rnd.nextBoolean() ? SPARK : SQUARE, pick(0xffffcf40, 0xffc77dff, 0xffffffff), 1f);
                break;
            default:
                break;
        }
        kick();
    }

    private boolean removeWhenDone;

    private int pick(int... colors) {
        return colors[rnd.nextInt(colors.length)];
    }

    private Bit add(float x, float y, int kind, int color) {
        Bit b = new Bit();
        b.x = x;
        b.y = y;
        b.kind = kind;
        b.color = color;
        b.life = 1f;
        b.decay = 0.9f + rnd.nextFloat() * 0.6f;
        b.size = density * (3 + rnd.nextFloat() * 4);
        b.rot = rnd.nextFloat() * 360;
        b.vr = (rnd.nextFloat() - 0.5f) * 540;
        bits.add(b);
        return b;
    }

    private Bit spawnOut(float x, float y, float size, int kind, int color, float speed) {
        double a = rnd.nextFloat() * Math.PI * 2;
        float sp = size * (1.2f + rnd.nextFloat() * 2.2f) * speed;
        Bit b = add(x + (float) Math.cos(a) * size * 0.3f, y + (float) Math.sin(a) * size * 0.3f, kind, color);
        b.vx = (float) Math.cos(a) * sp;
        b.vy = (float) Math.sin(a) * sp;
        if (kind == HEART || kind == FLAKE) b.size = density * (6 + rnd.nextFloat() * 6);
        if (kind == GLOW) b.size = density * (6 + rnd.nextFloat() * 8);
        return b;
    }

    private void ring(float x, float y, float size, int color) {
        Bit b = add(x, y, RING, color);
        b.size = size * 0.4f;
        b.vx = size * 2.4f; // growth speed
        b.decay = 1.8f;
    }

    private void glow(float x, float y, float size, int color) {
        Bit b = add(x, y, GLOW, color);
        b.size = size;
        b.decay = 1.6f;
    }

    private void kick() {
        last = System.nanoTime();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        ambient = false;
        bits.clear();
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        float w = getWidth(), h = getHeight();
        if (ambient && rnd.nextFloat() < dt * 7f) {
            // A slow drift: specks rise around the die and fade.
            double a = rnd.nextFloat() * Math.PI * 2;
            float r = w * ambientR * (0.7f + rnd.nextFloat() * 0.5f);
            int kind = "hearts".equals(effect) ? HEART : "snow".equals(effect) ? FLAKE : "flame".equals(effect) || "dragon".equals(effect) ? GLOW
                    : "gold".equals(effect) ? SQUARE : SPARK;
            Bit b = add(w * ambientX + (float) Math.cos(a) * r, h * ambientY + (float) Math.sin(a) * r * 0.6f, kind, ambientColor());
            b.vx = (rnd.nextFloat() - 0.5f) * 12 * density;
            b.vy = -(14 + rnd.nextFloat() * 22) * density;
            b.decay = 0.35f + rnd.nextFloat() * 0.3f;
            if (kind == HEART || kind == FLAKE) b.size = density * (5 + rnd.nextFloat() * 4);
        }
        for (int i = bits.size() - 1; i >= 0; i--) {
            Bit b = bits.get(i);
            b.life -= dt * b.decay;
            if (b.life <= 0) {
                bits.remove(i);
                continue;
            }
            if (b.kind == RING) {
                b.size += b.vx * dt;
            } else {
                b.vx *= (1 - dt * 2.2f);
                b.vy = b.vy * (1 - dt * 2.2f) + (b.kind == FLAKE || b.kind == SQUARE ? 160 * density * dt : 0);
                b.x += b.vx * dt;
                b.y += b.vy * dt;
                b.rot += b.vr * dt;
            }
            draw(c, b);
        }
        if (!bits.isEmpty() || ambient) {
            postInvalidateOnAnimation();
        } else if (removeWhenDone && getParent() instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) getParent();
            post(() -> g.removeView(this));
        }
    }

    private int ambientColor() {
        switch (effect) {
            case "gold": return pick(0xffffe27a, 0xffffc94a);
            case "flame": return pick(0xffffb347, 0xffff6a1e);
            case "snow": return 0xffffffff;
            case "cosmic": return pick(0xffc77dff, 0xff9ef0ff);
            case "dragon": return pick(0xffffcf40, 0xffff8a00);
            case "hearts": return pick(0xffff7aa8, 0xffff4f8a);
            case "lightning": return 0xffeaffff;
            case "neon": return pick(0xff39ffd6, 0xffff3df2);
            case "legend": return pick(0xffffcf40, 0xffc77dff);
            default: return pick(0xffffffff, 0xffbfeaff);
        }
    }

    private void draw(Canvas c, Bit b) {
        int alpha = (int) (255 * Math.min(1f, b.life * 1.4f));
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(b.color);
        p.setAlpha(Math.min(alpha, Color.alpha(b.color)));
        switch (b.kind) {
            case SPARK: {
                float r = b.size * 1.3f;
                path.reset();
                path.moveTo(b.x, b.y - r);
                path.quadTo(b.x, b.y, b.x + r, b.y);
                path.quadTo(b.x, b.y, b.x, b.y + r);
                path.quadTo(b.x, b.y, b.x - r, b.y);
                path.quadTo(b.x, b.y, b.x, b.y - r);
                c.save();
                c.rotate(b.rot * 0.2f, b.x, b.y);
                c.drawPath(path, p);
                c.restore();
                break;
            }
            case SQUARE:
                c.save();
                c.rotate(b.rot, b.x, b.y);
                c.drawRect(b.x - b.size * 0.6f, b.y - b.size * 0.6f, b.x + b.size * 0.6f, b.y + b.size * 0.6f, p);
                c.restore();
                break;
            case HEART:
                c.drawPath(com.ludox.game.gl.DiceArt.heart(b.x, b.y, b.size), p);
                break;
            case FLAKE: {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(density * 1.3f);
                for (int k = 0; k < 3; k++) {
                    double a = Math.toRadians(b.rot + k * 60);
                    float dx = (float) Math.cos(a) * b.size, dy = (float) Math.sin(a) * b.size;
                    c.drawLine(b.x - dx, b.y - dy, b.x + dx, b.y + dy, p);
                }
                break;
            }
            case RING:
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(density * 3f * b.life + density);
                c.drawCircle(b.x, b.y, b.size, p);
                break;
            case BOLT: {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(density * 2.4f);
                p.setStrokeJoin(Paint.Join.MITER);
                path.reset();
                float r = b.size;
                double a = Math.toRadians(b.rot);
                float ux = (float) Math.cos(a), uy = (float) Math.sin(a);
                path.moveTo(b.x + ux * r * 0.3f, b.y + uy * r * 0.3f);
                for (int k = 1; k <= 4; k++) {
                    float t = 0.3f + k * 0.18f, off = (k % 2 == 0 ? 1 : -1) * r * 0.08f;
                    path.lineTo(b.x + ux * r * t - uy * off, b.y + uy * r * t + ux * off);
                }
                c.drawPath(path, p);
                break;
            }
            case GLOW: {
                int col = b.color;
                p.setColor(Color.BLACK);
                p.setShader(new RadialGradient(b.x, b.y, b.size, (col & 0x00ffffff) | ((int) (Color.alpha(col) * Math.min(1f, b.life)) << 24),
                        col & 0x00ffffff, Shader.TileMode.CLAMP));
                c.drawCircle(b.x, b.y, b.size, p);
                p.setShader(null);
                break;
            }
            default:
                c.drawCircle(b.x, b.y, b.size * 0.6f, p);
                break;
        }
    }
}
