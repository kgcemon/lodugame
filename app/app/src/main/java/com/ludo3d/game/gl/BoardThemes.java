package com.ludo3d.game.gl;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Shader.TileMode;

import com.ludo3d.game.game.Board;

import java.util.Random;

/**
 * The board themes sold in the shop (the server's catalogue is shared/boards.ts) and the
 * painting of the board face for each. The players' colours never change, so a premium
 * board plays exactly like the classic one: only the panels, track, trim, ornaments and
 * the wooden (or golden) rim around it change. An unknown id paints the classic board.
 */
public final class BoardThemes {
    private BoardThemes() {}

    /** What sits in the centre medallion and on the safe cells. */
    enum Motif { CROWN, HEART, DIAMOND, FLAG }

    /** What is worked into the yards' inner panels. */
    enum Pattern { NONE, VEINS, HEARTS, QUILT, FLAG }

    public static final class Theme {
        public final String id, name;
        /** Board face behind everything, the yards' inner panels (centre, edge), the track cells. */
        final int base, panel, panelEdge, track;
        /** Cell lines, safe-cell symbols, the painted outer edge; trim 0 = plain (classic) board. */
        final int grid, safe, edge, trim;
        final Motif motif;
        final Pattern pattern;
        /** The 3D base and raised rim (0..1 RGB) and how shiny the rim is. */
        public final float[] wood, rim;
        final float rimSpec;

        Theme(String id, String name, int base, int panel, int panelEdge, int track, int grid, int safe, int edge, int trim,
              Motif motif, Pattern pattern, float[] wood, float[] rim, float rimSpec) {
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
            this.motif = motif;
            this.pattern = pattern;
            this.wood = wood;
            this.rim = rim;
            this.rimSpec = rimSpec;
        }

        public boolean royal() {
            return trim != 0;
        }
    }

    public static final Theme CLASSIC = new Theme("classic", "Classic", 0xfff7f1e3, 0xfffdfaf2, 0xfffdfaf2, Color.WHITE,
            0x40000000, 0xffb9b2a0, 0xff3b2a1a, 0, Motif.CROWN, Pattern.NONE,
            new float[]{0.40f, 0.26f, 0.15f}, new float[]{0.33f, 0.21f, 0.12f}, 0.18f);

    private static final Theme[] ALL = {
            CLASSIC,
            new Theme("royal_gold", "Royal Gold", 0xfff3dca0, 0xfffff8e4, 0xffecd08a, 0xfffffaeb,
                    0x55a07a1a, 0xffd4a017, 0xff8a5a00, 0xffe0b23a, Motif.CROWN, Pattern.NONE,
                    new float[]{0.42f, 0.08f, 0.10f}, new float[]{0.92f, 0.72f, 0.22f}, 1f),
            new Theme("love_story", "Love Story", 0xffffd6e4, 0xfffff1f6, 0xffffbcd2, 0xffffeef4,
                    0x40c2185b, 0xffe91e63, 0xff880e4f, 0xffe8a87c, Motif.HEART, Pattern.HEARTS,
                    new float[]{0.55f, 0.10f, 0.25f}, new float[]{0.93f, 0.64f, 0.56f}, 1f),
            new Theme("billionaire", "Billionaire", 0xff0b0b0d, 0xff26221a, 0xff0a0906, 0xfffff6dc,
                    0x77b8860b, 0xff7fe3ff, 0xff000000, 0xffffcf40, Motif.DIAMOND, Pattern.QUILT,
                    new float[]{0.05f, 0.05f, 0.05f}, new float[]{1f, 0.80f, 0.26f}, 1.2f),
            new Theme("bangladesh", "Bangladesh", 0xff006a4e, 0xff0a7a5a, 0xff005038, 0xffffffff,
                    0x40004d38, 0xfff42a41, 0xff003a29, 0xffe9c46a, Motif.FLAG, Pattern.FLAG,
                    new float[]{0.0f, 0.24f, 0.17f}, new float[]{0.0f, 0.45f, 0.32f}, 0.6f),
            new Theme("maharaja", "Maharaja", 0xfff2b8a0, 0xfffde9dc, 0xffeeac92, 0xfffff3e8,
                    0x55801020, 0xffc8102e, 0xff6a0816, 0xffe8b84a, Motif.CROWN, Pattern.NONE,
                    new float[]{0.45f, 0.05f, 0.08f}, new float[]{0.66f, 0.07f, 0.13f}, 0.7f),
            new Theme("emerald_palace", "Emerald Palace", 0xffbfe6cc, 0xffecf9f1, 0xffaedcbe, 0xfff7fff9,
                    0x55105a30, 0xff0f8a4e, 0xff0a4f2d, 0xffdcb64a, Motif.CROWN, Pattern.NONE,
                    new float[]{0.04f, 0.25f, 0.14f}, new float[]{0.86f, 0.67f, 0.22f}, 1f),
            new Theme("sapphire_throne", "Sapphire Throne", 0xffbccdf5, 0xffecf1ff, 0xffaebff0, 0xfff6f8ff,
                    0x55102a6e, 0xff1a4fc4, 0xff0d2a6e, 0xffd3dae6, Motif.CROWN, Pattern.NONE,
                    new float[]{0.05f, 0.10f, 0.30f}, new float[]{0.80f, 0.83f, 0.90f}, 1.1f),
            new Theme("black_marble", "Black Marble Empire", 0xff141318, 0xff3a3642, 0xff121116, 0xfff3efe6,
                    0x66000000, 0xffd4a017, 0xff000000, 0xffe8b923, Motif.CROWN, Pattern.VEINS,
                    new float[]{0.06f, 0.06f, 0.07f}, new float[]{0.92f, 0.72f, 0.22f}, 1.1f),
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

    // BD flag red disc.
    private static final int FLAG_RED = 0xfff42a41;

    /** The 15x15 board: yards, track, coloured start cells and home columns, safe cells, centre. */
    public static void paint(Canvas c, int size, Theme t) {
        float cell = size / 15f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean rich = t.royal();
        c.drawColor(t.base);

        // Yards.
        for (int ci = 0; ci < 4; ci++) {
            int col = Board.COLOR_ARGB[ci];
            float[] o = Board.yardOrigin(ci);
            float x = o[0] * cell, y = o[1] * cell, w = 6 * cell;
            p.setStyle(Paint.Style.FILL);
            if (rich) {
                // Lit from the top-left: a glossy, shaded yard instead of a flat one.
                shade(p, new LinearGradient(x, y, x + w, y + w, lighten(col, 0.22f), darken(col, 0.22f), TileMode.CLAMP));
            } else {
                p.setColor(col);
            }
            c.drawRect(x, y, x + w, y + w, p);
            p.setShader(null);

            RectF panel = new RectF(x + 0.75f * cell, y + 0.75f * cell, x + 5.25f * cell, y + 5.25f * cell);
            float round = cell * 0.5f;
            if (rich) {
                // A soft drop shadow so the panel sits on the yard.
                p.setColor(0x40000000);
                c.drawRoundRect(new RectF(panel.left + cell * 0.06f, panel.top + cell * 0.1f, panel.right + cell * 0.06f, panel.bottom + cell * 0.1f), round, round, p);
            }
            if (t.pattern == Pattern.FLAG) {
                shade(p, new LinearGradient(panel.left, panel.top, panel.right, panel.bottom, t.panel, t.panelEdge, TileMode.CLAMP));
            } else {
                shade(p, new RadialGradient(panel.centerX(), panel.centerY(), panel.width() * 0.75f, t.panel, t.panelEdge, TileMode.CLAMP));
            }
            c.drawRoundRect(panel, round, round, p);
            p.setShader(null);
            pattern(c, p, panel, cell, ci, t);
            if (rich) {
                // Gold inlay round the yard and the panel, and a jewel in each panel corner.
                p.setStyle(Paint.Style.STROKE);
                shade(p, metal(t.trim, x, y, x + w, y + w));
                p.setStrokeWidth(cell * 0.1f);
                float in = cell * 0.2f;
                c.drawRect(x + in, y + in, x + w - in, y + w - in, p);
                p.setStrokeWidth(cell * 0.07f);
                c.drawRoundRect(panel, round, round, p);
                p.setShader(null);
                // Inner shadow along the panel's top edge.
                p.setStrokeWidth(cell * 0.05f);
                p.setColor(0x26000000);
                c.drawRoundRect(new RectF(panel.left + cell * 0.08f, panel.top + cell * 0.08f, panel.right - cell * 0.08f, panel.bottom - cell * 0.08f), round, round, p);
                float j = cell * 0.55f, jr = cell * 0.17f;
                int gem = t.motif == Motif.HEART ? 0xffe91e63 : t.motif == Motif.DIAMOND ? 0xff9eeaff : t.trim;
                symbol(c, p, panel.left + j, panel.top + j, jr, gem, t);
                symbol(c, p, panel.right - j, panel.top + j, jr, gem, t);
                symbol(c, p, panel.left + j, panel.bottom - j, jr, gem, t);
                symbol(c, p, panel.right - j, panel.bottom - j, jr, gem, t);
            }
            for (int k = 0; k < 4; k++) {
                float[] s = Board.yardSlot(ci, k);
                socket(c, p, (s[0] + 0.5f) * cell, (s[1] + 0.5f) * cell, cell, col, t);
            }
        }

        // Track.
        for (int i = 0; i < Board.TRACK_LEN; i++) {
            float[] tr = Board.TRACK[i];
            int start = -1;
            for (int s = 0; s < 4; s++) if (Board.START_INDICES[s] == i) start = s;
            cellRect(c, p, tr[0], tr[1], cell, start >= 0 ? Board.COLOR_ARGB[start] : t.track, t.grid, rich);
            float cx = (tr[0] + 0.5f) * cell, cy = (tr[1] + 0.5f) * cell;
            if (start >= 0) star(c, p, cx, cy, cell * 0.3f, 0xccffffff);
            else if (Board.isSafe(i)) safeMark(c, p, cx, cy, cell, t);
        }
        // Home columns.
        for (int ci = 0; ci < 4; ci++) {
            for (int k = 0; k < 5; k++) {
                float[] h = Board.homeCell(ci, k);
                cellRect(c, p, h[0], h[1], cell, Board.COLOR_ARGB[ci], t.grid, rich);
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
            int col = Board.COLOR_ARGB[ci];
            if (rich) {
                float ex = (tris[ci][0][0] + tris[ci][1][0]) / 2f, ey = (tris[ci][0][1] + tris[ci][1][1]) / 2f;
                shade(p, new LinearGradient(ex, ey, m, m, darken(col, 0.15f), lighten(col, 0.25f), TileMode.CLAMP));
            } else {
                p.setColor(col);
            }
            c.drawPath(path, p);
            p.setShader(null);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(cell * 0.05f);
        if (rich) shade(p, metal(t.trim, a, a, b, b));
        else p.setColor(0x55000000);
        c.drawLine(a, a, b, b, p);
        c.drawLine(b, a, a, b, p);
        p.setStrokeWidth(rich ? cell * 0.12f : cell * 0.05f);
        c.drawRect(a, a, b, b, p);
        p.setShader(null);
        if (rich) medallion(c, p, m, m, cell * 0.78f, t);

        // Frame.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(cell * 0.14f);
        p.setColor(t.edge);
        c.drawRect(0, 0, size, size, p);
        if (rich) {
            shade(p, metal(t.trim, 0, 0, size, size));
            p.setStrokeWidth(cell * 0.08f);
            float in = cell * 0.13f;
            c.drawRect(in, in, size - in, size - in, p);
            p.setShader(null);
            // A gem on each corner of the frame.
            float g = cell * 0.32f;
            int gem = t.motif == Motif.HEART ? 0xffe91e63 : t.motif == Motif.DIAMOND ? 0xff9eeaff : t.motif == Motif.FLAG ? FLAG_RED : t.trim;
            for (float[] k : new float[][]{{g, g}, {size - g, g}, {g, size - g}, {size - g, size - g}}) {
                p.setStyle(Paint.Style.FILL);
                shade(p, metal(t.trim, k[0] - g, k[1] - g, k[0] + g, k[1] + g));
                c.drawCircle(k[0], k[1], g * 0.85f, p);
                p.setShader(null);
                symbol(c, p, k[0], k[1], g * 0.55f, gem, t);
            }
        }
    }

    // ------------------------------------------------------------------ pieces

    /** Fill with a gradient at full strength (a paint's alpha also fades its shader). */
    private static void shade(Paint p, Shader s) {
        p.setColor(Color.BLACK);
        p.setShader(s);
    }

    private static int lighten(int c, float f) {
        int r = (int) (Color.red(c) + (255 - Color.red(c)) * f);
        int g = (int) (Color.green(c) + (255 - Color.green(c)) * f);
        int b = (int) (Color.blue(c) + (255 - Color.blue(c)) * f);
        return Color.argb(Color.alpha(c), r, g, b);
    }

    private static int darken(int c, float f) {
        return Color.argb(Color.alpha(c), (int) (Color.red(c) * (1 - f)), (int) (Color.green(c) * (1 - f)), (int) (Color.blue(c) * (1 - f)));
    }

    /** Polished metal along a diagonal: bright, deep, bright, deep (gold, rose gold, silver). */
    private static Shader metal(int c, float x0, float y0, float x1, float y1) {
        return new LinearGradient(x0, y0, x1, y1,
                new int[]{lighten(c, 0.55f), c, darken(c, 0.3f), lighten(c, 0.35f), darken(c, 0.2f)},
                new float[]{0f, 0.3f, 0.5f, 0.7f, 1f}, TileMode.MIRROR);
    }

    private static void cellRect(Canvas c, Paint p, float col, float row, float cell, int fill, int grid, boolean rich) {
        float x = col * cell, y = row * cell;
        p.setStyle(Paint.Style.FILL);
        if (rich) {
            shade(p, new LinearGradient(x, y, x, y + cell, lighten(fill, 0.12f), darken(fill, 0.06f), TileMode.CLAMP));
        } else {
            p.setColor(fill);
        }
        c.drawRect(x, y, x + cell, y + cell, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(cell * 0.04f);
        p.setColor(grid);
        c.drawRect(x, y, x + cell, y + cell, p);
    }

    /** A pawn's home spot in the yard: a sunken socket ringed in its colour (and gold). */
    private static void socket(Canvas c, Paint p, float cx, float cy, float cell, int col, Theme t) {
        float r = cell * 0.6f;
        p.setStyle(Paint.Style.FILL);
        if (t.royal()) {
            shade(p, new RadialGradient(cx - r * 0.2f, cy - r * 0.25f, r * 1.1f, lighten(col, 0.7f), lighten(col, 0.3f), TileMode.CLAMP));
        } else {
            p.setColor(lighten(col, 0.55f));
        }
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(cell * 0.09f);
        p.setColor(col);
        c.drawCircle(cx, cy, r, p);
        if (t.royal()) {
            p.setColor(0x33000000);
            p.setStrokeWidth(cell * 0.05f);
            c.drawArc(new RectF(cx - r * 0.86f, cy - r * 0.86f, cx + r * 0.86f, cy + r * 0.86f), 200, 140, false, p);
            shade(p, metal(t.trim, cx - r, cy - r, cx + r, cy + r));
            p.setStrokeWidth(cell * 0.05f);
            c.drawCircle(cx, cy, r + cell * 0.08f, p);
            p.setShader(null);
        }
    }

    /** The theme's mark on a safe cell: a star, a heart or a diamond. */
    private static void safeMark(Canvas c, Paint p, float cx, float cy, float cell, Theme t) {
        if (t.motif == Motif.HEART) heart(c, p, cx, cy + cell * 0.03f, cell * 0.34f, t.safe);
        else if (t.motif == Motif.DIAMOND) gem(c, p, cx, cy, cell * 0.3f, t.safe);
        else star(c, p, cx, cy, cell * 0.32f, t.safe);
    }

    /** A small ornament in the theme's style (corners of panels and frame). */
    private static void symbol(Canvas c, Paint p, float cx, float cy, float r, int color, Theme t) {
        if (t.motif == Motif.HEART) heart(c, p, cx, cy, r * 1.1f, color);
        else if (t.motif == Motif.DIAMOND) gem(c, p, cx, cy, r * 1.05f, color);
        else if (t.motif == Motif.FLAG) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(FLAG_RED);
            c.drawCircle(cx, cy, r * 0.8f, p);
            p.setColor(0x66ffffff);
            c.drawCircle(cx - r * 0.25f, cy - r * 0.25f, r * 0.2f, p);
        } else jewel(c, p, cx, cy, r, color);
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

    /** A rhombus jewel with a highlight. */
    private static void jewel(Canvas c, Paint p, float cx, float cy, float r, int color) {
        Path path = new Path();
        path.moveTo(cx, cy - r);
        path.lineTo(cx + r, cy);
        path.lineTo(cx, cy + r);
        path.lineTo(cx - r, cy);
        path.close();
        p.setStyle(Paint.Style.FILL);
        shade(p, new LinearGradient(cx - r, cy - r, cx + r, cy + r, lighten(color, 0.5f), darken(color, 0.2f), TileMode.CLAMP));
        c.drawPath(path, p);
        p.setShader(null);
        p.setColor(0x99ffffff);
        c.drawCircle(cx - r * 0.25f, cy - r * 0.25f, r * 0.22f, p);
    }

    /** A heart, glossy. */
    private static void heart(Canvas c, Paint p, float cx, float cy, float r, int color) {
        Path h = heartPath(cx, cy, r);
        p.setStyle(Paint.Style.FILL);
        shade(p, new LinearGradient(cx, cy - r, cx, cy + r, lighten(color, 0.35f), darken(color, 0.15f), TileMode.CLAMP));
        c.drawPath(h, p);
        p.setShader(null);
        p.setColor(0x88ffffff);
        c.drawCircle(cx - r * 0.42f, cy - r * 0.38f, r * 0.17f, p);
    }

    private static Path heartPath(float cx, float cy, float r) {
        Path h = new Path();
        h.moveTo(cx, cy + r * 0.9f);
        h.cubicTo(cx - r * 1.35f, cy + r * 0.05f, cx - r * 0.95f, cy - r * 1.05f, cx, cy - r * 0.42f);
        h.cubicTo(cx + r * 0.95f, cy - r * 1.05f, cx + r * 1.35f, cy + r * 0.05f, cx, cy + r * 0.9f);
        h.close();
        return h;
    }

    /** A cut diamond seen from the side: crown on top, pavilion below, facet lines. */
    private static void gem(Canvas c, Paint p, float cx, float cy, float r, int color) {
        float top = cy - r * 0.6f, girdle = cy - r * 0.15f, tip = cy + r * 0.9f, w = r, tw = r * 0.55f;
        Path g = new Path();
        g.moveTo(cx - tw, top);
        g.lineTo(cx + tw, top);
        g.lineTo(cx + w, girdle);
        g.lineTo(cx, tip);
        g.lineTo(cx - w, girdle);
        g.close();
        p.setStyle(Paint.Style.FILL);
        shade(p, new LinearGradient(cx - w, top, cx + w, tip, new int[]{0xffffffff, color, darken(color, 0.25f), lighten(color, 0.4f)},
                null, TileMode.CLAMP));
        c.drawPath(g, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.07f);
        p.setColor(0x99ffffff);
        c.drawLine(cx - w, girdle, cx + w, girdle, p);
        c.drawLine(cx - tw, top, cx - tw * 0.4f, girdle, p);
        c.drawLine(cx + tw, top, cx + tw * 0.4f, girdle, p);
        c.drawLine(cx - tw * 0.4f, girdle, cx, tip, p);
        c.drawLine(cx + tw * 0.4f, girdle, cx, tip, p);
    }

    /** The big ornament in the middle of the board: crown, heart, diamond or flag. */
    private static void medallion(Canvas c, Paint p, float cx, float cy, float r, Theme t) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55000000);
        c.drawCircle(cx + r * 0.05f, cy + r * 0.1f, r, p);
        shade(p, metal(t.trim, cx - r, cy - r, cx + r, cy + r));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
        float in = r * 0.8f;
        switch (t.motif) {
            case HEART:
                p.setColor(0xfffff1f6);
                c.drawCircle(cx, cy, in, p);
                heart(c, p, cx, cy + in * 0.05f, in * 0.72f, 0xffe91e63);
                heart(c, p, cx + in * 0.5f, cy - in * 0.42f, in * 0.22f, 0xffff6f9a);
                break;
            case DIAMOND:
                p.setColor(0xff0b0b0d);
                c.drawCircle(cx, cy, in, p);
                gem(c, p, cx, cy, in * 0.72f, 0xff9eeaff);
                for (float[] s : new float[][]{{-0.55f, -0.5f}, {0.6f, -0.35f}, {0.45f, 0.55f}}) {
                    sparkle(c, p, cx + s[0] * in, cy + s[1] * in, in * 0.13f);
                }
                break;
            case FLAG:
                p.setColor(0xff006a4e);
                c.drawCircle(cx, cy, in, p);
                p.setColor(FLAG_RED);
                c.drawCircle(cx - in * 0.1f, cy, in * 0.55f, p);
                break;
            default:
                crown(c, p, cx, cy, in, t);
                break;
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.06f);
        p.setColor(darken(t.trim, 0.45f));
        c.drawCircle(cx, cy, in, p);
    }

    /** A crown with three points and pearls, on the medallion. */
    private static void crown(Canvas c, Paint p, float cx, float cy, float r, Theme t) {
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
        p.setColor(t.edge == 0xff000000 ? 0xff2a1a00 : darken(t.edge, 0.1f));
        c.drawPath(path, p);
        p.setColor(lighten(t.trim, 0.4f));
        c.drawCircle(cx - w, top + r * 0.08f, r * 0.09f, p);
        c.drawCircle(cx, top - r * 0.04f, r * 0.1f, p);
        c.drawCircle(cx + w, top + r * 0.08f, r * 0.09f, p);
        p.setColor(0xffe0115f);
        c.drawCircle(cx, (band + bottom) / 2f, r * 0.08f, p);
    }

    /** A four-pointed twinkle. */
    private static void sparkle(Canvas c, Paint p, float cx, float cy, float r) {
        Path s = new Path();
        s.moveTo(cx, cy - r);
        s.quadTo(cx, cy, cx + r, cy);
        s.quadTo(cx, cy, cx, cy + r);
        s.quadTo(cx, cy, cx - r, cy);
        s.quadTo(cx, cy, cx, cy - r);
        s.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xffffffff);
        c.drawPath(s, p);
    }

    /** What is worked into a yard's panel. */
    private static void pattern(Canvas c, Paint p, RectF panel, float cell, int seed, Theme t) {
        if (t.pattern == Pattern.NONE) return;
        float round = cell * 0.5f;
        c.save();
        Path clip = new Path();
        clip.addRoundRect(panel, round, round, Path.Direction.CW);
        c.clipPath(clip);
        Random rnd = new Random(31L * seed + 7);
        switch (t.pattern) {
            case VEINS:
                p.setStyle(Paint.Style.STROKE);
                for (int v = 0; v < 6; v++) {
                    Path path = new Path();
                    float x = panel.left + rnd.nextFloat() * panel.width(), y = panel.top;
                    path.moveTo(x, y);
                    for (int k = 0; k < 6; k++) {
                        x += (rnd.nextFloat() - 0.4f) * panel.width() * 0.35f;
                        y += panel.height() / 5f;
                        path.lineTo(x, y);
                    }
                    p.setStrokeWidth(cell * (0.03f + rnd.nextFloat() * 0.05f));
                    p.setColor(v % 2 == 0 ? 0x33ffffff : 0x26e8b923);
                    c.drawPath(path, p);
                }
                break;
            case HEARTS:
                // Scattered little hearts, some leaning, in soft pinks.
                for (int k = 0; k < 16; k++) {
                    float hx = panel.left + rnd.nextFloat() * panel.width(), hy = panel.top + rnd.nextFloat() * panel.height();
                    float hr = cell * (0.12f + rnd.nextFloat() * 0.14f);
                    c.save();
                    c.rotate((rnd.nextFloat() - 0.5f) * 50, hx, hy);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(k % 3 == 0 ? 0x77e91e63 : k % 3 == 1 ? 0x66ff80ab : 0x55f48fb1);
                    c.drawPath(heartPath(hx, hy, hr), p);
                    c.restore();
                }
                break;
            case QUILT: {
                // Quilted leather: a gold diamond lattice with a stud at each crossing.
                float step = cell * 0.75f;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(cell * 0.035f);
                p.setColor(0x66d4a017);
                for (float d = -panel.height(); d < panel.width() + panel.height(); d += step) {
                    c.drawLine(panel.left + d, panel.top, panel.left + d + panel.height(), panel.bottom, p);
                    c.drawLine(panel.left + d, panel.bottom, panel.left + d + panel.height(), panel.top, p);
                }
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xccffd76a);
                float half = step / 2f;
                for (float yy = panel.top; yy <= panel.bottom + 1; yy += half) {
                    int row = Math.round((yy - panel.top) / half);
                    for (float xx = panel.left + (row % 2 == 0 ? 0 : half); xx <= panel.right + 1; xx += step) {
                        c.drawCircle(xx, yy, cell * 0.045f, p);
                    }
                }
                break;
            }
            case FLAG: {
                // The red sun of Bangladesh, big enough to hold the four sockets, a little
                // towards the hoist side as on the flag.
                float sx = panel.centerX() - panel.width() * 0.03f, sy = panel.centerY(), sr = panel.width() * 0.43f;
                p.setStyle(Paint.Style.FILL);
                shade(p, new RadialGradient(sx - sr * 0.3f, sy - sr * 0.35f, sr * 1.3f, lighten(FLAG_RED, 0.18f), darken(FLAG_RED, 0.2f), TileMode.CLAMP));
                c.drawCircle(sx, sy, sr, p);
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(cell * 0.05f);
                p.setColor(0x55ffffff);
                c.drawCircle(sx, sy, sr, p);
                break;
            }
            default:
                break;
        }
        c.restore();
    }
}
