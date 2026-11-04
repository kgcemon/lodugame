package com.ludox.game.gl;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader.TileMode;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The royal treasure box of the Match Found introduction: a real 3D chest whose lid opens, gold
 * coins that fly into it from the players' cards, and the glow when it locks. It only shows what
 * the server has already recorded and decides nothing.
 *
 * Everything on screen is a function of the time left on the server's clock (no state builds up
 * from frame to frame): two phones stay together, and a player reconnecting mid-way sees what
 * they would have been seeing anyway.
 */
public final class TreasureRenderer implements GLSurfaceView.Renderer {
    /** The introduction's phases, as fractions of its length; the screen's text and sounds follow the same clock. */
    public static final class Timeline {
        public static final float CARDS_RISE = 0.30f, BOX_IN = 0.37f, LID_OPEN = 0.42f, COINS_START = 0.42f, COINS_END = 0.72f;
        public static final float LOCK_START = 0.745f, LOCK_END = 0.81f, READY = 0.83f, FADE = 0.96f;
        private static final float MIN_FLIGHT = 0.06f;

        public final int contributors, coinsEach;
        private final float slot, flight;

        public Timeline(int contributors) {
            this.contributors = contributors;
            coinsEach = contributors <= 2 ? 8 : 6;
            slot = contributors > 0 ? (COINS_END - COINS_START) / contributors : 0;
            flight = Math.max(slot * 0.5f, MIN_FLIGHT);
        }

        /** When this contributor's turn starts and ends (coins still in the air may overlap the next). */
        public float slotStart(int who) {
            return COINS_START + who * slot;
        }

        public float slotEnd(int who) {
            return slotStart(who) + slot;
        }

        float launch(int who, int coin) {
            return slotStart(who) + slot * 0.5f * coin / coinsEach;
        }

        float flight() {
            return flight;
        }

        /** How many of this contributor's coins are in the box at `f`. */
        public int landed(int who, float f) {
            int n = 0;
            for (int j = 0; j < coinsEach; j++) if (f >= launch(who, j) + flight) n++;
            return n;
        }
    }

    private static final String METAL_FS =
            "precision mediump float;\n" +
            "uniform vec4 uColor;\n" +
            "uniform sampler2D uTex;\n" +
            "uniform float uUseTex;\n" +
            "uniform float uUnlit;\n" +
            "uniform float uSpec;\n" +
            "uniform float uGlow;\n" +
            "uniform float uSweep;\n" +
            "uniform vec3 uLight;\n" +
            "uniform vec3 uEye;\n" +
            "varying vec3 vN;\n" +
            "varying vec2 vUV;\n" +
            "varying vec3 vW;\n" +
            "void main() {\n" +
            "  vec4 base = uColor;\n" +
            "  if (uUseTex > 0.5) base *= texture2D(uTex, vUV);\n" +
            "  if (uUnlit > 0.5) { gl_FragColor = base; return; }\n" +
            "  vec3 n = normalize(vN);\n" +
            "  vec3 L = normalize(uLight);\n" +
            "  vec3 V = normalize(uEye - vW);\n" +
            "  vec3 R = reflect(-V, n);\n" +
            // A studio around the chest for the metal to mirror: warm softbox above, purple room below,
            // and a bright strip of light that sweeps across.
            "  float up = R.y * 0.5 + 0.5;\n" +
            "  vec3 env = mix(vec3(0.22, 0.07, 0.42), vec3(1.0, 0.92, 0.70), smoothstep(0.12, 0.72, up));\n" +
            "  env += vec3(1.0, 0.94, 0.78) * pow(max(dot(R, L), 0.0), 22.0) * 1.3;\n" +
            "  env += vec3(1.0, 0.9, 0.7) * 0.45 * smoothstep(0.93, 1.0, sin(R.x * 5.0 + uSweep));\n" +
            "  float d = max(dot(n, L), 0.0);\n" +
            "  float rim = pow(1.0 - max(dot(n, V), 0.0), 3.0);\n" +
            "  vec3 lit = base.rgb * (0.30 + 0.55 * d);\n" +
            "  vec3 mirror = base.rgb * env;\n" +
            "  vec3 col = mix(lit, mirror, uSpec) + vec3(0.9, 0.7, 1.0) * rim * 0.12 + base.rgb * uGlow;\n" +
            "  gl_FragColor = vec4(col, base.a);\n" +
            "}\n";

    /** Half width, half depth and height of the body; the lid's rise. */
    private static final float W = 1.3f, D = 0.85f, H = 1.05f, LID_RISE = 0.62f;
    private static final float COIN_R = 0.17f;

    private final Timeline line;
    private final long durationMs;
    private final long endsAt;
    private final boolean hasCoins;
    /** Screen x,y of where each contributor's coins leave their card, and the box's screen y (surface pixels). */
    private volatile float[][] anchors;
    private volatile float boxY = -1;
    /** Height of the space left for the chest, as a share of the surface's. */
    private volatile float stageShare = 0.3f;
    /** 2 full, 1 light, 0 the bare minimum; steps down by itself if frames take too long. */
    private volatile int quality;

    private Shader shader;
    private int uGlow, uSweep;
    private Mesh unit, arch, coin, gem, sprite;
    private int texPanel, texCoin, texGlow, texSpark, texBg, texRays, texShadow;

    private int width = 1, height = 1;
    private final float[] proj = new float[16], view = new float[16], vp = new float[16], inv = new float[16], shift = new float[16];
    private final float[] eye = new float[3];
    private final float[] right = new float[3], upv = new float[3];
    private float camBoxY = -2;
    private int camW = -1, camH = -1;
    private float[][] camAnchors;
    private float[][] origins = new float[0][3];

    private final float[] mvp = new float[16], m = new float[16], tmp = new float[16], boxM = new float[16], lidM = new float[16];
    private final float[] p3 = new float[3], q3 = new float[3];

    private long lastFrame;
    private float slowAvg;
    private int slowCount;

    public TreasureRenderer(int contributors, long durationMs, long endsAt, int quality) {
        this.line = new Timeline(contributors);
        this.durationMs = durationMs;
        this.endsAt = endsAt;
        this.hasCoins = contributors > 0;
        this.quality = quality;
    }

    public Timeline timeline() {
        return line;
    }

    /** Where things are on screen, in this surface's pixels. */
    public void setLayout(float[][] coinAnchors, float boxScreenY, float stageHeight) {
        stageShare = Math.max(0.12f, stageHeight / Math.max(1, height));
        anchors = coinAnchors;
        boxY = boxScreenY;
    }

    // ================================================================= GL

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        shader = new Shader(Shader.VS, METAL_FS);
        uGlow = GLES20.glGetUniformLocation(shader.program, "uGlow");
        uSweep = GLES20.glGetUniformLocation(shader.program, "uSweep");
        unit = Mesh.box(1, 1, 1, false);
        arch = Mesh.arch(28);
        coin = Mesh.coin(28);
        gem = Mesh.gem(8);
        sprite = Mesh.quad();
        texBg = Textures.upload(Art.background(), false);
        texGlow = Textures.upload(Art.glow(), false);
        texSpark = Textures.upload(Art.spark(), false);
        texRays = Textures.upload(Art.rays(), false);
        texShadow = Textures.upload(Textures.shadow(), false);
        texPanel = Textures.upload(Art.panel(), true);
        texCoin = Textures.upload(Art.coin(), true);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glClearColor(0.07f, 0.02f, 0.16f, 1f);
        GLES20.glUseProgram(shader.program);
        GLES20.glUniform3f(shader.uLight, 0.45f, 1f, 0.75f);
        GLES20.glUniform1i(shader.uTex, 0);
        camW = -1;
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        width = Math.max(1, w);
        height = Math.max(1, h);
    }

    private void camera() {
        float[][] a = anchors;
        float by = boxY >= 0 ? boxY : height * 0.56f;
        if (camW == width && camH == height && camBoxY == by && camAnchors == a) return;
        camW = width;
        camH = height;
        camBoxY = by;
        camAnchors = a;
        float aspect = width / (float) height;
        float fov = 30f, tan = (float) Math.tan(Math.toRadians(fov / 2));
        // Close enough to fill the space, far enough that the chest fits it whichever way the screen is short.
        float dist = Math.max(2.9f / (0.62f * 2 * tan * aspect), 2.3f / (stageShare * 2 * tan));
        double pitch = Math.toRadians(17);
        float cy = 0.85f;
        eye[0] = 0;
        eye[1] = cy + (float) (dist * Math.sin(pitch));
        eye[2] = (float) (dist * Math.cos(pitch));
        Matrix.perspectiveM(proj, 0, fov, aspect, 0.5f, dist + 12f);
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], 0, cy, 0, 0, 1, 0);
        Matrix.multiplyMM(tmp, 0, proj, 0, view, 0);
        // Slide the picture so the chest sits where the layout left room for it.
        Matrix.setIdentityM(shift, 0);
        Matrix.translateM(shift, 0, 0, 1f - 2f * by / height, 0);
        Matrix.multiplyMM(vp, 0, shift, 0, tmp, 0);
        Matrix.invertM(inv, 0, vp, 0);
        // Billboards face the camera.
        right[0] = view[0];
        right[1] = view[4];
        right[2] = view[8];
        upv[0] = view[1];
        upv[1] = view[5];
        upv[2] = view[9];
        if (a != null) {
            origins = new float[a.length][3];
            for (int i = 0; i < a.length; i++) unproject(a[i][0], a[i][1], origins[i]);
        }
    }

    /** The point on the plane z = 1 (in front of the chest) under this screen position. */
    private void unproject(float px, float py, float[] out) {
        float nx = 2 * px / width - 1, ny = 1 - 2 * py / height;
        float[] near = {nx, ny, -1, 1}, far = {nx, ny, 1, 1}, a = new float[4], b = new float[4];
        Matrix.multiplyMV(a, 0, inv, 0, near, 0);
        Matrix.multiplyMV(b, 0, inv, 0, far, 0);
        for (int k = 0; k < 3; k++) {
            a[k] /= a[3];
            b[k] /= b[3];
        }
        float z = 1f, s = (z - a[2]) / (b[2] - a[2]);
        out[0] = a[0] + (b[0] - a[0]) * s;
        out[1] = a[1] + (b[1] - a[1]) * s;
        out[2] = z;
    }

    private void adapt() {
        long now = SystemClock.uptimeMillis();
        if (lastFrame != 0) {
            slowAvg += (now - lastFrame);
            if (++slowCount >= 20) {
                if (slowAvg / slowCount > 26f && quality > 0) quality--;
                slowAvg = 0;
                slowCount = 0;
            }
        }
        lastFrame = now;
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        adapt();
        camera();
        float f = 1f - (endsAt - SystemClock.elapsedRealtime()) / (float) durationMs;
        f = Math.max(0f, Math.min(1f, f));
        // Clearing depth needs the mask on, and the last frame ended with it off.
        GLES20.glDepthMask(true);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glUseProgram(shader.program);
        GLES20.glUniform3f(shader.uEye, eye[0], eye[1], eye[2]);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        background();
        ambient(f);
        if (hasCoins) scene(f);
    }

    // ================================================================= drawing helpers

    private void bind(int tex) {
        if (tex != 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            GLES20.glUniform1f(shader.uUseTex, 1f);
        } else {
            GLES20.glUniform1f(shader.uUseTex, 0f);
        }
    }

    private void submit(float[] model) {
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);
        GLES20.glUniformMatrix4fv(shader.uMVP, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(shader.uModel, 1, false, model, 0);
    }

    private void background() {
        Matrix.setIdentityM(m, 0);
        GLES20.glUniformMatrix4fv(shader.uMVP, 1, false, m, 0);
        GLES20.glUniformMatrix4fv(shader.uModel, 1, false, m, 0);
        GLES20.glUniform1f(shader.uUnlit, 1f);
        GLES20.glUniform4f(shader.uColor, 1, 1, 1, 1);
        bind(texBg);
        sprite.draw(shader);
    }

    /** A camera-facing sprite. */
    private void billboard(int tex, float x, float y, float z, float size, float rotDeg, float r, float g, float b, float a) {
        if (a <= 0.003f) return;
        Matrix.setIdentityM(m, 0);
        m[0] = right[0];
        m[1] = right[1];
        m[2] = right[2];
        m[4] = upv[0];
        m[5] = upv[1];
        m[6] = upv[2];
        m[8] = right[1] * upv[2] - right[2] * upv[1];
        m[9] = right[2] * upv[0] - right[0] * upv[2];
        m[10] = right[0] * upv[1] - right[1] * upv[0];
        m[12] = x;
        m[13] = y;
        m[14] = z;
        if (rotDeg != 0) Matrix.rotateM(m, 0, rotDeg, 0, 0, 1);
        Matrix.scaleM(m, 0, size, size, size);
        submit(m);
        GLES20.glUniform4f(shader.uColor, r, g, b, a);
        bind(tex);
        sprite.draw(shader);
    }

    /** A sprite lying flat on the floor. */
    private void floor(int tex, float x, float z, float sx, float sz, float r, float g, float b, float a) {
        if (a <= 0.003f) return;
        Matrix.setIdentityM(m, 0);
        Matrix.translateM(m, 0, x, 0.004f, z);
        Matrix.rotateM(m, 0, -90, 1, 0, 0);
        Matrix.scaleM(m, 0, sx, sz, 1);
        submit(m);
        GLES20.glUniform4f(shader.uColor, r, g, b, a);
        bind(tex);
        sprite.draw(shader);
    }

    private void part(float[] parent, Mesh mesh, int tex, float r, float g, float b, float spec, float glow,
                      float tx, float ty, float tz, float sx, float sy, float sz) {
        Matrix.translateM(tmp, 0, parent, 0, tx, ty, tz);
        Matrix.scaleM(tmp, 0, sx, sy, sz);
        submit(tmp);
        GLES20.glUniform4f(shader.uColor, r, g, b, 1f);
        GLES20.glUniform1f(shader.uSpec, spec);
        GLES20.glUniform1f(uGlow, glow);
        bind(tex);
        mesh.draw(shader);
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static float smooth(float v) {
        v = clamp01(v);
        return v * v * (3 - 2 * v);
    }

    /** Overshoots a little before settling. */
    private static float back(float v) {
        v = clamp01(v);
        float s = 1.9f, u = v - 1;
        return u * u * ((s + 1) * u + s) + 1;
    }

    /** The same pseudo-random number for the same coin and key, on every phone. */
    private static float rnd(int a, int b) {
        double x = Math.sin(a * 12.9898 + b * 78.233) * 43758.5453;
        return (float) (x - Math.floor(x));
    }

    // ================================================================= the scene

    /** Slow motes of gold dust in the air, from the first moment. */
    private void ambient(float f) {
        int n = quality == 2 ? 14 : quality == 1 ? 7 : 3;
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        GLES20.glUniform1f(shader.uUnlit, 1f);
        float secs = f * durationMs / 1000f;
        for (int i = 0; i < n; i++) {
            float rise = (rnd(i, 1) + secs * (0.05f + 0.05f * rnd(i, 2))) % 1f;
            float x = (rnd(i, 3) - 0.5f) * 5.2f + (float) Math.sin(secs * 0.7f + i) * 0.1f;
            float y = rise * 4.6f - 0.6f;
            float a = (float) Math.sin(rise * Math.PI) * 0.55f;
            billboard(texGlow, x, y, -1.2f + rnd(i, 4), 0.04f + rnd(i, 5) * 0.05f, 0, 1f, 0.8f, 0.4f, a);
        }
    }

    private void scene(float f) {
        float in = back((f - Timeline.CARDS_RISE) / (Timeline.BOX_IN - Timeline.CARDS_RISE));
        if (f < Timeline.CARDS_RISE) return;
        float open = smooth((f - (Timeline.BOX_IN - 0.01f)) / (Timeline.LID_OPEN - Timeline.BOX_IN + 0.01f));
        float shut = smooth((f - Timeline.LOCK_START) / (Timeline.LOCK_END - Timeline.LOCK_START));
        // A little bounce as the lid lands.
        float landed = clamp01((f - Timeline.LOCK_END) / 0.05f);
        float settle = landed > 0 && landed < 1 ? (float) Math.sin(landed * Math.PI) * 0.03f : 0;
        float lidDeg = open * (1 - shut) * 112f + settle * 60f;
        float locked = smooth((f - (Timeline.LOCK_END - 0.03f)) / 0.06f);
        float pulse = 0.5f + 0.5f * (float) Math.sin(f * durationMs / 170f);
        float filled = 0;
        for (int i = 0; i < line.contributors; i++) filled += line.landed(i, f);
        filled /= Math.max(1, line.contributors * line.coinsEach);

        // Behind the chest: the room's light gathers when it locks.
        GLES20.glUniform1f(shader.uUnlit, 1f);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        float halo = in * (0.22f + 0.2f * filled + 0.5f * locked);
        billboard(texRays, 0, 1f, -1.6f, 3.1f + locked * 1.1f, f * 140f, 1f, 0.78f, 0.35f, in * (0.10f + 0.5f * locked));
        billboard(texGlow, 0, 1f, -1.5f, 2.6f + locked, 0, 0.75f, 0.45f, 1f, halo);

        // The floor: a pool of soft purple light, the chest's soft shadow in it.
        floor(texGlow, 0, 0, 3.2f * in, 2.2f * in, 0.55f, 0.28f, 0.95f, 0.45f * in);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        floor(texShadow, 0, 0.1f, 2.1f * in, 1.5f * in, 1, 1, 1, 0.85f * in);

        // Solid things.
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUniform1f(shader.uUnlit, 0f);
        GLES20.glUniform1f(uSweep, f * 16f);
        float lift = (1 - in) * -0.2f;
        Matrix.setIdentityM(boxM, 0);
        Matrix.translateM(boxM, 0, 0, lift + settle * -0.2f, 0);
        float yaw = (1 - clamp01((f - Timeline.CARDS_RISE) / (Timeline.LID_OPEN - Timeline.CARDS_RISE))) * -24f
                + locked * (float) Math.sin((f - Timeline.LOCK_END) * 9f) * 3f;
        Matrix.rotateM(boxM, 0, yaw, 0, 1, 0);
        float s = Math.max(0.001f, in);
        Matrix.scaleM(boxM, 0, s, s, s);
        chest(lidDeg, locked, pulse);
        coins(f, s);

        // Light on top of it all.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        GLES20.glDepthMask(false);
        GLES20.glUniform1f(shader.uUnlit, 1f);
        // Treasure light spilling from the open chest.
        billboard(texGlow, 0, H * s + 0.12f, 0.1f, 1.25f, 0, 1f, 0.82f, 0.4f, open * (1 - shut) * (0.28f + 0.4f * filled));
        // The lock's flash.
        float flash = clamp01((f - Timeline.LOCK_END + 0.02f) / 0.03f) * (1 - clamp01((f - Timeline.LOCK_END - 0.01f) / 0.09f));
        billboard(texGlow, 0, H * 0.72f, D + 0.1f, 1.7f, 0, 1f, 0.9f, 0.6f, flash * 0.9f);
        particles(f);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
    }

    private void chest(float lidDeg, float locked, float pulse) {
        float gr = 1f, gg = 0.80f, gb = 0.40f;
        float bright = 0.06f + locked * 0.32f * (0.7f + 0.3f * pulse);
        float wall = 0.08f;

        // Inside: dark, so that coins read against it.
        part(boxM, unit, 0, 0.13f, 0.05f, 0.24f, 0.05f, 0, 0, (H - 0.3f) / 2f + 0.05f, 0, W - wall, (H - 0.3f) / 2f, D - wall);
        // Engraved walls.
        part(boxM, unit, texPanel, 1f, 0.92f, 0.78f, 0.5f, bright, 0, H / 2f, D - wall / 2f, W, H / 2f, wall / 2f);
        part(boxM, unit, texPanel, 1f, 0.92f, 0.78f, 0.5f, bright, 0, H / 2f, -D + wall / 2f, W, H / 2f, wall / 2f);
        part(boxM, unit, texPanel, 1f, 0.92f, 0.78f, 0.5f, bright, W - wall / 2f, H / 2f, 0, wall / 2f, H / 2f, D);
        part(boxM, unit, texPanel, 1f, 0.92f, 0.78f, 0.5f, bright, -W + wall / 2f, H / 2f, 0, wall / 2f, H / 2f, D);
        // Polished gold: plinth, top rim, corner posts, straps.
        part(boxM, unit, 0, gr, gg, gb, 0.95f, bright, 0, 0.08f, 0, W + 0.07f, 0.075f, D + 0.07f);
        part(boxM, unit, 0, gr, gg, gb, 0.95f, bright, 0, H - 0.015f, D - 0.02f, W + 0.03f, 0.04f, 0.05f);
        part(boxM, unit, 0, gr, gg, gb, 0.95f, bright, 0, H - 0.015f, -D + 0.02f, W + 0.03f, 0.04f, 0.05f);
        part(boxM, unit, 0, gr, gg, gb, 0.95f, bright, W - 0.02f, H - 0.015f, 0, 0.05f, 0.04f, D + 0.03f);
        part(boxM, unit, 0, gr, gg, gb, 0.95f, bright, -W + 0.02f, H - 0.015f, 0, 0.05f, 0.04f, D + 0.03f);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                part(boxM, unit, 0, gr, gg, gb, 0.95f, bright, sx * W, H / 2f, sz * D, 0.07f, H / 2f, 0.07f);
                part(boxM, coin, 0, gr * 0.85f, gg * 0.85f, gb * 0.85f, 0.9f, bright, sx * (W - 0.05f), 0.03f, sz * (D - 0.05f), 0.13f, 0.04f, 0.13f);
            }
            part(boxM, unit, 0, gr, gg, gb, 0.95f, bright, sx * 0.72f, H / 2f, D + 0.012f, 0.055f, H / 2f - 0.02f, 0.02f);
        }
        // The lock plate and the jewels on the front.
        part(boxM, unit, 0, gr, gg, gb, 0.98f, bright, 0, H - 0.27f, D + 0.02f, 0.24f, 0.25f, 0.03f);
        jewel(boxM, 0, H - 0.27f, D + 0.06f, 0.14f, 90, locked, pulse);
        jewel(boxM, 0.98f, H * 0.5f, D + 0.012f, 0.085f, 90, locked, pulse);
        jewel(boxM, -0.98f, H * 0.5f, D + 0.012f, 0.085f, 90, locked, pulse);
        jewel(boxM, 0.36f, H * 0.42f, D + 0.012f, 0.06f, 90, locked, pulse);
        jewel(boxM, -0.36f, H * 0.42f, D + 0.012f, 0.06f, 90, locked, pulse);

        // The lid, hinged along the back edge.
        Matrix.translateM(lidM, 0, boxM, 0, 0, H + 0.02f, -D);
        Matrix.rotateM(lidM, 0, -lidDeg, 1, 0, 0);
        Matrix.translateM(lidM, 0, 0, 0, D);
        part(lidM, arch, texPanel, 1f, 0.92f, 0.78f, 0.5f, bright, 0, 0, 0, W, LID_RISE, D);
        part(lidM, unit, 0, 0.30f, 0.09f, 0.52f, 0.18f, 0.05f, 0, -0.006f, 0, W - 0.03f, 0.012f, D - 0.03f);
        part(lidM, unit, 0, gr, gg, gb, 0.95f, bright, 0, 0.022f, D - 0.015f, W + 0.03f, 0.04f, 0.04f);
        part(lidM, unit, 0, gr, gg, gb, 0.95f, bright, 0, 0.022f, -D + 0.015f, W + 0.03f, 0.04f, 0.04f);
        part(lidM, arch, 0, gr, gg, gb, 0.95f, bright, W - 0.01f, 0, 0, 0.05f, LID_RISE + 0.015f, D + 0.012f);
        part(lidM, arch, 0, gr, gg, gb, 0.95f, bright, -W + 0.01f, 0, 0, 0.05f, LID_RISE + 0.015f, D + 0.012f);
        part(lidM, arch, 0, gr, gg, gb, 0.95f, bright, 0.72f, 0, 0, 0.055f, LID_RISE + 0.015f, D + 0.012f);
        part(lidM, arch, 0, gr, gg, gb, 0.95f, bright, -0.72f, 0, 0, 0.055f, LID_RISE + 0.015f, D + 0.012f);
        part(lidM, unit, 0, gr, gg, gb, 0.98f, bright, 0, 0.1f, D + 0.012f, 0.12f, 0.1f, 0.02f);
        jewel(lidM, 0, LID_RISE + 0.015f, 0, 0.15f, 0, locked, pulse);
        jewel(lidM, 0, LID_RISE * 0.72f, D * 0.70f, 0.1f, 45, locked, pulse);
    }

    /** A cut purple gem (`tiltDeg` 90: its table faces the viewer, for the front of the chest). */
    private void jewel(float[] parent, float x, float y, float z, float size, float tiltDeg, float locked, float pulse) {
        Matrix.translateM(tmp, 0, parent, 0, x, y, z);
        if (tiltDeg != 0) Matrix.rotateM(tmp, 0, tiltDeg, 1, 0, 0);
        Matrix.scaleM(tmp, 0, size, size, size);
        submit(tmp);
        GLES20.glUniform4f(shader.uColor, 0.62f, 0.2f, 1f, 1f);
        GLES20.glUniform1f(shader.uSpec, 0.72f);
        GLES20.glUniform1f(uGlow, 0.18f + 0.1f * pulse + locked * 0.45f);
        bind(0);
        gem.draw(shader);
    }

    /** Where a coin is on its way into the box (k: 0 at the card, 1 in the box). */
    private void flightPos(int who, int j, float k, float[] out) {
        float[] o = origins[who];
        int id = who * 31 + j;
        float tx = (rnd(id, 1) - 0.5f) * (W * 1.4f), tz = (rnd(id, 2) - 0.5f) * (D * 1.2f), ty = H - 0.22f;
        float mx = (o[0] + tx) * 0.5f + (rnd(id, 3) - 0.5f) * 0.5f;
        float my = Math.max(o[1], ty + 0.5f) + 0.55f + rnd(id, 4) * 0.35f;
        float mz = (o[2] + tz) * 0.5f + 0.3f;
        float u = 1 - k;
        out[0] = u * u * o[0] + 2 * u * k * mx + k * k * tx;
        out[1] = u * u * o[1] + 2 * u * k * my + k * k * ty;
        out[2] = u * u * o[2] + 2 * u * k * mz + k * k * tz;
    }

    private void coins(float f, float boxScale) {
        if (origins.length < line.contributors) return;
        GLES20.glUniform4f(shader.uColor, 1f, 0.84f, 0.46f, 1f);
        GLES20.glUniform1f(shader.uSpec, 0.8f);
        GLES20.glUniform1f(uGlow, 0.1f);
        bind(texCoin);
        // The trip is only drawn with the lid up; once shut the coins are inside it and out of sight.
        boolean visibleInside = f < Timeline.LOCK_END;
        int settled = 0;
        for (int i = 0; i < line.contributors; i++) {
            for (int j = 0; j < line.coinsEach; j++) {
                float start = line.launch(i, j), fl = line.flight();
                float k = (f - start) / fl;
                if (k < 0) continue;
                if (k >= 1) {
                    if (!visibleInside) continue;
                    // On the pile: coins lie where they fell, each landing on the last.
                    int id = i * 31 + j;
                    float tx = (rnd(id, 1) - 0.5f) * (W * 1.4f), tz = (rnd(id, 2) - 0.5f) * (D * 1.2f);
                    float y = H - 0.28f + (settled % 3) * 0.018f;
                    settled++;
                    Matrix.setIdentityM(m, 0);
                    Matrix.scaleM(m, 0, boxScale, boxScale, boxScale);
                    Matrix.translateM(m, 0, tx, y, tz);
                    Matrix.rotateM(m, 0, rnd(id, 7) * 360f, 0, 1, 0);
                    Matrix.rotateM(m, 0, (rnd(id, 8) - 0.5f) * 18f, 1, 0, 0);
                    Matrix.scaleM(m, 0, COIN_R, COIN_R * 0.2f, COIN_R);
                    submit(m);
                    coin.draw(shader);
                    continue;
                }
                float eased = k * k * (3 - 2 * k) * 0.35f + k * 0.65f;
                flightPos(i, j, eased, p3);
                int id = i * 31 + j;
                Matrix.setIdentityM(m, 0);
                Matrix.translateM(m, 0, p3[0], p3[1], p3[2]);
                // Tumbling on its own axis, faster at the start of the throw.
                Matrix.rotateM(m, 0, (0.5f + rnd(id, 5)) * 760f * (float) Math.sqrt(k) + rnd(id, 6) * 360f, rnd(id, 9) - 0.5f, 0.5f, rnd(id, 10) - 0.5f);
                float grow = Math.min(1f, k * 6f) * (1f - k * 0.1f);
                Matrix.scaleM(m, 0, COIN_R * grow, COIN_R * 0.2f * grow, COIN_R * grow);
                submit(m);
                coin.draw(shader);
            }
        }
    }

    private void particles(float f) {
        int q = quality;
        if (origins.length >= line.contributors) {
            int trail = q == 2 ? 3 : q == 1 ? 2 : 0;
            int burst = q == 2 ? 3 : q == 1 ? 1 : 0;
            float fl = line.flight();
            for (int i = 0; i < line.contributors; i++) {
                for (int j = 0; j < line.coinsEach; j++) {
                    float start = line.launch(i, j);
                    float k = (f - start) / fl;
                    if (k < 0) continue;
                    if (k < 1) {
                        // A short golden trail behind the coin.
                        for (int t = 1; t <= trail; t++) {
                            float kk = k - t * 0.05f;
                            if (kk <= 0) break;
                            flightPos(i, j, kk * kk * (3 - 2 * kk) * 0.35f + kk * 0.65f, q3);
                            billboard(texGlow, q3[0], q3[1], q3[2], 0.09f - t * 0.018f, 0, 1f, 0.78f, 0.32f, 0.55f - t * 0.13f);
                        }
                    } else if (k < 1.9f) {
                        // Sparks where it lands.
                        int id = i * 31 + j;
                        float tx = (rnd(id, 1) - 0.5f) * (W * 1.4f), tz = (rnd(id, 2) - 0.5f) * (D * 1.2f);
                        float age = (k - 1) / 0.9f;
                        for (int b = 0; b < burst; b++) {
                            float dx = (rnd(id, 20 + b) - 0.5f) * 0.8f, dz = (rnd(id, 30 + b) - 0.5f) * 0.6f;
                            float y = H - 0.15f + age * 0.55f * (0.6f + rnd(id, 40 + b)) - age * age * 0.35f;
                            billboard(texSpark, tx + dx * age, y, tz + dz * age + 0.1f, 0.08f, age * 90f, 1f, 0.9f, 0.55f, (1 - age) * 0.95f);
                        }
                    }
                }
            }
        }
        // The lock: one ring of gold thrown outward.
        float age = (f - Timeline.LOCK_END + 0.015f) / 0.13f;
        if (age > 0 && age < 1) {
            int n = q == 2 ? 26 : q == 1 ? 12 : 5;
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2 / n + rnd(i, 50);
                float sp = 1.1f + rnd(i, 51) * 1.3f;
                float x = (float) Math.cos(a) * sp * age;
                float y = 0.9f + (float) Math.sin(a) * sp * age * 0.8f - age * age * 0.8f;
                billboard(texSpark, x, y, D + 0.25f, 0.13f * (1 - age * 0.6f), age * 120f, 1f, 0.85f, 0.45f, (1 - age) * 0.95f);
            }
        }
    }

    // ================================================================= painted textures

    private static final class Art {
        static Bitmap background() {
            int w = 256, h = 512;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, h, new int[]{0xff160736, 0xff2a0d63, 0xff14052f}, new float[]{0f, 0.55f, 1f}, TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(new RadialGradient(w / 2f, h * 0.56f, h * 0.55f, new int[]{0x887a35d8, 0x00200a50}, null, TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            return b;
        }

        static Bitmap glow() {
            int s = 128;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f, new int[]{0xffffffff, 0x66ffffff, 0x00ffffff}, new float[]{0f, 0.4f, 1f}, TileMode.CLAMP));
            c.drawCircle(s / 2f, s / 2f, s / 2f, p);
            return b;
        }

        /** A four-pointed glint. */
        static Bitmap spark() {
            int s = 64;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(s / 2f, s / 2f, s * 0.2f, 0xffffffff, 0x00ffffff, TileMode.CLAMP));
            c.drawCircle(s / 2f, s / 2f, s * 0.2f, p);
            p.setShader(null);
            p.setColor(0xffffffff);
            Path star = new Path();
            float m = s / 2f, t = s * 0.05f;
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

        /** Soft rays turning behind the chest. */
        static Bitmap rays() {
            int s = 256;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f, new int[]{0xffffffff, 0x88ffffff, 0x00ffffff}, new float[]{0f, 0.35f, 1f}, TileMode.CLAMP));
            Path wedge = new Path();
            int n = 12;
            for (int i = 0; i < n; i++) {
                double a = i * 2 * Math.PI / n, w = Math.PI / n * 0.55;
                wedge.moveTo(s / 2f, s / 2f);
                wedge.lineTo(s / 2f + (float) Math.cos(a - w) * s, s / 2f + (float) Math.sin(a - w) * s);
                wedge.lineTo(s / 2f + (float) Math.cos(a + w) * s, s / 2f + (float) Math.sin(a + w) * s);
                wedge.close();
            }
            c.drawPath(wedge, p);
            return b;
        }

        /** Struck gold with a raised rim and a crown on its face. */
        static Bitmap coin() {
            int s = 192;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            float m = s / 2f;
            c.drawColor(0xffd9a02f);
            p.setShader(new LinearGradient(0, 0, s, s, new int[]{0xfffff0a8, 0xffe9b53c, 0xffa86f12}, null, TileMode.CLAMP));
            c.drawCircle(m, m, m, p);
            p.setShader(new LinearGradient(s, s, 0, 0, new int[]{0xffffe48a, 0xffd9a02f, 0xff9a650f}, null, TileMode.CLAMP));
            c.drawCircle(m, m, m * 0.82f, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(s * 0.018f);
            p.setColor(0xff8a5a0c);
            c.drawCircle(m, m, m * 0.78f, p);
            // Reeded edge.
            p.setStrokeWidth(s * 0.012f);
            for (int i = 0; i < 48; i++) {
                double a = i * Math.PI / 24;
                c.drawLine(m + (float) Math.cos(a) * m * 0.88f, m + (float) Math.sin(a) * m * 0.88f,
                        m + (float) Math.cos(a) * m * 0.98f, m + (float) Math.sin(a) * m * 0.98f, p);
            }
            p.setStyle(Paint.Style.FILL);
            Path crown = new Path();
            float w = s * 0.27f, t = m - s * 0.15f, bot = m + s * 0.16f;
            crown.moveTo(m - w, bot);
            crown.lineTo(m - w * 1.1f, t + s * 0.03f);
            crown.lineTo(m - w * 0.5f, m - s * 0.02f);
            crown.lineTo(m, t - s * 0.03f);
            crown.lineTo(m + w * 0.5f, m - s * 0.02f);
            crown.lineTo(m + w * 1.1f, t + s * 0.03f);
            crown.lineTo(m + w, bot);
            crown.close();
            p.setColor(0xff7c4f0a);
            c.save();
            c.translate(s * 0.012f, s * 0.018f);
            c.drawPath(crown, p);
            c.restore();
            p.setColor(0xfffff2b0);
            c.drawPath(crown, p);
            return b;
        }

        /** The chest's walls and lid: gold with engraved scrolls round a recessed panel of purple enamel. */
        static Bitmap panel() {
            int s = 512;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 0, s, s, new int[]{0xfff4cf6a, 0xffd9a43a, 0xfff0c55a, 0xffb98522}, null, TileMode.CLAMP));
            c.drawRect(0, 0, s, s, p);
            p.setShader(null);

            // Purple enamel, set in.
            RectF inner = new RectF(s * 0.16f, s * 0.2f, s * 0.84f, s * 0.8f);
            p.setShader(new LinearGradient(0, inner.top, 0, inner.bottom, new int[]{0xff4b1a94, 0xff2c0d62}, null, TileMode.CLAMP));
            c.drawRoundRect(inner, 26, 26, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            engrave(c, p, s * 0.012f, q -> c.drawRoundRect(inner, 26, 26, q));
            // Double border round the plate.
            RectF border = new RectF(s * 0.05f, s * 0.05f, s * 0.95f, s * 0.95f);
            engrave(c, p, s * 0.01f, q -> c.drawRoundRect(border, 18, 18, q));
            RectF border2 = new RectF(s * 0.085f, s * 0.085f, s * 0.915f, s * 0.915f);
            engrave(c, p, s * 0.005f, q -> c.drawRoundRect(border2, 12, 12, q));

            // Gold scrollwork on the enamel: a lozenge and leaves growing from it.
            p.setStrokeWidth(s * 0.009f);
            p.setColor(0xffe7bd55);
            float m = s / 2f;
            Path lozenge = new Path();
            lozenge.moveTo(m, m - s * 0.2f);
            lozenge.lineTo(m + s * 0.19f, m);
            lozenge.lineTo(m, m + s * 0.2f);
            lozenge.lineTo(m - s * 0.19f, m);
            lozenge.close();
            c.drawPath(lozenge, p);
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sy = -1; sy <= 1; sy += 2) {
                    Path leaf = new Path();
                    leaf.moveTo(m + sx * s * 0.20f, m);
                    leaf.cubicTo(m + sx * s * 0.27f, m + sy * s * 0.05f, m + sx * s * 0.31f, m + sy * s * 0.13f, m + sx * s * 0.26f, m + sy * s * 0.2f);
                    leaf.cubicTo(m + sx * s * 0.21f, m + sy * s * 0.23f, m + sx * s * 0.15f, m + sy * s * 0.19f, m + sx * s * 0.18f, m + sy * s * 0.13f);
                    c.drawPath(leaf, p);
                }
            }
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(m, m, s * 0.035f, p);

            // Rivets and little engraved dots round the gold.
            for (int i = 0; i < 12; i++) {
                float x = s * 0.14f + i * s * 0.065f;
                for (float y : new float[]{s * 0.14f, s * 0.86f}) {
                    p.setColor(0xff8a5d10);
                    c.drawCircle(x + 1, y + 2, s * 0.009f, p);
                    p.setColor(0xfffff0b8);
                    c.drawCircle(x, y, s * 0.008f, p);
                }
            }
            return b;
        }

        private interface Stroke {
            void draw(Paint p);
        }

        /** Cut into the metal: a dark line with a catch of light along one side. */
        private static void engrave(Canvas c, Paint p, float width, Stroke stroke) {
            p.setStrokeWidth(width);
            p.setColor(0xff7e540d);
            stroke.draw(p);
            c.save();
            c.translate(width * 0.7f, width * 0.8f);
            p.setColor(0xfffff0b8);
            p.setStrokeWidth(width * 0.5f);
            stroke.draw(p);
            c.restore();
        }
    }
}
