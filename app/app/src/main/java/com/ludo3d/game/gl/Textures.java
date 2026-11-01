package com.ludo3d.game.gl;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader.TileMode;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import com.ludo3d.game.game.Board;

/** Textures painted with Canvas at start-up: the board face, the dice faces, a soft shadow and a ring. */
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

    private static int lighten(int c, float f) {
        int r = (int) (Color.red(c) + (255 - Color.red(c)) * f);
        int g = (int) (Color.green(c) + (255 - Color.green(c)) * f);
        int b = (int) (Color.blue(c) + (255 - Color.blue(c)) * f);
        return Color.rgb(r, g, b);
    }

    /** The 15x15 board: yards, track, coloured start cells and home columns, star safe cells, centre. */
    static Bitmap board() {
        int size = 1024;
        float cell = size / 15f;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawColor(0xfff7f1e3);

        // Yards.
        for (int ci = 0; ci < 4; ci++) {
            int col = Board.COLOR_ARGB[ci];
            float[] o = Board.yardOrigin(ci);
            float x = o[0] * cell, y = o[1] * cell;
            p.setStyle(Paint.Style.FILL);
            p.setColor(col);
            c.drawRect(x, y, x + 6 * cell, y + 6 * cell, p);
            p.setColor(0xfffdfaf2);
            c.drawRoundRect(new RectF(x + 0.75f * cell, y + 0.75f * cell, x + 5.25f * cell, y + 5.25f * cell), cell * 0.5f, cell * 0.5f, p);
            for (int t = 0; t < 4; t++) {
                float[] s = Board.yardSlot(ci, t);
                float cx = (s[0] + 0.5f) * cell, cy = (s[1] + 0.5f) * cell;
                p.setStyle(Paint.Style.FILL);
                p.setColor(lighten(col, 0.55f));
                c.drawCircle(cx, cy, cell * 0.6f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(cell * 0.09f);
                p.setColor(col);
                c.drawCircle(cx, cy, cell * 0.6f, p);
            }
        }

        // Track.
        for (int i = 0; i < Board.TRACK_LEN; i++) {
            float[] t = Board.TRACK[i];
            int fill = Color.WHITE;
            for (int s = 0; s < 4; s++) if (Board.START_INDICES[s] == i) fill = Board.COLOR_ARGB[s];
            cellRect(c, p, t[0], t[1], cell, fill);
            if (Board.isSafe(i) && fill == Color.WHITE) star(c, p, (t[0] + 0.5f) * cell, (t[1] + 0.5f) * cell, cell * 0.32f, 0xffb9b2a0);
            if (fill != Color.WHITE) star(c, p, (t[0] + 0.5f) * cell, (t[1] + 0.5f) * cell, cell * 0.3f, 0xccffffff);
        }
        // Home columns.
        for (int ci = 0; ci < 4; ci++) {
            for (int k = 0; k < 5; k++) {
                float[] h = Board.homeCell(ci, k);
                cellRect(c, p, h[0], h[1], cell, Board.COLOR_ARGB[ci]);
            }
        }

        // Centre: four triangles pointing at the middle (red left, green top, yellow right, blue bottom).
        float a = 6 * cell, b = 9 * cell, m = 7.5f * cell;
        float[][][] tris = {
                {{a, a}, {a, b}, {m, m}},
                {{a, a}, {b, a}, {m, m}},
                {{b, a}, {b, b}, {m, m}},
                {{a, b}, {b, b}, {m, m}},
        };
        p.setStyle(Paint.Style.FILL);
        for (int ci = 0; ci < 4; ci++) {
            Path path = new Path();
            path.moveTo(tris[ci][0][0], tris[ci][0][1]);
            path.lineTo(tris[ci][1][0], tris[ci][1][1]);
            path.lineTo(tris[ci][2][0], tris[ci][2][1]);
            path.close();
            p.setColor(Board.COLOR_ARGB[ci]);
            c.drawPath(path, p);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(cell * 0.05f);
        p.setColor(0x55000000);
        c.drawLine(a, a, b, b, p);
        c.drawLine(b, a, a, b, p);
        c.drawRect(a, a, b, b, p);

        // Frame.
        p.setStrokeWidth(cell * 0.12f);
        p.setColor(0xff3b2a1a);
        c.drawRect(0, 0, size, size, p);
        return bmp;
    }

    private static void cellRect(Canvas c, Paint p, float col, float row, float cell, int fill) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(fill);
        c.drawRect(col * cell, row * cell, (col + 1) * cell, (row + 1) * cell, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(cell * 0.04f);
        p.setColor(0x40000000);
        c.drawRect(col * cell, row * cell, (col + 1) * cell, (row + 1) * cell, p);
    }

    private static void star(Canvas c, Paint p, float cx, float cy, float r, int color) {
        Path path = new Path();
        for (int k = 0; k < 10; k++) {
            double ang = -Math.PI / 2 + k * Math.PI / 5;
            float rr = k % 2 == 0 ? r : r * 0.45f;
            float x = cx + (float) (Math.cos(ang) * rr), y = cy + (float) (Math.sin(ang) * rr);
            if (k == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(path, p);
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
