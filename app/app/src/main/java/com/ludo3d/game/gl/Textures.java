package com.ludo3d.game.gl;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader.TileMode;
import android.opengl.GLES20;
import android.opengl.GLUtils;

/** Textures painted with Canvas at start-up: the dice faces, a soft shadow and a ring (the board: BoardThemes). */
final class Textures {
    private Textures() {}

    static int upload(Bitmap bmp, boolean mipmap) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, mipmap ? GLES20.GL_LINEAR_MIPMAP_LINEAR : GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        if (mipmap) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        bmp.recycle();
        return t[0];
    }

    /** 3x2 atlas of dice faces 1..6 (row-major), 256 px each (768x512; upload without mipmaps). */
    static Bitmap dice() {
        int s = 256;
        Bitmap bmp = Bitmap.createBitmap(s * 3, s * 2, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float[][][] pips = {
                {{.5f, .5f}},
                {{.27f, .27f}, {.73f, .73f}},
                {{.27f, .27f}, {.5f, .5f}, {.73f, .73f}},
                {{.27f, .27f}, {.73f, .27f}, {.27f, .73f}, {.73f, .73f}},
                {{.27f, .27f}, {.73f, .27f}, {.5f, .5f}, {.27f, .73f}, {.73f, .73f}},
                {{.27f, .25f}, {.73f, .25f}, {.27f, .5f}, {.73f, .5f}, {.27f, .75f}, {.73f, .75f}},
        };
        for (int v = 0; v < 6; v++) {
            float x = (v % 3) * s, y = (v / 3) * s;
            p.setColor(0xffd9dbe6);
            c.drawRect(x, y, x + s, y + s, p);
            p.setColor(Color.WHITE);
            c.drawRoundRect(new RectF(x + 6, y + 6, x + s - 6, y + s - 6), 40, 40, p);
            p.setColor(v == 0 ? 0xffe53945 : 0xff1b1f3b);
            for (float[] pp : pips[v]) c.drawCircle(x + pp[0] * s, y + pp[1] * s, v == 0 ? s * 0.13f : s * 0.085f, p);
        }
        return bmp;
    }

    static Bitmap shadow() {
        int s = 128;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f, new int[]{0xff000000, 0x88000000, 0x00000000}, new float[]{0f, 0.5f, 1f}, TileMode.CLAMP));
        c.drawCircle(s / 2f, s / 2f, s / 2f, p);
        return bmp;
    }

    static Bitmap ring() {
        int s = 128;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.1f);
        p.setColor(Color.WHITE);
        c.drawCircle(s / 2f, s / 2f, s * 0.4f, p);
        return bmp;
    }
}
