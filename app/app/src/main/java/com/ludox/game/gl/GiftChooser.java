package com.ludox.game.gl;

import android.opengl.GLSurfaceView;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

/** A config with an alpha channel, for gifts drawn on a see-through surface: 4x MSAA where the phone offers it. */
public final class GiftChooser implements GLSurfaceView.EGLConfigChooser {
    private static final int EGL_OPENGL_ES2_BIT = 4;

    @Override
    public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
        for (int samples : new int[]{4, 2, 0}) {
            if (samples > ConfigChooser.maxSamples) continue;
            int[] attrs = {
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16,
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL10.EGL_SAMPLE_BUFFERS, samples > 0 ? 1 : 0,
                    EGL10.EGL_SAMPLES, samples,
                    EGL10.EGL_NONE,
            };
            int[] n = new int[1];
            if (!egl.eglChooseConfig(display, attrs, null, 0, n) || n[0] <= 0) continue;
            EGLConfig[] c = new EGLConfig[n[0]];
            if (egl.eglChooseConfig(display, attrs, c, c.length, n) && c.length > 0) return c[0];
        }
        throw new IllegalArgumentException("No translucent OpenGL ES 2 config available");
    }
}
