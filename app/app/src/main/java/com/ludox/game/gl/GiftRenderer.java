package com.ludox.game.gl;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader.TileMode;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Gifts in the air: drawn on a transparent surface above the match screen, in screen pixels, so a
 * gift really goes from one player's card to another's. It decides nothing, draws nothing while
 * idle and takes no touches, so it can't get in the way of the dice or the tokens. Every position
 * is a function of the time since the gift was announced.
 */
public final class GiftRenderer implements GLSurfaceView.Renderer {
    /** One gift's journey. For a reveal (`stage`) it stays where it lands instead of leaving. */
    public static final class Flight {
        public String id, model, send, receive, particle, rarity;
        public float x0, y0, x1, y1;
        /** The gift's radius in pixels while travelling. */
        public float size;
        public long startAt, durationMs;
        /** This phone is the one receiving: a little more of everything. */
        public boolean mine, stage;
    }

    /** Phases, as fractions of a journey. */
    public static final float PREP_END = 0.2f, FLY_END = 0.62f;

    private final List<Flight> flights = new CopyOnWriteArrayList<>();
    /** 2 full, 1 light, 0 reduced (no trail, a few sparks on arrival). */
    private volatile int quality;
    public volatile Runnable requestRender;
    /** Told on the GL thread when the last journey has ended. */
    public volatile Runnable onIdle;

    private Shader shader;
    private int uGlow, uSweep;
    private GiftModels models;
    private Mesh sprite;
    private int texGlow, texSpark, texRing, texRays, texHeart, texPetal, texStar, texPaper, texDiamond;
    private int width = 1, height = 1;
    private final float[] vp = new float[16], m = new float[16], mvp = new float[16], base = new float[16];
    private boolean drewLast;
    private final float[] pos = new float[2], prev = new float[2];

    public GiftRenderer(int quality) {
        this.quality = quality;
    }

    /** Start a journey (any thread). */
    public void play(Flight f) {
        flights.add(f);
        Runnable r = requestRender;
        if (r != null) r.run();
    }

    public void clear() {
        flights.clear();
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        // A surface coming back may still have this context, and everything already in it.
        if (shader != null && GLES20.glIsProgram(shader.program)) return;
        shader = new Shader(Shader.VS, TreasureRenderer.METAL_FS);
        uGlow = GLES20.glGetUniformLocation(shader.program, "uGlow");
        uSweep = GLES20.glGetUniformLocation(shader.program, "uSweep");
        models = new GiftModels();
        sprite = Mesh.quad();
        texGlow = Textures.upload(Art.glow(), false);
        texSpark = Textures.upload(Art.spark(), false);
        texRing = Textures.upload(Textures.ring(), false);
        texRays = Textures.upload(Art.rays(), false);
        texHeart = Textures.upload(Art.heart(), false);
        texPetal = Textures.upload(Art.petal(), false);
        texStar = Textures.upload(Art.star(), false);
        texPaper = Textures.upload(Art.paper(), false);
        texDiamond = Textures.upload(Art.diamond(), false);
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        width = Math.max(1, w);
        height = Math.max(1, h);
        // Screen pixels with depth for the models to sit in. y is up here, as the lighting expects;
        // the journey is worked out in the screen's y (down) and turned over when drawn.
        Matrix.orthoM(vp, 0, 0, width, 0, height, -4000, 4000);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        long now = SystemClock.elapsedRealtime();
        GLES20.glClearColor(0, 0, 0, 0);
        GLES20.glDepthMask(true);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        boolean any = false;
        GLES20.glUseProgram(shader.program);
        GLES20.glUniform3f(shader.uLight, 0.35f, 0.9f, 0.75f);
        GLES20.glUniform1i(shader.uTex, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        for (Flight f : flights) {
            float t = (now - f.startAt) / (float) f.durationMs;
            if (t >= 1f && !f.stage) {
                flights.remove(f);
                continue;
            }
            any = true;
            drawFlight(f, Math.max(0f, f.stage ? Math.min(t, 0.86f) : t), now);
        }
        if (any) {
            Runnable r = requestRender;
            if (r != null) r.run();
        } else if (drewLast) {
            Runnable r = onIdle;
            if (r != null) r.run();
        }
        drewLast = any;
    }

    // ================================================================= a journey

    private static float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static float smooth(float v) {
        v = clamp01(v);
        return v * v * (3 - 2 * v);
    }

    private static float back(float v) {
        v = clamp01(v);
        float s = 1.9f, u = v - 1;
        return u * u * ((s + 1) * u + s) + 1;
    }

    private static float rnd(int a, int b) {
        double x = Math.sin(a * 12.9898 + b * 78.233) * 43758.5453;
        return (float) (x - Math.floor(x));
    }

    /**
     * Along the journey (k 0 to 1), by the screen's edge rather than through the middle, where the
     * dice and the home cells are: two cards on different rows go round the nearer side, two on one
     * row over the top or under the bottom.
     */
    private void path(Flight f, float k, float[] out) {
        float dx = f.x1 - f.x0, dy = f.y1 - f.y0;
        float c1x, c1y, c2x, c2y;
        if (Math.abs(dy) > Math.abs(dx) * 0.6f) {
            float side = f.x1 >= width / 2f ? width * 0.97f : width * 0.03f;
            c1x = side;
            c1y = f.y0 + dy * 0.25f;
            c2x = side;
            c2y = f.y0 + dy * 0.75f;
        } else {
            float lift = f.y0 < height / 2f ? -height * 0.1f : height * 0.1f;
            c1x = f.x0 + dx * 0.25f;
            c1y = f.y0 + lift;
            c2x = f.x0 + dx * 0.75f;
            c2y = f.y1 + lift;
        }
        float u = 1 - k;
        float x = u * u * u * f.x0 + 3 * u * u * k * c1x + 3 * u * k * k * c2x + k * k * k * f.x1;
        float y = u * u * u * f.y0 + 3 * u * u * k * c1y + 3 * u * k * k * c2y + k * k * k * f.y1;
        float across = (float) Math.sin(k * Math.PI);
        switch (f.send) {
            case "flutter":
                x += (float) Math.sin(k * 15) * f.size * 0.7f * across;
                y += (float) Math.cos(k * 11) * f.size * 0.45f * across;
                break;
            case "hop":
                y -= Math.abs((float) Math.sin(k * Math.PI * 5)) * f.size * 1.1f * across;
                break;
            case "float":
                y += (float) Math.sin(k * 9) * f.size * 0.3f * across;
                break;
            case "rise":
                y -= across * f.size * 1.5f;
                x += (float) Math.sin(k * 7) * f.size * 0.25f * across;
                break;
            case "arc":
                y -= across * f.size * 1.2f;
                break;
            default:
                break;
        }
        out[0] = x;
        out[1] = y;
    }

    private static float flightEase(String send, float k) {
        switch (send) {
            case "swoop": return k * k * (2.4f - 1.4f * k);
            case "drive": return k * k * (3 - 2 * k);
            case "rise": return k * (0.6f + 0.4f * k);
            default: return k * k * (3 - 2 * k) * 0.5f + k * 0.5f;
        }
    }

    private void drawFlight(Flight f, float t, long now) {
        float secs = (now - f.startAt) / 1000f;
        boolean grand = "epic".equals(f.rarity) || "legendary".equals(f.rarity);
        boolean legend = "legendary".equals(f.rarity);
        float prep = t / PREP_END;
        float fly = clamp01((t - PREP_END) / (FLY_END - PREP_END));
        float arrive = clamp01((t - FLY_END) / (1f - FLY_END));
        float x, y, scale = 1f, spinY, tilt = 0, roll = 0, open = 0, flap = 9f;

        // Where it is. Preparation: it appears on the sender's side and lifts away.
        float lift = smooth(prep) * f.size * 1.5f * (f.y0 < height / 2f ? 1 : -1);
        if (t < PREP_END) {
            x = f.x0;
            y = f.y0 + lift;
            scale = back(prep * 1.5f);
            spinY = prep * 120f;
        } else if (t < FLY_END) {
            // From the lifted spot: path() starts at the card, so blend the offset away.
            float e = flightEase(f.send, fly);
            path(f, e, pos);
            x = pos[0];
            y = pos[1] + (1 - e) * (1 - e) * lift;
            spinY = 120f + fly * 300f;
            if (f.send.equals("swoop") || f.send.equals("drive")) {
                path(f, Math.max(0, e - 0.02f), prev);
                float head = (float) Math.toDegrees(Math.atan2(pos[0] - prev[0], -(pos[1] - prev[1])));
                roll = -head * 0.25f;
                spinY = f.send.equals("drive") ? (pos[0] >= prev[0] ? 90f : -90f) : 20f * (float) Math.sin(fly * 6);
                flap = 14f;
            }
        } else {
            x = f.x1;
            y = f.y1;
            spinY = 60f + secs * 40f;
        }

        // What it does on arrival.
        float grow = 1f;
        float a = arrive;
        switch (f.receive) {
            case "bounce":
                y -= Math.abs((float) Math.sin(a * Math.PI * 4)) * f.size * 0.85f * (1 - a);
                spinY = 0;
                grow = 1 + 0.15f * smooth(a * 4);
                break;
            case "expand":
                grow = 1f + 0.9f * back(a * 2.4f) - 0.2f * clamp01((a - 0.4f) * 2);
                spinY = secs * 60f;
                break;
            case "unfold":
                spinY = 0;
                tilt = (1 - smooth(a * 2.5f)) * 360f;
                grow = 1 + 0.5f * smooth(a * 2.5f);
                break;
            case "open":
                open = smooth((a - 0.08f) * 3f);
                spinY = -12f;
                tilt = 12f;
                grow = 1 + 0.45f * smooth(a * 3f);
                break;
            case "bob":
                y -= (float) Math.sin(a * Math.PI * 4) * f.size * 0.3f + a * f.size * 0.3f;
                spinY = (float) Math.sin(a * 6) * 25f;
                grow = 1 + 0.25f * smooth(a * 3);
                break;
            case "hover":
                y -= smooth(a * 2) * f.size * 1.5f + (float) Math.sin(a * 12) * f.size * 0.08f;
                grow = 1 + 0.25f * smooth(a * 2);
                break;
            case "wings":
                flap = 18f + 8f * (1 - a);
                spinY = 0;
                y -= smooth(a * 2) * f.size * 0.7f;
                grow = 1 + 0.6f * back(a * 2.4f);
                break;
            case "land":
                // Comes in over the card and settles on it with a small bump.
                y -= (1 - smooth(a * 2.2f)) * f.size * 1.4f;
                spinY = 22f;
                tilt = 12f;
                grow = 1 + 0.5f * back(a * 2.4f) - (a > 0.45f && a < 0.6f ? (float) Math.sin((a - 0.45f) * 21) * 0.06f : 0);
                break;
            default: // pop
                grow = 1 + 0.6f * back(a * 3f) - 0.15f * clamp01((a - 0.35f) * 3);
                break;
        }
        if (t >= FLY_END && f.mine) grow *= 1.2f;
        if (f.stage) {
            grow = 2.4f * (0.5f + 0.5f * back(arrive * 2f));
            // It turns to and fro, so its face stays towards you.
            spinY = (float) Math.sin(secs * 0.9f) * 34f;
            if (!"open".equals(f.receive)) tilt = 8f;
            else tilt = 16f;
        }
        // Leaving: it shrinks into the card.
        float leave = f.stage ? 0 : clamp01((t - 0.93f) / 0.07f);
        scale *= grow * (1 - smooth(leave));

        // Big gifts landing on a corner card stay on the screen; their ring stays on the card.
        float edge = f.size * Math.max(1f, scale) * 1.15f;
        x = Math.max(edge, Math.min(width - edge, x));
        y = Math.max(edge, Math.min(height - edge, y));

        behind(f, t, arrive, x, y, scale, secs, grand, legend);

        if (scale > 0.01f) {
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glDepthMask(true);
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT);
            GLES20.glUniform3f(shader.uEye, x, height - y, 3000f);
            GLES20.glUniform1f(uSweep, secs * 3f);
            Matrix.setIdentityM(base, 0);
            Matrix.translateM(base, 0, x, height - y, 0);
            Matrix.scaleM(base, 0, f.size * scale, f.size * scale, f.size * scale);
            if (roll != 0) Matrix.rotateM(base, 0, roll, 0, 0, 1);
            if (tilt != 0) Matrix.rotateM(base, 0, tilt, 1, 0, 0);
            Matrix.rotateM(base, 0, spinY, 0, 1, 0);
            models.draw(f.model, shader, uGlow, vp, base, secs, flap, open);
        }

        front(f, t, arrive, x, y, scale, secs);
    }

    // ================================================================= light and particles

    private void sprite(int tex, float x, float y, float size, float rot, float r, float g, float b, float a) {
        if (a <= 0.004f || size <= 0.1f) return;
        Matrix.setIdentityM(m, 0);
        Matrix.translateM(m, 0, x, height - y, 0);
        if (rot != 0) Matrix.rotateM(m, 0, rot, 0, 0, 1);
        Matrix.scaleM(m, 0, size, size, 1);
        Matrix.multiplyMM(mvp, 0, vp, 0, m, 0);
        GLES20.glUniformMatrix4fv(shader.uMVP, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(shader.uModel, 1, false, m, 0);
        GLES20.glUniform4f(shader.uColor, r, g, b, a);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        sprite.draw(shader);
    }

    private void additive() {
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUniform1f(shader.uUnlit, 1f);
        GLES20.glUniform1f(shader.uUseTex, 1f);
    }

    private void normalBlend() {
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUniform1f(shader.uUnlit, 1f);
        GLES20.glUniform1f(shader.uUseTex, 1f);
    }

    /** Glow, rays and the ring: behind the gift. */
    private void behind(Flight f, float t, float arrive, float x, float y, float scale, float secs, boolean grand, boolean legend) {
        additive();
        float[] c = tint(f.particle, 0);
        // Preparation: light gathers round it, then bursts as it launches.
        float prep = t / PREP_END;
        if (t < PREP_END + 0.05f) {
            float burst = clamp01((t - PREP_END + 0.05f) / 0.1f);
            sprite(texGlow, x, y, f.size * (1.6f + burst * 1.8f), 0, c[0], c[1], c[2], Math.min(1f, prep) * (1 - burst) * 0.85f);
        }
        // A halo that travels with it.
        sprite(texGlow, x, y, f.size * 1.5f * Math.max(scale, 0.2f), 0, c[0], c[1], c[2], t < FLY_END ? 0.32f : 0.45f * (1 - arrive * 0.6f));
        if (arrive > 0) {
            float k = f.stage ? 1 : 1 - clamp01((arrive - 0.6f) / 0.4f);
            if (grand || f.mine || f.stage) {
                sprite(texRays, f.x1, f.y1, f.size * (legend ? 4.2f : 3f) * smooth(arrive * 3f) * (f.stage ? 1.6f : 1f), secs * 28f, c[0], c[1], c[2], 0.5f * k);
            }
            // The soft ring round the recipient's avatar.
            float ring = (arrive * 2.2f) % 1f;
            if (!f.stage) {
                sprite(texRing, f.x1, f.y1, f.size * (1f + ring * 2f), 0, c[0], c[1], c[2], (1 - ring) * k * 0.9f);
                if (arrive > 0.2f) {
                    float r2 = ((arrive - 0.2f) * 2.2f) % 1f;
                    sprite(texRing, f.x1, f.y1, f.size * (1f + r2 * 2f), 0, 1f, 0.95f, 0.75f, (1 - r2) * k * 0.6f);
                }
            }
        }
    }

    private static float[] tint(String particle, int i) {
        switch (particle) {
            case "hearts": return i % 2 == 0 ? new float[]{1f, 0.35f, 0.55f} : new float[]{1f, 0.6f, 0.75f};
            case "petals": return i % 2 == 0 ? new float[]{0.95f, 0.2f, 0.32f} : new float[]{1f, 0.55f, 0.65f};
            case "stars": return new float[]{1f, 0.86f, 0.4f};
            case "magic": return i % 3 == 0 ? new float[]{0.4f, 0.9f, 1f} : i % 3 == 1 ? new float[]{0.8f, 0.5f, 1f} : new float[]{1f, 0.75f, 1f};
            case "fire": return i % 2 == 0 ? new float[]{1f, 0.55f, 0.12f} : new float[]{1f, 0.82f, 0.25f};
            case "diamonds": return i % 2 == 0 ? new float[]{0.65f, 0.9f, 1f} : new float[]{0.9f, 0.75f, 1f};
            case "confetti": {
                float[][] c = {{1f, 0.35f, 0.4f}, {1f, 0.8f, 0.25f}, {0.35f, 0.75f, 1f}, {0.5f, 0.9f, 0.5f}, {0.85f, 0.5f, 1f}};
                return c[i % c.length];
            }
            default: return new float[]{1f, 0.82f, 0.42f};
        }
    }

    private int particleTex(String particle) {
        switch (particle) {
            case "hearts": return texHeart;
            case "petals": return texPetal;
            case "stars": return texStar;
            case "confetti": return texPaper;
            case "diamonds": return texDiamond;
            case "fire": return texGlow;
            default: return texSpark;
        }
    }

    /** Trail in the air, and the celebration where it lands: over the gift. */
    private void front(Flight f, float t, float arrive, float x, float y, float scale, float secs) {
        int q = quality;
        int tex = particleTex(f.particle);
        boolean soft = tex == texHeart || tex == texPetal || tex == texPaper;
        if (soft) normalBlend();
        else additive();

        // The trail: the same seeds fall behind it along the path, so every phone draws the same one.
        int trail = q == 2 ? 16 : q == 1 ? 8 : 0;
        if (t > PREP_END && t < FLY_END + 0.1f && !f.stage) {
            float fly = clamp01((t - PREP_END) / (FLY_END - PREP_END));
            for (int i = 0; i < trail; i++) {
                float born = i / (float) trail;
                float age = (fly - born) * 3f;
                if (age < 0 || age > 1) continue;
                path(f, flightEase(f.send, born), pos);
                float[] c = tint(f.particle, i);
                float dx = (rnd(i, 1) - 0.5f) * f.size * 1.1f;
                float dy = (rnd(i, 2) - 0.5f) * f.size * 1.1f + age * f.size * 0.7f;
                sprite(tex, pos[0] + dx, pos[1] + dy, f.size * (0.1f + rnd(i, 3) * 0.14f) * (1 - age * 0.4f), age * 200f * (rnd(i, 4) - 0.5f), c[0], c[1], c[2], (1 - age) * 0.9f);
            }
        }

        // The celebration on arrival.
        int burst = f.stage ? (q == 0 ? 8 : 26) : (f.mine ? 34 : 20);
        if (q == 1) burst = burst * 2 / 3;
        if (q == 0) burst = Math.min(burst, 8);
        if ("legendary".equals(f.rarity) && q > 0) burst += 14;
        if (arrive > 0) {
            for (int i = 0; i < burst; i++) {
                float start = rnd(i, 5) * 0.35f;
                float age = f.stage ? ((secs * 0.28f + rnd(i, 6)) % 1f) : (arrive - start) / 0.6f;
                if (age < 0 || age > 1) continue;
                double dir = rnd(i, 7) * Math.PI * 2;
                float sp = f.size * (1.2f + rnd(i, 8) * (f.stage ? 2.2f : 2.6f));
                float[] c = tint(f.particle, i);
                float px = f.x1 + (float) Math.cos(dir) * sp * age;
                // Petals and paper drift down; the rest are thrown up and fall a little.
                float gravity = soft ? f.size * 2.4f : f.size * 1.2f;
                float py = f.y1 + (float) Math.sin(dir) * sp * age * 0.8f + gravity * age * age;
                sprite(tex, px, py, f.size * (0.13f + rnd(i, 9) * 0.17f), age * 360f * (rnd(i, 10) - 0.5f), c[0], c[1], c[2], (1 - age * age));
            }
        }
    }

    // ================================================================= painted sprites

    private static final class Art {
        static Bitmap glow() {
            int s = 96;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f, new int[]{0xffffffff, 0x55ffffff, 0x00ffffff}, new float[]{0f, 0.4f, 1f}, TileMode.CLAMP));
            new Canvas(b).drawCircle(s / 2f, s / 2f, s / 2f, p);
            return b;
        }

        static Bitmap spark() {
            int s = 64;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            float m = s / 2f, t = s * 0.05f;
            p.setShader(new RadialGradient(m, m, s * 0.22f, 0xffffffff, 0x00ffffff, TileMode.CLAMP));
            c.drawCircle(m, m, s * 0.22f, p);
            p.setShader(null);
            p.setColor(0xffffffff);
            Path star = new Path();
            star.moveTo(m, 0);
            star.lineTo(m + t, m - t);
            star.lineTo(s, m);
            star.lineTo(m + t, m + t);
            star.lineTo(m, s);
            star.lineTo(m - t, m + t);
            star.lineTo(0, m);
            star.lineTo(m - t, m - t);
            star.close();
            c.drawPath(star, p);
            return b;
        }

        static Bitmap rays() {
            int s = 192;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f, new int[]{0xffffffff, 0x77ffffff, 0x00ffffff}, new float[]{0f, 0.35f, 1f}, TileMode.CLAMP));
            Path w = new Path();
            int n = 12;
            for (int i = 0; i < n; i++) {
                double a = i * 2 * Math.PI / n, d = Math.PI / n * 0.5;
                w.moveTo(s / 2f, s / 2f);
                w.lineTo(s / 2f + (float) Math.cos(a - d) * s, s / 2f + (float) Math.sin(a - d) * s);
                w.lineTo(s / 2f + (float) Math.cos(a + d) * s, s / 2f + (float) Math.sin(a + d) * s);
                w.close();
            }
            new Canvas(b).drawPath(w, p);
            return b;
        }

        static Bitmap heart() {
            int s = 64;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xffffffff);
            Path h = new Path();
            for (int i = 0; i <= 40; i++) {
                double t = 2 * Math.PI * i / 40;
                float x = (float) (16 * Math.pow(Math.sin(t), 3)) / 17f;
                float y = (float) (13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t)) / 17f;
                float px = s / 2f + x * s * 0.46f, py = s / 2f - y * s * 0.46f;
                if (i == 0) h.moveTo(px, py);
                else h.lineTo(px, py);
            }
            h.close();
            new Canvas(b).drawPath(h, p);
            return b;
        }

        static Bitmap petal() {
            int s = 64;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xffffffff);
            Path h = new Path();
            h.moveTo(s * 0.5f, s * 0.04f);
            h.cubicTo(s * 0.95f, s * 0.2f, s * 0.86f, s * 0.8f, s * 0.5f, s * 0.96f);
            h.cubicTo(s * 0.14f, s * 0.8f, s * 0.05f, s * 0.2f, s * 0.5f, s * 0.04f);
            new Canvas(b).drawPath(h, p);
            return b;
        }

        static Bitmap star() {
            int s = 64;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xffffffff);
            Path h = new Path();
            for (int i = 0; i < 10; i++) {
                double a = -Math.PI / 2 + i * Math.PI / 5;
                float r = (i % 2 == 0 ? 0.48f : 0.21f) * s;
                float x = s / 2f + (float) Math.cos(a) * r, y = s / 2f + (float) Math.sin(a) * r;
                if (i == 0) h.moveTo(x, y);
                else h.lineTo(x, y);
            }
            h.close();
            new Canvas(b).drawPath(h, p);
            return b;
        }

        static Bitmap paper() {
            int s = 32;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Paint p = new Paint();
            p.setColor(0xffffffff);
            new Canvas(b).drawRect(s * 0.1f, s * 0.28f, s * 0.9f, s * 0.72f, p);
            return b;
        }

        static Bitmap diamond() {
            int s = 64;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            Path d = new Path();
            d.moveTo(s * 0.25f, s * 0.18f);
            d.lineTo(s * 0.75f, s * 0.18f);
            d.lineTo(s * 0.96f, s * 0.42f);
            d.lineTo(s * 0.5f, s * 0.96f);
            d.lineTo(s * 0.04f, s * 0.42f);
            d.close();
            p.setColor(0xffcccccc);
            c.drawPath(d, p);
            p.setColor(0xffffffff);
            Path top = new Path();
            top.moveTo(s * 0.25f, s * 0.18f);
            top.lineTo(s * 0.75f, s * 0.18f);
            top.lineTo(s * 0.62f, s * 0.42f);
            top.lineTo(s * 0.38f, s * 0.42f);
            top.close();
            c.drawPath(top, p);
            return b;
        }
    }
}
