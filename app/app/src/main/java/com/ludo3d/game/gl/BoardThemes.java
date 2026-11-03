package com.ludo3d.game.gl;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader.TileMode;

import com.ludo3d.game.game.Board;

/**
 * The board themes sold in the shop (the server's catalogue is shared/boards.ts) and the
 * painting of the board face for each. The players' colours never change, so a royal
 * board plays exactly like the classic one: only the panels, track, trim and the wooden
 * (or golden) rim around it change. An unknown id paints the classic board.
 */
public final class BoardThemes {
    private BoardThemes() {}

    public static final class Theme {
        public final String id, name;
        /** Board face behind everything, the yards' inner panels (centre, edge), the track cells. */
        final int base, panel, panelEdge, track;
        /** Cell lines, safe-cell stars, the painted outer edge; trim 0 = no royal ornaments. */
        final int grid, safe, edge, trim;
        /** Black marble: light veins over the panels. */
        final boolean veins;
        /** The 3D base and raised rim (0..1 RGB) and how shiny the rim is. */
        public final float[] wood, rim;
        final float rimSpec;

        Theme(String id, String name, int base, int panel, int panelEdge, int track, int grid, int safe, int edge, int trim, boolean veins,
              float[] wood, float[] rim, float rimSpec) {
            this.id = id;
            this.name = name;
            this.base = base;
            this.panel = panel;
            this.panelEdge = panelEdge;
            this.track = track;
            this.grid = grid;
            this.safe = safe;
            this.edge = edge;
            this.trim = trim;
            this.veins = veins;
            this.wood = wood;
            this.rim = rim;
            this.rimSpec = rimSpec;
        }

        public boolean royal() {
            return trim != 0;
        }
    }

    public static final Theme CLASSIC = new Theme("classic", "Classic", 0xfff7f1e3, 0xfffdfaf2, 0xfffdfaf2, Color.WHITE,
            0x40000000, 0xffb9b2a0, 0xff3b2a1a, 0, false,
            new float[]{0.40f, 0.26f, 0.15f}, new float[]{0.33f, 0.21f, 0.12f}, 0.18f);

    private static final Theme[] ALL = {
            CLASSIC,
            new Theme("royal_gold", "Royal Gold", 0xfff3dca0, 0xfffff6dc, 0xfff1d68f, 0xfffffaeb,
                    0x55a07a1a, 0xffd4a017, 0xffb8860b, 0xffd4a017, false,
                    new float[]{0.42f, 0.08f, 0.10f}, new float[]{0.86f, 0.66f, 0.18f}, 0.9f),
            new Theme("maharaja", "Maharaja", 0xfff2b8a0, 0xfffde7d8, 0xfff0b49c, 0xfffff3e8,
                    0x55801020, 0xffc8102e, 0xff7a0a1a, 0xffe0b040, false,
                    new float[]{0.45f, 0.05f, 0.08f}, new float[]{0.62f, 0.06f, 0.12f}, 0.6f),
            new Theme("emerald_palace", "Emerald Palace", 0xffbfe6cc, 0xffeaf8ef, 0xffb4e0c3, 0xfff7fff9,
                    0x55105a30, 0xff0f8a4e, 0xff0a4f2d, 0xffd4af37, false,
                    new float[]{0.04f, 0.25f, 0.14f}, new float[]{0.80f, 0.62f, 0.20f}, 0.9f),
            new Theme("sapphire_throne", "Sapphire Throne", 0xffbccdf5, 0xffeaf0ff, 0xffb4c6f2, 0xfff6f8ff,
                    0x55102a6e, 0xff1a4fc4, 0xff0d2a6e, 0xffc9d1e0, false,
                    new float[]{0.05f, 0.10f, 0.30f}, new float[]{0.75f, 0.78f, 0.84f}, 1f),
            new Theme("black_marble", "Black Marble Empire", 0xff141318, 0xff34313c, 0xff121116, 0xfff3efe6,
                    0x66000000, 0xffd4a017, 0xff000000, 0xffe8b923, true,
                    new float[]{0.06f, 0.06f, 0.07f}, new float[]{0.90f, 0.70f, 0.20f}, 1f),
    };

    /** The theme for this id (classic when unknown or null). */
    public static Theme get(String id) {
        if (id != null) for (Theme t : ALL) if (t.id.equals(id)) return t;
        return CLASSIC;
    }

    public static boolean known(String id) {
        if (id == null) return false;
        for (Theme t : ALL) if (t.id.equals(id)) return true;
        return false;
    }

    /** The board face as a square bitmap (for the 3D board and the shop's previews). */
    public static Bitmap bitmap(Theme t, int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        paint(new Canvas(bmp), size, t);
        return bmp;
    }

    /** The 15x15 board: yards, track, coloured start cells and home columns, star safe cells, centre. */
    public static void paint(Canvas c, int size, Theme t) {
        float cell = size / 15f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawColor(t.base);

        // Yards.
        for (int ci = 0; ci < 4; ci++) {
            int col = Board.COLOR_ARGB[ci];
            float[] o = Board.yardOrigin(ci);
            float x = o[0] * cell, y = o[1] * cell;
            p.setStyle(Paint.Style.FILL);
            p.setColor(col);
            c.drawRect(x, y, x + 6 * cell, y + 6 * cell, p);
            RectF panel = new RectF(x + 0.75f * cell, y + 0.75f * cell, x + 5.25f * cell, y + 5.25f * cell);
            p.setShader(new RadialGradient(panel.centerX(), panel.centerY(), panel.width() * 0.75f, t.panel, t.panelEdge, TileMode.CLAMP));
            c.drawRoundRect(panel, cell * 0.5f, cell * 0.5f, p);
            p.setShader(null);
            if (t.veins) veins(c, p, panel, cell, ci);
            if (t.royal()) {
                // Gold inlay round the yard and the panel, and a jewel in each panel corner.
                p.setStyle(Paint.Style.STROKE);
                p.setColor(t.trim);
                p.setStrokeWidth(cell * 0.08f);
                c.drawRect(x + cell * 0.18f, y + cell * 0.18f, x + 6 * cell - cell * 0.18f, y + 6 * cell - cell * 0.18f, p);
                p.setStrokeWidth(cell * 0.06f);
                c.drawRoundRect(panel, cell * 0.5f, cell * 0.5f, p);
                p.setStyle(Paint.Style.FILL);
                float in = cell * 0.55f;
                jewel(c, p, panel.left + in, panel.top + in, cell * 0.16f, t.trim);
                jewel(c, p, panel.right - in, panel.top + in, cell * 0.16f, t.trim);
                jewel(c, p, panel.left + in, panel.bottom - in, cell * 0.16f, t.trim);
                jewel(c, p, panel.right - in, panel.bottom - in, cell * 0.16f, t.trim);
            }
            for (int k = 0; k < 4; k++) {
                float[] s = Board.yardSlot(ci, k);
                float cx = (s[0] + 0.5f) * cell, cy = (s[1] + 0.5f) * cell;
                p.setStyle(Paint.Style.FILL);
                p.setColor(lighten(col, 0.55f));
                c.drawCircle(cx, cy, cell * 0.6f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(cell * 0.09f);
                p.setColor(col);
                c.drawCircle(cx, cy, cell * 0.6f, p);
                if (t.royal()) {
                    p.setStrokeWidth(cell * 0.04f);
                    p.setColor(t.trim);
                    c.drawCircle(cx, cy, cell * 0.68f, p);
                }
            }
        }

        // Track.
        for (int i = 0; i < Board.TRACK_LEN; i++) {
            float[] tr = Board.TRACK[i];
            int start = -1;
            for (int s = 0; s < 4; s++) if (Board.START_INDICES[s] == i) start = s;
            cellRect(c, p, tr[0], tr[1], cell, start >= 0 ? Board.COLOR_ARGB[start] : t.track, t.grid);
            float cx = (tr[0] + 0.5f) * cell, cy = (tr[1] + 0.5f) * cell;
            if (start >= 0) star(c, p, cx, cy, cell * 0.3f, 0xccffffff);
            else if (Board.isSafe(i)) star(c, p, cx, cy, cell * 0.32f, t.safe);
        }
        // Home columns.
        for (int ci = 0; ci < 4; ci++) {
            for (int k = 0; k < 5; k++) {
                float[] h = Board.homeCell(ci, k);
                cellRect(c, p, h[0], h[1], cell, Board.COLOR_ARGB[ci], t.grid);
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
        p.setColor(t.royal() ? t.trim : 0x55000000);
        c.drawLine(a, a, b, b, p);
        c.drawLine(b, a, a, b, p);
        p.setStrokeWidth(t.royal() ? cell * 0.1f : cell * 0.05f);
        c.drawRect(a, a, b, b, p);
        if (t.royal()) crown(c, p, m, m, cell * 0.62f, t);

        // Frame.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(cell * 0.12f);
        p.setColor(t.edge);
        c.drawRect(0, 0, size, size, p);
        if (t.royal()) {
            p.setStrokeWidth(cell * 0.05f);
            p.setColor(t.trim);
            float in = cell * 0.1f;
            c.drawRect(in, in, size - in, size - in, p);
        }
    }

    private static int lighten(int c, float f) {
        int r = (int) (Color.red(c) + (255 - Color.red(c)) * f);
        int g = (int) (Color.green(c) + (255 - Color.green(c)) * f);
        int b = (int) (Color.blue(c) + (255 - Color.blue(c)) * f);
        return Color.rgb(r, g, b);
    }

    private static void cellRect(Canvas c, Paint p, float col, float row, float cell, int fill, int grid) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(fill);
        c.drawRect(col * cell, row * cell, (col + 1) * cell, (row + 1) * cell, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(cell * 0.04f);
        p.setColor(grid);
        c.drawRect(col * cell, row * cell, (col + 1) * cell, (row + 1) * cell, p);
    }

    static void star(Canvas c, Paint p, float cx, float cy, float r, int color) {
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

    /** A small diamond-shaped gem. */
    private static void jewel(Canvas c, Paint p, float cx, float cy, float r, int color) {
        Path path = new Path();
        path.moveTo(cx, cy - r);
        path.lineTo(cx + r, cy);
        path.lineTo(cx, cy + r);
        path.lineTo(cx - r, cy);
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(path, p);
        p.setColor(0x88ffffff);
        c.drawCircle(cx - r * 0.25f, cy - r * 0.25f, r * 0.22f, p);
    }

    /** A golden medallion with a crown, in the middle of the board. */
    private static void crown(Canvas c, Paint p, float cx, float cy, float r, Theme t) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(cx, cy - r, cx, cy + r, lighten(t.trim, 0.45f), t.trim, TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.1f);
        p.setColor(t.edge);
        c.drawCircle(cx, cy, r, p);
        // Crown: a band with three points, a pearl on each.
        float w = r * 0.62f, top = cy - r * 0.42f, band = cy + r * 0.12f, bottom = cy + r * 0.38f;
        Path path = new Path();
        path.moveTo(cx - w, bottom);
        path.lineTo(cx - w, top + r * 0.12f);
        path.lineTo(cx - w * 0.5f, band - r * 0.12f);
        path.lineTo(cx, top);
        path.lineTo(cx + w * 0.5f, band - r * 0.12f);
        path.lineTo(cx + w, top + r * 0.12f);
        path.lineTo(cx + w, bottom);
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(t.edge == 0xff000000 ? 0xff2a1a00 : t.edge);
        c.drawPath(path, p);
        p.setColor(t.trim);
        c.drawCircle(cx - w, top + r * 0.08f, r * 0.09f, p);
        c.drawCircle(cx, top - r * 0.04f, r * 0.1f, p);
        c.drawCircle(cx + w, top + r * 0.08f, r * 0.09f, p);
        p.setColor(0xffe0115f);
        c.drawCircle(cx, (band + bottom) / 2f, r * 0.08f, p);
    }

    /** Soft light veins across a dark marble panel (the same pattern every time). */
    private static void veins(Canvas c, Paint p, RectF panel, float cell, int seed) {
        java.util.Random rnd = new java.util.Random(31L * seed + 7);
        p.setStyle(Paint.Style.STROKE);
        c.save();
        Path clip = new Path();
        clip.addRoundRect(panel, cell * 0.5f, cell * 0.5f, Path.Direction.CW);
        c.clipPath(clip);
        for (int v = 0; v < 5; v++) {
            Path path = new Path();
            float x = panel.left + rnd.nextFloat() * panel.width(), y = panel.top;
            path.moveTo(x, y);
            for (int k = 0; k < 6; k++) {
                x += (rnd.nextFloat() - 0.4f) * panel.width() * 0.35f;
                y += panel.height() / 5f;
                path.lineTo(x, y);
            }
            p.setStrokeWidth(cell * (0.03f + rnd.nextFloat() * 0.05f));
            p.setColor(v % 2 == 0 ? 0x30ffffff : 0x20e8b923);
            c.drawPath(path, p);
        }
        c.restore();
    }
}
