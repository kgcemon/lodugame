package com.ludox.game.gl;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pictures of the 3D gifts for shop cards and lists, rendered once from the real models on a small
 * off-screen context. One still image per model keeps long lists cheap: no GL view per row.
 */
public final class GiftThumbs {
    public interface Cb {
        void done(Bitmap b);
    }

    private static final int SIZE = 192;
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    private static final Map<String, List<Cb>> WAITING = new HashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Handler worker;

    private static EGLDisplay display;
    private static EGLSurface surface;
    private static Shader shader;
    private static int uGlow, uSweep;
    private static GiftModels models;
    private static boolean failed;

    private GiftThumbs() {}

    /** Forget the pictures (views already showing one keep it). */
    public static void trim() {
        CACHE.clear();
    }

    /** Calls back on the main thread (at once if already rendered); a phone without a context gets null. */
    public static void get(String model, Cb cb) {
        Bitmap have = CACHE.get(model);
        if (have != null) {
            cb.done(have);
            return;
        }
        List<Cb> w = WAITING.get(model);
        if (w != null) {
            w.add(cb);
            return;
        }
        w = new ArrayList<>();
        w.add(cb);
        WAITING.put(model, w);
        if (worker == null) {
            HandlerThread t = new HandlerThread("gift-thumbs");
            t.start();
            worker = new Handler(t.getLooper());
        }
        worker.post(() -> {
            Bitmap b = null;
            try {
                b = render(model);
            } catch (RuntimeException e) {
                failed = true;
            }
            Bitmap out = b;
            MAIN.post(() -> {
                if (out != null) CACHE.put(model, out);
                List<Cb> all = WAITING.remove(model);
                if (all != null) for (Cb c : all) c.done(out);
            });
        });
    }

    private static boolean setup() {
        if (shader != null) return true;
        if (failed) return false;
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        if (!EGL14.eglInitialize(display, v, 0, v, 1)) return false;
        int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
        EGLConfig[] cfg = new EGLConfig[1];
        int[] n = new int[1];
        if (!EGL14.eglChooseConfig(display, attrs, 0, cfg, 0, 1, n, 0) || n[0] == 0) return false;
        EGLContext ctx = EGL14.eglCreateContext(display, cfg[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        surface = EGL14.eglCreatePbufferSurface(display, cfg[0], new int[]{EGL14.EGL_WIDTH, SIZE, EGL14.EGL_HEIGHT, SIZE, EGL14.EGL_NONE}, 0);
        if (ctx == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, ctx)) return false;
        shader = new Shader(Shader.VS, TreasureRenderer.METAL_FS);
        uGlow = GLES20.glGetUniformLocation(shader.program, "uGlow");
        uSweep = GLES20.glGetUniformLocation(shader.program, "uSweep");
        models = new GiftModels();
        return true;
    }

    private static Bitmap render(String model) {
        if (!setup()) return null;
        GLES20.glViewport(0, 0, SIZE, SIZE);
        GLES20.glClearColor(0, 0, 0, 0);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glUseProgram(shader.program);
        GLES20.glUniform3f(shader.uLight, 0.35f, 0.9f, 0.75f);
        GLES20.glUniform3f(shader.uEye, 0, 0, 3000f);
        GLES20.glUniform1f(uSweep, 1.2f);
        float[] vp = new float[16], base = new float[16];
        Matrix.orthoM(vp, 0, -1.25f, 1.25f, -1.25f, 1.25f, -10, 10);
        Matrix.setIdentityM(base, 0);
        Matrix.rotateM(base, 0, 10, 1, 0, 0);
        Matrix.rotateM(base, 0, "car".equals(model) ? 55 : -14, 0, 1, 0);
        models.draw(model, shader, uGlow, vp, base, 0.35f, 2f, 0f);
        GLES20.glFinish();
        ByteBuffer buf = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(0, 0, SIZE, SIZE, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        Bitmap raw = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        raw.copyPixelsFromBuffer(buf);
        // GL's rows run bottom-up.
        android.graphics.Matrix flip = new android.graphics.Matrix();
        flip.preScale(1, -1);
        Bitmap out = Bitmap.createBitmap(raw, 0, 0, SIZE, SIZE, flip, false);
        raw.recycle();
        return out;
    }
}
