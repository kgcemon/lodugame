package com.ludox.game.gl;

import android.opengl.GLSurfaceView;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

/**
 * Picks the best EGL config the phone offers: 4x MSAA with a 24-bit depth buffer (smooth
 * board lines, precise depth), falling back step by step to a plain 16-bit-depth config.
 */
public final class ConfigChooser implements GLSurfaceView.EGLConfigChooser {
    private static final int EGL_OPENGL_ES2_BIT = 4;

    @Override
    public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
        int[][] wanted = {
                {24, 4}, {16, 4}, {24, 0}, {16, 0},
        };
        for (int[] w : wanted) {
            EGLConfig c = find(egl, display, w[0], w[1]);
            if (c != null) return c;
        }
        throw new IllegalArgumentException("No OpenGL ES 2 config available");
    }

    private static EGLConfig find(EGL10 egl, EGLDisplay display, int depth, int samples) {
        int[] attrs = {
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, depth,
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_SAMPLE_BUFFERS, samples > 0 ? 1 : 0,
                EGL10.EGL_SAMPLES, samples,
                EGL10.EGL_NONE,
        };
        int[] count = new int[1];
        if (!egl.eglChooseConfig(display, attrs, null, 0, count) || count[0] <= 0) return null;
        EGLConfig[] configs = new EGLConfig[count[0]];
        if (!egl.eglChooseConfig(display, attrs, configs, configs.length, count)) return null;
        return configs.length > 0 ? configs[0] : null;
    }
}
