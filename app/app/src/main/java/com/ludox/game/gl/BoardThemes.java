package com.ludox.game.gl;

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

import com.ludox.game.LudoApp;
import com.ludox.game.game.Board;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * The board skins and the painting of the board face for each. A board's look comes from
 * the server's catalogue (shared/boards.ts, sent in /config): its colours, centre emblem,
 * yard pattern, safe-cell mark, rim, sky, corner decorations and ambient effect, so new
 * boards need no app update. Every board keeps the same grid, paths, safe cells, yards and
 * home, and the players' colours never change: decoration only goes inside the yards' inner
 * panels and the centre, never on a path. An unknown id paints the classic board.
 */
public final class BoardThemes {
    private BoardThemes() {}

    public static final class Theme {
        public final String id, name;
        /** Board face behind everything, the yards' inner panels (centre, edge), the path cells. */
        final int base, panel, panelEdge, track;
        /** Cell lines, safe-cell marks, the painted outer edge; trim 0 = plain (classic) board. */
        final int grid, safe, edge;
        public final int trim;
        final String emblem, pattern, safeMark;
        /** The 3D base and raised rim (0..1 RGB) and how shiny the rim is. */
        public final float[] wood, rim;
        final float rimSpec;
        /** The colour around the board (its environment). */
        public final int sky;
        /** 3D decorations on the corners, and their colour. */
        public final String decor;
        public final int decorColor;
        /** The gentle effect drifting over the board in a match. */
        public final String ambient;
        /** Shop data. */
        public final String collection, rarity;

        Theme(String id, String name, int base, int panel, int panelEdge, int track, int grid, int safe, int edge, int trim,
              String emblem, String pattern, String safeMark, float[] wood, float[] rim, float rimSpec, int sky,
              String decor, int decorColor, String ambient, String collection, String rarity) {
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
            this.emblem = emblem;
            this.pattern = pattern;
            this.safeMark = safeMark;
            this.wood = wood;
            this.rim = rim;
            this.rimSpec = rimSpec;
            this.sky = sky;
            this.decor = decor;
            this.decorColor = decorColor;
            this.ambient = ambient;
            this.collection = collection;
            this.rarity = rarity;
        }

        public boolean royal() {
            return trim != 0;
        }

        /** Changes whenever anything about the look changes (for caches of painted boards). */
        public String key() {
            return id + "|" + base + "|" + panel + "|" + panelEdge + "|" + track + "|" + safe + "|" + trim + "|" + emblem + "|" + pattern + "|" + safeMark + "|" + decor;
        }

        /** A board from the server's catalogue entry (id, name, look...); null if it doesn't parse. */
        public static Theme from(JSONObject o) {
            try {
                JSONObject l = o.getJSONObject("look");
                String trim = l.optString("trim", "");
                return new Theme(o.getString("id"), o.optString("name", o.getString("id")),
                        col(l, "base"), col(l, "panel"), col(l, "panelEdge"), col(l, "track"), col(l, "grid"), col(l, "safe"),
                        col(l, "edge"), trim.isEmpty() ? 0 : Color.parseColor(trim),
                        l.optString("emblem", "none"), l.optString("pattern", "none"), l.optString("safeMark", "star"),
                        rgb(l.optJSONArray("wood"), CLASSIC_WOOD), rgb(l.optJSONArray("rim"), CLASSIC_RIM), (float) l.optDouble("rimSpec", 0.6),
                        col(l, "sky"), l.optString("decor", "none"), col(l, "decorColor"), l.optString("ambient", "none"),
                        o.optString("collection", "free"), o.optString("rarity", "common"));
            } catch (Exception e) {
                return null;
            }
        }

        private static int col(JSONObject l, String k) {
            return Color.parseColor(l.optString(k, "#808080"));
        }

        private static float[] rgb(JSONArray a, float[] d) {
            if (a == null || a.length() != 3) return d;
            return new float[]{(float) a.optDouble(0), (float) a.optDouble(1), (float) a.optDouble(2)};
        }
    }

    private static final float[] CLASSIC_WOOD = {0.40f, 0.26f, 0.15f}, CLASSIC_RIM = {0.33f, 0.21f, 0.12f};

    public static final Theme CLASSIC = new Theme("classic", "Classic", 0xfff7f1e3, 0xfffdfaf2, 0xfffdfaf2, Color.WHITE,
            0x40000000, 0xffb9b2a0, 0xff3b2a1a, 0, "none", "none", "star", CLASSIC_WOOD, CLASSIC_RIM, 0.18f, 0xff4316a0,
            "none", 0, "none", "free", "common");

    private static final String PREF = "boards_cache";
    private static Map<String, Theme> registry;
    private static JSONArray registered;

    /** Every board the server sells (from /config, kept on the phone for offline play). */
    private static synchronized Map<String, Theme> boards() {
        LudoApp app = LudoApp.get();
        JSONArray arr = app.config != null ? app.config.optJSONArray("boards") : null;
        if (arr != null && arr != registered) {
            registered = arr;
            registry = parse(arr);
            app.prefs.edit().putString(PREF, arr.toString()).apply();
        } else if (registry == null) {
            try {
                registry = parse(new JSONArray(app.prefs.getString(PREF, "[]")));
            } catch (Exception e) {
                registry = new HashMap<>();
            }
        }
        return registry;
    }

    private static Map<String, Theme> parse(JSONArray arr) {
        Map<String, Theme> out = new HashMap<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            Theme t = o != null && o.has("look") ? Theme.from(o) : null;
            if (t != null) out.put(t.id, t);
        }
        return out;
    }

    /** The theme for this id (classic when unknown or null). */
    public static Theme get(String id) {
        if (id == null || CLASSIC.id.equals(id)) return CLASSIC;
        Theme t = boards().get(id);
        return t != null ? t : CLASSIC;
    }

    public static boolean known(String id) {
        return id != null && (CLASSIC.id.equals(id) || boards().containsKey(id));
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
            if (rich) shade(p, new LinearGradient(x, y, x + w, y + w, lighten(col, 0.22f), darken(col, 0.22f), TileMode.CLAMP));
            else p.setColor(col);
            c.drawRect(x, y, x + w, y + w, p);
            p.setShader(null);

            RectF panel = new RectF(x + 0.75f * cell, y + 0.75f * cell, x + 5.25f * cell, y + 5.25f * cell);
            float round = cell * 0.5f;
            if (rich) {
                p.setColor(0x40000000);
                c.drawRoundRect(new RectF(panel.left + cell * 0.06f, panel.top + cell * 0.1f, panel.right + cell * 0.06f, panel.bottom + cell * 0.1f), round, round, p);
            }
            if ("flag".equals(t.pattern) || "sunset".equals(t.pattern) || "aurora".equals(t.pattern)) {
                shade(p, new LinearGradient(panel.left, panel.top, panel.right, panel.bottom, t.panel, t.panelEdge, TileMode.CLAMP));
            } else {
                shade(p, new RadialGradient(panel.centerX(), panel.centerY(), panel.width() * 0.75f, t.panel, t.panelEdge, TileMode.CLAMP));
            }
            c.drawRoundRect(panel, round, round, p);
            p.setShader(null);
            pattern(c, p, panel, cell, ci, t);
            if (rich) {
                p.setStyle(Paint.Style.STROKE);
                shade(p, metal(t.trim, x, y, x + w, y + w));
                p.setStrokeWidth(cell * 0.1f);
                float in = cell * 0.2f;
                c.drawRect(x + in, y + in, x + w - in, y + w - in, p);
                p.setStrokeWidth(cell * 0.07f);
                c.drawRoundRect(panel, round, round, p);
                p.setShader(null);
                p.setStrokeWidth(cell * 0.05f);
                p.setColor(0x26000000);
                c.drawRoundRect(new RectF(panel.left + cell * 0.08f, panel.top + cell * 0.08f, panel.right - cell * 0.08f, panel.bottom - cell * 0.08f), round, round, p);
                float j = cell * 0.55f, jr = cell * 0.17f;
                mark(c, p, t.safeMark, panel.left + j, panel.top + j, jr * 1.1f, t.safe);
                mark(c, p, t.safeMark, panel.right - j, panel.top + j, jr * 1.1f, t.safe);
                mark(c, p, t.safeMark, panel.left + j, panel.bottom - j, jr * 1.1f, t.safe);
                mark(c, p, t.safeMark, panel.right - j, panel.bottom - j, jr * 1.1f, t.safe);
            }
            for (int k = 0; k < 4; k++) {
                float[] s = Board.yardSlot(ci, k);
                socket(c, p, (s[0] + 0.5f) * cell, (s[1] + 0.5f) * cell, cell, col, t);
            }
        }

        // Track (plain colours only: nothing decorative ever sits on a path).
        for (int i = 0; i < Board.TRACK_LEN; i++) {
            float[] tr = Board.TRACK[i];
            int start = -1;
            for (int s = 0; s < 4; s++) if (Board.START_INDICES[s] == i) start = s;
            cellRect(c, p, tr[0], tr[1], cell, start >= 0 ? Board.COLOR_ARGB[start] : t.track, t.grid, rich);
            float cx = (tr[0] + 0.5f) * cell, cy = (tr[1] + 0.5f) * cell;
            if (start >= 0) star(c, p, cx, cy, cell * 0.3f, 0xccffffff);
            else if (Board.isSafe(i)) mark(c, p, t.safeMark, cx, cy, cell * 0.32f, t.safe);
        }
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
        if (rich && !"none".equals(t.emblem)) medallion(c, p, m, m, cell * 0.82f, t);

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
            float g = cell * 0.32f;
            for (float[] k : new float[][]{{g, g}, {size - g, g}, {g, size - g}, {size - g, size - g}}) {
                p.setStyle(Paint.Style.FILL);
                shade(p, metal(t.trim, k[0] - g, k[1] - g, k[0] + g, k[1] + g));
                c.drawCircle(k[0], k[1], g * 0.85f, p);
                p.setShader(null);
                mark(c, p, t.safeMark, k[0], k[1], g * 0.55f, t.safe);
            }
        }
    }

    // ================================================================= pieces

    /** A gradient at full strength (a paint's alpha also fades its shader). */
    private static void shade(Paint p, Shader s) {
        p.setColor(Color.BLACK);
        p.setShader(s);
    }

    static int lighten(int c, float f) {
        return Color.argb(Color.alpha(c), (int) (Color.red(c) + (255 - Color.red(c)) * f), (int) (Color.green(c) + (255 - Color.green(c)) * f),
                (int) (Color.blue(c) + (255 - Color.blue(c)) * f));
    }

    static int darken(int c, float f) {
        return Color.argb(Color.alpha(c), (int) (Color.red(c) * (1 - f)), (int) (Color.green(c) * (1 - f)), (int) (Color.blue(c) * (1 - f)));
    }

    private static int alpha(int c, int a) {
        return (c & 0x00ffffff) | (a << 24);
    }

    /** Polished metal along a diagonal. */
    private static Shader metal(int c, float x0, float y0, float x1, float y1) {
        return new LinearGradient(x0, y0, x1, y1,
                new int[]{lighten(c, 0.55f), c, darken(c, 0.3f), lighten(c, 0.35f), darken(c, 0.2f)},
                new float[]{0f, 0.3f, 0.5f, 0.7f, 1f}, TileMode.MIRROR);
    }

    private static void cellRect(Canvas c, Paint p, float col, float row, float cell, int fill, int grid, boolean rich) {
        float x = col * cell, y = row * cell;
        p.setStyle(Paint.Style.FILL);
        if (rich) shade(p, new LinearGradient(x, y, x, y + cell, lighten(fill, 0.12f), darken(fill, 0.06f), TileMode.CLAMP));
        else p.setColor(fill);
        c.drawRect(x, y, x + cell, y + cell, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(cell * 0.04f);
        p.setColor(grid);
        c.drawRect(x, y, x + cell, y + cell, p);
    }

    /** A pawn's home spot in the yard: a sunken socket ringed in its colour (and metal). */
    private static void socket(Canvas c, Paint p, float cx, float cy, float cell, int col, Theme t) {
        float r = cell * 0.6f;
        p.setStyle(Paint.Style.FILL);
        if (t.royal()) shade(p, new RadialGradient(cx - r * 0.2f, cy - r * 0.25f, r * 1.1f, lighten(col, 0.7f), lighten(col, 0.3f), TileMode.CLAMP));
        else p.setColor(lighten(col, 0.55f));
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

    /** The theme's mark (safe cells, panel and frame corners). */
    private static void mark(Canvas c, Paint p, String kind, float cx, float cy, float r, int color) {
        switch (kind) {
            case "heart": heart(c, p, cx, cy + r * 0.08f, r * 1.05f, color); break;
            case "diamond": gem(c, p, cx, cy, r * 0.95f, color); break;
            case "flower": flower(c, p, cx, cy, r, color, 0xfffff3c4, 5); break;
            case "snowflake": snowflakeMark(c, p, cx, cy, r, color); break;
            case "flame": flame(c, p, cx, cy, r, color); break;
            case "moon": crescent(c, p, cx, cy, r * 0.9f, color); break;
            case "pearl": pearl(c, p, cx, cy, r * 0.75f); break;
            case "leaf": leaf(c, p, cx, cy, r, color, 30); break;
            default: star(c, p, cx, cy, r, color); break;
        }
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
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(path, p);
    }

    private static void heart(Canvas c, Paint p, float cx, float cy, float r, int color) {
        p.setStyle(Paint.Style.FILL);
        shade(p, new LinearGradient(cx, cy - r, cx, cy + r, lighten(color, 0.35f), darken(color, 0.15f), TileMode.CLAMP));
        c.drawPath(DiceArt.heart(cx, cy, r), p);
        p.setShader(null);
        p.setColor(0x88ffffff);
        c.drawCircle(cx - r * 0.42f, cy - r * 0.38f, r * 0.17f, p);
    }

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
        shade(p, new LinearGradient(cx - w, top, cx + w, tip, new int[]{0xffffffff, color, darken(color, 0.25f), lighten(color, 0.4f)}, null, TileMode.CLAMP));
        c.drawPath(g, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.07f);
        p.setColor(0x99ffffff);
        c.drawLine(cx - w, girdle, cx + w, girdle, p);
        c.drawLine(cx - tw * 0.4f, girdle, cx, tip, p);
        c.drawLine(cx + tw * 0.4f, girdle, cx, tip, p);
        p.setStyle(Paint.Style.FILL);
    }

    private static void flower(Canvas c, Paint p, float cx, float cy, float r, int petal, int centre, int n) {
        p.setStyle(Paint.Style.FILL);
        for (int k = 0; k < n; k++) {
            double a = Math.PI * 2 * k / n - Math.PI / 2;
            float px = cx + (float) Math.cos(a) * r * 0.55f, py = cy + (float) Math.sin(a) * r * 0.55f;
            shade(p, new RadialGradient(px, py, r * 0.5f, lighten(petal, 0.3f), petal, TileMode.CLAMP));
            c.drawCircle(px, py, r * 0.48f, p);
        }
        p.setShader(null);
        p.setColor(centre);
        c.drawCircle(cx, cy, r * 0.3f, p);
    }

    private static void snowflakeMark(Canvas c, Paint p, float cx, float cy, float r, int color) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(r * 0.16f);
        p.setColor(color);
        for (int k = 0; k < 6; k++) {
            double a = Math.PI / 3 * k;
            float ex = cx + (float) Math.cos(a) * r, ey = cy + (float) Math.sin(a) * r;
            c.drawLine(cx, cy, ex, ey, p);
            float bx = cx + (float) Math.cos(a) * r * 0.6f, by = cy + (float) Math.sin(a) * r * 0.6f;
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                double b = a + sgn * Math.PI / 4;
                c.drawLine(bx, by, bx + (float) Math.cos(b) * r * 0.3f, by + (float) Math.sin(b) * r * 0.3f, p);
            }
        }
        p.setStyle(Paint.Style.FILL);
    }

    private static Path flamePath(float cx, float cy, float r) {
        Path f = new Path();
        f.moveTo(cx, cy - r);
        f.cubicTo(cx + r * 0.9f, cy - r * 0.1f, cx + r * 0.7f, cy + r * 0.9f, cx, cy + r * 0.9f);
        f.cubicTo(cx - r * 0.7f, cy + r * 0.9f, cx - r * 0.9f, cy - r * 0.1f, cx, cy - r);
        f.close();
        return f;
    }

    private static void flame(Canvas c, Paint p, float cx, float cy, float r, int color) {
        p.setStyle(Paint.Style.FILL);
        shade(p, new LinearGradient(cx, cy - r, cx, cy + r, 0xffffe08a, color, TileMode.CLAMP));
        c.drawPath(flamePath(cx, cy, r), p);
        p.setShader(null);
        p.setColor(0xccfff3c4);
        c.drawPath(flamePath(cx, cy + r * 0.35f, r * 0.45f), p);
    }

    private static void crescent(Canvas c, Paint p, float cx, float cy, float r, int color) {
        Path m = new Path();
        m.addCircle(cx, cy, r, Path.Direction.CW);
        Path cut = new Path();
        cut.addCircle(cx + r * 0.45f, cy - r * 0.25f, r * 0.85f, Path.Direction.CW);
        m.op(cut, Path.Op.DIFFERENCE);
        p.setStyle(Paint.Style.FILL);
        shade(p, new LinearGradient(cx - r, cy - r, cx + r, cy + r, lighten(color, 0.4f), color, TileMode.CLAMP));
        c.drawPath(m, p);
        p.setShader(null);
    }

    private static void pearl(Canvas c, Paint p, float cx, float cy, float r) {
        p.setStyle(Paint.Style.FILL);
        shade(p, new RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 1.4f, new int[]{0xffffffff, 0xfff1ece4, 0xffc9c0b4}, new float[]{0f, 0.5f, 1f}, TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
        p.setColor(0x55ffd6e8);
        c.drawCircle(cx + r * 0.3f, cy + r * 0.3f, r * 0.35f, p);
    }

    private static void leaf(Canvas c, Paint p, float cx, float cy, float r, int color, float rot) {
        c.save();
        c.rotate(rot, cx, cy);
        Path l = new Path();
        l.moveTo(cx, cy - r);
        l.quadTo(cx + r * 0.8f, cy, cx, cy + r);
        l.quadTo(cx - r * 0.8f, cy, cx, cy - r);
        l.close();
        p.setStyle(Paint.Style.FILL);
        shade(p, new LinearGradient(cx - r, cy, cx + r, cy, lighten(color, 0.3f), darken(color, 0.2f), TileMode.CLAMP));
        c.drawPath(l, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.08f);
        p.setColor(0x55ffffff);
        c.drawLine(cx, cy - r * 0.8f, cx, cy + r * 0.8f, p);
        p.setStyle(Paint.Style.FILL);
        c.restore();
    }

    private static void sparkle(Canvas c, Paint p, float cx, float cy, float r) {
        Path s = new Path();
        s.moveTo(cx, cy - r);
        s.quadTo(cx, cy, cx + r, cy);
        s.quadTo(cx, cy, cx, cy + r);
        s.quadTo(cx, cy, cx - r, cy);
        s.quadTo(cx, cy, cx, cy - r);
        s.close();
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xffffffff);
        c.drawPath(s, p);
    }

    private static void dot(Canvas c, Paint p, float x, float y, float r, int color) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawCircle(x, y, r, p);
    }

    // ================================================================= centre emblems

    /** The big ornament in the middle of the board, on a metal medallion. */
    private static void medallion(Canvas c, Paint p, float cx, float cy, float r, Theme t) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55000000);
        c.drawCircle(cx + r * 0.05f, cy + r * 0.1f, r, p);
        shade(p, metal(t.trim, cx - r, cy - r, cx + r, cy + r));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
        float in = r * 0.8f;
        shade(p, new RadialGradient(cx, cy - in * 0.3f, in * 1.3f, lighten(t.panel, 0.2f), t.panelEdge, TileMode.CLAMP));
        c.drawCircle(cx, cy, in, p);
        p.setShader(null);
        c.save();
        Path clip = new Path();
        clip.addCircle(cx, cy, in, Path.Direction.CW);
        c.clipPath(clip);
        emblem(c, p, cx, cy, in, t);
        c.restore();
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.06f);
        p.setColor(darken(t.trim, 0.45f));
        c.drawCircle(cx, cy, in, p);
        p.setStyle(Paint.Style.FILL);
    }

    private static void emblem(Canvas c, Paint p, float cx, float cy, float r, Theme t) {
        int gold = t.trim, dark = t.edge == 0xff000000 ? 0xff2a1a00 : darken(t.edge, 0.1f), accent = t.safe;
        p.setStyle(Paint.Style.FILL);
        switch (t.emblem) {
            case "crown":
                crown(c, p, cx, cy, r, gold, dark);
                break;
            case "palace": {
                // A domed palace between columns.
                p.setColor(lighten(gold, 0.3f));
                c.drawRect(cx - r * 0.7f, cy + r * 0.45f, cx + r * 0.7f, cy + r * 0.6f, p);
                for (int k = -2; k <= 2; k++) c.drawRect(cx + k * r * 0.28f - r * 0.06f, cy - r * 0.05f, cx + k * r * 0.28f + r * 0.06f, cy + r * 0.45f, p);
                c.drawRect(cx - r * 0.7f, cy - r * 0.15f, cx + r * 0.7f, cy - r * 0.02f, p);
                p.setColor(gold);
                c.drawArc(new RectF(cx - r * 0.4f, cy - r * 0.6f, cx + r * 0.4f, cy + r * 0.2f), 180, 180, true, p);
                c.drawRect(cx - r * 0.03f, cy - r * 0.78f, cx + r * 0.03f, cy - r * 0.55f, p);
                c.drawCircle(cx, cy - r * 0.8f, r * 0.06f, p);
                break;
            }
            case "throne": {
                Path th = new Path();
                th.moveTo(cx - r * 0.45f, cy + r * 0.6f);
                th.lineTo(cx - r * 0.45f, cy - r * 0.45f);
                th.quadTo(cx, cy - r * 0.85f, cx + r * 0.45f, cy - r * 0.45f);
                th.lineTo(cx + r * 0.45f, cy + r * 0.6f);
                th.lineTo(cx + r * 0.3f, cy + r * 0.6f);
                th.lineTo(cx + r * 0.3f, cy + r * 0.1f);
                th.lineTo(cx - r * 0.3f, cy + r * 0.1f);
                th.lineTo(cx - r * 0.3f, cy + r * 0.6f);
                th.close();
                shade(p, metal(gold, cx - r, cy - r, cx + r, cy + r));
                c.drawPath(th, p);
                p.setShader(null);
                p.setColor(accent);
                c.drawRoundRect(new RectF(cx - r * 0.3f, cy - r * 0.35f, cx + r * 0.3f, cy + r * 0.12f), r * 0.1f, r * 0.1f, p);
                crown(c, p, cx, cy - r * 0.62f, r * 0.25f, gold, dark);
                break;
            }
            case "diamond":
                gem(c, p, cx, cy, r * 0.72f, accent == gold ? 0xff9eeaff : accent);
                sparkle(c, p, cx + r * 0.5f, cy - r * 0.45f, r * 0.14f);
                break;
            case "heart":
                shade(p, new RadialGradient(cx, cy, r, alpha(accent, 0x99), alpha(accent, 0), TileMode.CLAMP));
                c.drawCircle(cx, cy, r, p);
                p.setShader(null);
                heart(c, p, cx, cy + r * 0.05f, r * 0.62f, accent);
                break;
            case "twin_hearts":
                heart(c, p, cx - r * 0.22f, cy + r * 0.02f, r * 0.45f, accent);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.09f);
                p.setColor(gold);
                c.drawPath(DiceArt.heart(cx + r * 0.22f, cy + r * 0.02f, r * 0.45f), p);
                p.setStyle(Paint.Style.FILL);
                break;
            case "moon":
                dot(c, p, cx, cy, r, 0xff0b0c2a);
                crescent(c, p, cx - r * 0.1f, cy, r * 0.55f, 0xfffff3c4);
                star(c, p, cx + r * 0.45f, cy - r * 0.4f, r * 0.1f, 0xffffe08a);
                star(c, p, cx + r * 0.55f, cy + r * 0.25f, r * 0.07f, 0xffffe08a);
                star(c, p, cx + r * 0.2f, cy + r * 0.55f, r * 0.06f, 0xffffe08a);
                break;
            case "rose":
                rose(c, p, cx, cy, r * 0.62f, accent);
                break;
            case "eiffel": {
                dot(c, p, cx, cy, r, 0xff1a1d3a);
                dot(c, p, cx + r * 0.45f, cy - r * 0.45f, r * 0.14f, 0xfffff3c4);
                Path tw = new Path();
                tw.moveTo(cx, cy - r * 0.78f);
                tw.lineTo(cx + r * 0.12f, cy - r * 0.2f);
                tw.lineTo(cx + r * 0.42f, cy + r * 0.62f);
                tw.lineTo(cx + r * 0.22f, cy + r * 0.62f);
                tw.quadTo(cx, cy + r * 0.25f, cx - r * 0.22f, cy + r * 0.62f);
                tw.lineTo(cx - r * 0.42f, cy + r * 0.62f);
                tw.lineTo(cx - r * 0.12f, cy - r * 0.2f);
                tw.close();
                shade(p, metal(gold, cx - r, cy - r, cx + r, cy + r));
                c.drawPath(tw, p);
                p.setShader(null);
                p.setColor(0xff1a1d3a);
                c.drawRect(cx - r * 0.25f, cy + r * 0.08f, cx + r * 0.25f, cy + r * 0.14f, p);
                heart(c, p, cx - r * 0.5f, cy - r * 0.4f, r * 0.14f, accent);
                break;
            }
            case "blossom":
                flower(c, p, cx, cy, r * 0.62f, 0xffffb7cf, 0xffffe08a, 5);
                break;
            case "castle": {
                p.setColor(lighten(accent, 0.5f));
                c.drawRect(cx - r * 0.55f, cy - r * 0.1f, cx + r * 0.55f, cy + r * 0.6f, p);
                for (int k = -1; k <= 1; k++) {
                    float tx = cx + k * r * 0.45f, h = k == 0 ? r * 0.75f : r * 0.5f;
                    p.setColor(lighten(accent, 0.65f));
                    c.drawRect(tx - r * 0.13f, cy - h + r * 0.15f, tx + r * 0.13f, cy + r * 0.6f, p);
                    Path roof = new Path();
                    roof.moveTo(tx - r * 0.18f, cy - h + r * 0.15f);
                    roof.lineTo(tx, cy - h - r * 0.2f);
                    roof.lineTo(tx + r * 0.18f, cy - h + r * 0.15f);
                    roof.close();
                    p.setColor(gold);
                    c.drawPath(roof, p);
                }
                heart(c, p, cx, cy + r * 0.32f, r * 0.2f, accent);
                break;
            }
            case "flower":
                flower(c, p, cx, cy, r * 0.62f, accent, 0xfffff3c4, 8);
                break;
            case "pearl":
                p.setColor(0xffffe6ef);
                c.drawArc(new RectF(cx - r * 0.75f, cy - r * 0.2f, cx + r * 0.75f, cy + r * 0.75f), 0, 180, true, p);
                pearl(c, p, cx, cy, r * 0.42f);
                break;
            case "leaf":
                leaf(c, p, cx, cy, r * 0.65f, accent, 25);
                break;
            case "waterfall": {
                p.setColor(0xff4a6a5a);
                c.drawRect(cx - r, cy - r, cx + r, cy - r * 0.2f, p);
                shade(p, new LinearGradient(cx, cy - r * 0.3f, cx, cy + r, 0xffffffff, 0xff7fd0f0, TileMode.CLAMP));
                c.drawRect(cx - r * 0.3f, cy - r * 0.3f, cx + r * 0.3f, cy + r, p);
                p.setShader(null);
                for (int k = 0; k < 4; k++) dot(c, p, cx + (k - 1.5f) * r * 0.22f, cy + r * 0.62f, r * 0.12f, 0xbbffffff);
                break;
            }
            case "sun": {
                for (int k = 0; k < 12; k++) {
                    c.save();
                    c.rotate(k * 30, cx, cy);
                    p.setColor(alpha(gold, 0xcc));
                    Path ray = new Path();
                    ray.moveTo(cx - r * 0.08f, cy - r * 0.45f);
                    ray.lineTo(cx, cy - r * 0.85f);
                    ray.lineTo(cx + r * 0.08f, cy - r * 0.45f);
                    ray.close();
                    c.drawPath(ray, p);
                    c.restore();
                }
                shade(p, new RadialGradient(cx, cy, r * 0.42f, 0xffffe08a, accent, TileMode.CLAMP));
                c.drawCircle(cx, cy, r * 0.42f, p);
                p.setShader(null);
                break;
            }
            case "mountain": {
                Path mt = new Path();
                mt.moveTo(cx - r, cy + r * 0.6f);
                mt.lineTo(cx - r * 0.3f, cy - r * 0.5f);
                mt.lineTo(cx, cy - r * 0.1f);
                mt.lineTo(cx + r * 0.35f, cy - r * 0.65f);
                mt.lineTo(cx + r, cy + r * 0.6f);
                mt.close();
                p.setColor(0xff5a6a80);
                c.drawPath(mt, p);
                Path snow = new Path();
                snow.moveTo(cx + r * 0.35f, cy - r * 0.65f);
                snow.lineTo(cx + r * 0.53f, cy - r * 0.35f);
                snow.lineTo(cx + r * 0.17f, cy - r * 0.35f);
                snow.close();
                snow.moveTo(cx - r * 0.3f, cy - r * 0.5f);
                snow.lineTo(cx - r * 0.15f, cy - r * 0.27f);
                snow.lineTo(cx - r * 0.45f, cy - r * 0.27f);
                snow.close();
                p.setColor(Color.WHITE);
                c.drawPath(snow, p);
                break;
            }
            case "aurora": {
                dot(c, p, cx, cy, r, 0xff050a1e);
                int[] bands = {0xaa5affc8, 0x99a06bff, 0x885affc8};
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.16f);
                for (int k = 0; k < 3; k++) {
                    Path w = new Path();
                    float y = cy - r * 0.4f + k * r * 0.3f;
                    w.moveTo(cx - r, y);
                    w.cubicTo(cx - r * 0.3f, y - r * 0.4f, cx + r * 0.3f, y + r * 0.4f, cx + r, y - r * 0.1f);
                    p.setColor(bands[k]);
                    c.drawPath(w, p);
                }
                p.setStyle(Paint.Style.FILL);
                star(c, p, cx + r * 0.4f, cy + r * 0.5f, r * 0.08f, 0xffffffff);
                break;
            }
            case "dragon": {
                // A coiled dragon round a fire jewel.
                Path body = new Path();
                body.addArc(new RectF(cx - r * 0.65f, cy - r * 0.65f, cx + r * 0.65f, cy + r * 0.65f), 120, 300);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeWidth(r * 0.2f);
                shade(p, metal(gold, cx - r, cy - r, cx + r, cy + r));
                p.setStyle(Paint.Style.STROKE);
                c.drawPath(body, p);
                p.setShader(null);
                p.setStrokeWidth(r * 0.03f);
                p.setColor(0x88000000);
                for (int k = 0; k < 14; k++) {
                    double ang = Math.toRadians(130 + k * 20);
                    float x = cx + (float) Math.cos(ang) * r * 0.65f, y = cy + (float) Math.sin(ang) * r * 0.65f;
                    c.drawArc(new RectF(x - r * 0.07f, y - r * 0.07f, x + r * 0.07f, y + r * 0.07f), (float) Math.toDegrees(ang), 180, false, p);
                }
                p.setStyle(Paint.Style.FILL);
                double ha = Math.toRadians(120);
                float hx = cx + (float) Math.cos(ha) * r * 0.65f, hy = cy + (float) Math.sin(ha) * r * 0.65f;
                Path head = new Path();
                head.moveTo(hx - r * 0.22f, hy + r * 0.1f);
                head.lineTo(hx + r * 0.05f, hy - r * 0.18f);
                head.lineTo(hx + r * 0.22f, hy + r * 0.05f);
                head.lineTo(hx, hy + r * 0.2f);
                head.close();
                p.setColor(gold);
                c.drawPath(head, p);
                dot(c, p, hx + r * 0.04f, hy - r * 0.02f, r * 0.04f, 0xffff4500);
                flame(c, p, cx, cy, r * 0.28f, 0xffff4500);
                break;
            }
            case "snowflake":
                snowflakeMark(c, p, cx, cy, r * 0.7f, accent);
                break;
            case "flame":
                flame(c, p, cx, cy + r * 0.05f, r * 0.65f, accent);
                break;
            case "planet": {
                dot(c, p, cx, cy, r, 0xff030210);
                for (int k = 0; k < 14; k++) dot(c, p, cx + (float) Math.cos(k * 2.3) * r * 0.8f, cy + (float) Math.sin(k * 1.7) * r * 0.8f, r * 0.02f, 0xddffffff);
                shade(p, new RadialGradient(cx - r * 0.15f, cy - r * 0.15f, r * 0.55f, 0xffc77dff, 0xff4a1a8a, TileMode.CLAMP));
                c.drawCircle(cx, cy, r * 0.4f, p);
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.08f);
                p.setColor(0xccffcf40);
                c.save();
                c.rotate(-20, cx, cy);
                c.drawOval(new RectF(cx - r * 0.75f, cy - r * 0.16f, cx + r * 0.75f, cy + r * 0.16f), p);
                c.restore();
                p.setStyle(Paint.Style.FILL);
                break;
            }
            case "crystal": {
                int[] cols = {lighten(accent, 0.2f), accent, lighten(accent, 0.45f)};
                float[][] cr = {{-0.3f, 0.3f, -12}, {0f, 0.55f, 0}, {0.3f, 0.35f, 14}};
                for (int k = 0; k < 3; k++) {
                    c.save();
                    c.rotate(cr[k][2], cx + cr[k][0] * r, cy + r * 0.5f);
                    Path prism = new Path();
                    float x = cx + cr[k][0] * r, h = cr[k][1] * r * 1.4f, w = r * 0.14f;
                    prism.moveTo(x - w, cy + r * 0.5f);
                    prism.lineTo(x - w, cy + r * 0.5f - h);
                    prism.lineTo(x, cy + r * 0.5f - h - w * 1.4f);
                    prism.lineTo(x + w, cy + r * 0.5f - h);
                    prism.lineTo(x + w, cy + r * 0.5f);
                    prism.close();
                    shade(p, new LinearGradient(x - w, 0, x + w, 0, cols[k], darken(cols[k], 0.3f), TileMode.CLAMP));
                    c.drawPath(prism, p);
                    p.setShader(null);
                    c.restore();
                }
                sparkle(c, p, cx + r * 0.45f, cy - r * 0.45f, r * 0.12f);
                break;
            }
            case "tree": {
                p.setColor(0xff6a4a2a);
                c.drawRect(cx - r * 0.08f, cy, cx + r * 0.08f, cy + r * 0.65f, p);
                int leafCol = t.decorColor != 0 ? t.decorColor : 0xff4a8a3a;
                for (float[] k : new float[][]{{0, -0.25f, 0.42f}, {-0.3f, 0f, 0.3f}, {0.3f, 0f, 0.3f}}) {
                    shade(p, new RadialGradient(cx + k[0] * r, cy + k[1] * r, k[2] * r, lighten(leafCol, 0.25f), leafCol, TileMode.CLAMP));
                    c.drawCircle(cx + k[0] * r, cy + k[1] * r, k[2] * r, p);
                }
                p.setShader(null);
                for (int k = 0; k < 5; k++) dot(c, p, cx + (float) Math.cos(k * 1.9) * r * 0.35f, cy - r * 0.2f + (float) Math.sin(k * 2.7) * r * 0.25f, r * 0.04f, lighten(accent, 0.4f));
                break;
            }
            case "eye": {
                // An Egyptian eye on a golden pyramid.
                Path pyr = new Path();
                pyr.moveTo(cx - r * 0.75f, cy + r * 0.55f);
                pyr.lineTo(cx, cy - r * 0.65f);
                pyr.lineTo(cx + r * 0.75f, cy + r * 0.55f);
                pyr.close();
                shade(p, metal(gold, cx - r, cy - r, cx + r, cy + r));
                c.drawPath(pyr, p);
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.06f);
                p.setColor(accent);
                Path eye = new Path();
                eye.moveTo(cx - r * 0.3f, cy + r * 0.1f);
                eye.quadTo(cx, cy - r * 0.15f, cx + r * 0.3f, cy + r * 0.1f);
                eye.quadTo(cx, cy + r * 0.3f, cx - r * 0.3f, cy + r * 0.1f);
                c.drawPath(eye, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy + r * 0.08f, r * 0.08f, p);
                break;
            }
            case "rocket": {
                dot(c, p, cx, cy, r, 0xff05080e);
                for (int k = 0; k < 10; k++) dot(c, p, cx + (float) Math.cos(k * 2.1) * r * 0.75f, cy + (float) Math.sin(k * 1.3) * r * 0.75f, r * 0.02f, 0xddffffff);
                c.save();
                c.rotate(35, cx, cy);
                flame(c, p, cx, cy + r * 0.55f, r * 0.2f, 0xffff5a3d);
                Path body = new Path();
                body.moveTo(cx, cy - r * 0.62f);
                body.cubicTo(cx + r * 0.2f, cy - r * 0.4f, cx + r * 0.18f, cy + r * 0.2f, cx + r * 0.15f, cy + r * 0.38f);
                body.lineTo(cx - r * 0.15f, cy + r * 0.38f);
                body.cubicTo(cx - r * 0.18f, cy + r * 0.2f, cx - r * 0.2f, cy - r * 0.4f, cx, cy - r * 0.62f);
                shade(p, new LinearGradient(cx - r * 0.2f, 0, cx + r * 0.2f, 0, 0xffffffff, 0xffa8b4c4, TileMode.CLAMP));
                c.drawPath(body, p);
                p.setShader(null);
                dot(c, p, cx, cy - r * 0.15f, r * 0.08f, accent);
                c.restore();
                break;
            }
            case "lx": {
                Paint tx = new Paint(Paint.ANTI_ALIAS_FLAG);
                tx.setTextAlign(Paint.Align.CENTER);
                tx.setTextSize(r * 0.85f);
                tx.setFakeBoldText(true);
                tx.setColor(gold);
                tx.setShadowLayer(r * 0.15f, 0, 0, gold);
                c.drawText("LX", cx, cy + r * 0.3f, tx);
                break;
            }
            case "flag":
                dot(c, p, cx, cy, r, 0xff006a4e);
                dot(c, p, cx - r * 0.1f, cy, r * 0.55f, FLAG_RED);
                break;
            case "palm": {
                p.setColor(0xff8a5a2a);
                Path trunk = new Path();
                trunk.moveTo(cx - r * 0.06f, cy + r * 0.65f);
                trunk.quadTo(cx + r * 0.05f, cy, cx + r * 0.02f, cy - r * 0.3f);
                trunk.lineTo(cx + r * 0.1f, cy - r * 0.3f);
                trunk.quadTo(cx + r * 0.15f, cy, cx + r * 0.08f, cy + r * 0.65f);
                trunk.close();
                c.drawPath(trunk, p);
                for (int k = 0; k < 6; k++) {
                    double ang = Math.toRadians(-160 + k * 28);
                    leaf(c, p, cx + r * 0.06f + (float) Math.cos(ang) * r * 0.3f, cy - r * 0.35f + (float) Math.sin(ang) * r * 0.2f, r * 0.3f, 0xff3ab06a, -70 + k * 28);
                }
                break;
            }
            case "cupid": {
                heart(c, p, cx - r * 0.15f, cy, r * 0.42f, accent);
                heart(c, p, cx + r * 0.2f, cy + r * 0.1f, r * 0.32f, lighten(accent, 0.3f));
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.06f);
                p.setColor(gold);
                c.drawLine(cx - r * 0.7f, cy + r * 0.45f, cx + r * 0.65f, cy - r * 0.45f, p);
                p.setStyle(Paint.Style.FILL);
                Path tip = new Path();
                tip.moveTo(cx + r * 0.72f, cy - r * 0.5f);
                tip.lineTo(cx + r * 0.5f, cy - r * 0.45f);
                tip.lineTo(cx + r * 0.62f, cy - r * 0.3f);
                tip.close();
                c.drawPath(tip, p);
                break;
            }
            case "lantern": {
                p.setColor(dark);
                c.drawRect(cx - r * 0.03f, cy - r * 0.75f, cx + r * 0.03f, cy - r * 0.45f, p);
                shade(p, new RadialGradient(cx, cy, r * 0.55f, 0xfffff3c4, accent, TileMode.CLAMP));
                c.drawOval(new RectF(cx - r * 0.35f, cy - r * 0.45f, cx + r * 0.35f, cy + r * 0.5f), p);
                p.setShader(null);
                p.setColor(gold);
                c.drawRect(cx - r * 0.25f, cy - r * 0.5f, cx + r * 0.25f, cy - r * 0.4f, p);
                c.drawRect(cx - r * 0.25f, cy + r * 0.45f, cx + r * 0.25f, cy + r * 0.55f, p);
                break;
            }
            default:
                crown(c, p, cx, cy, r, gold, dark);
                break;
        }
    }

    private static void crown(Canvas c, Paint p, float cx, float cy, float r, int gold, int dark) {
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
        shade(p, metal(gold, cx - w, top, cx + w, bottom));
        c.drawPath(path, p);
        p.setShader(null);
        dot(c, p, cx - w, top + r * 0.08f, r * 0.09f, lighten(gold, 0.5f));
        dot(c, p, cx, top - r * 0.04f, r * 0.1f, lighten(gold, 0.5f));
        dot(c, p, cx + w, top + r * 0.08f, r * 0.09f, lighten(gold, 0.5f));
        dot(c, p, cx, (band + bottom) / 2f, r * 0.08f, 0xffe0115f);
        p.setColor(dark);
        c.drawRect(cx - w, band - r * 0.02f, cx + w, band + r * 0.02f, p);
    }

    private static void rose(Canvas c, Paint p, float cx, float cy, float r, int color) {
        leaf(c, p, cx - r * 0.7f, cy + r * 0.55f, r * 0.35f, 0xff3a8a3a, -50);
        leaf(c, p, cx + r * 0.7f, cy + r * 0.55f, r * 0.35f, 0xff3a8a3a, 50);
        p.setStyle(Paint.Style.FILL);
        for (int ring = 3; ring >= 0; ring--) {
            float rr = r * (0.3f + ring * 0.22f);
            int col = ring % 2 == 0 ? color : darken(color, 0.18f);
            for (int k = 0; k < 5; k++) {
                double a = Math.PI * 2 * k / 5 + ring * 0.6;
                shade(p, new RadialGradient(cx, cy, rr * 1.3f, lighten(col, 0.25f), col, TileMode.CLAMP));
                c.drawCircle(cx + (float) Math.cos(a) * rr * 0.35f, cy + (float) Math.sin(a) * rr * 0.35f, rr * 0.55f, p);
            }
        }
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.05f);
        p.setColor(darken(color, 0.4f));
        Path sp = new Path();
        sp.moveTo(cx, cy);
        for (int i = 1; i <= 20; i++) {
            double a = i * 0.6;
            float rr = r * 0.018f * i;
            sp.lineTo(cx + (float) Math.cos(a) * rr, cy + (float) Math.sin(a) * rr);
        }
        c.drawPath(sp, p);
        p.setStyle(Paint.Style.FILL);
    }

    // ================================================================= yard patterns

    /** What is worked into a yard's panel (only inside it: never on a path). */
    private static void pattern(Canvas c, Paint p, RectF panel, float cell, int seed, Theme t) {
        if ("none".equals(t.pattern)) return;
        float round = cell * 0.5f, w = panel.width(), h = panel.height();
        c.save();
        Path clip = new Path();
        clip.addRoundRect(panel, round, round, Path.Direction.CW);
        c.clipPath(clip);
        Random rnd = new Random(31L * seed + t.id.hashCode());
        int accent = t.safe, trim = t.royal() ? t.trim : t.edge;
        switch (t.pattern) {
            case "veins":
                p.setStyle(Paint.Style.STROKE);
                for (int v = 0; v < 6; v++) {
                    Path path = new Path();
                    float x = panel.left + rnd.nextFloat() * w, y = panel.top;
                    path.moveTo(x, y);
                    for (int k = 0; k < 6; k++) {
                        x += (rnd.nextFloat() - 0.4f) * w * 0.35f;
                        y += h / 5f;
                        path.lineTo(x, y);
                    }
                    p.setStrokeWidth(cell * (0.03f + rnd.nextFloat() * 0.05f));
                    p.setColor(v % 2 == 0 ? 0x33ffffff : alpha(trim, 0x26));
                    c.drawPath(path, p);
                }
                break;
            case "hearts":
                for (int k = 0; k < 16; k++) {
                    float hx = panel.left + rnd.nextFloat() * w, hy = panel.top + rnd.nextFloat() * h;
                    float hr = cell * (0.12f + rnd.nextFloat() * 0.14f);
                    c.save();
                    c.rotate((rnd.nextFloat() - 0.5f) * 50, hx, hy);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(k % 3 == 0 ? alpha(accent, 0x77) : k % 3 == 1 ? 0x66ff80ab : 0x55f48fb1);
                    c.drawPath(DiceArt.heart(hx, hy, hr), p);
                    c.restore();
                }
                break;
            case "quilt": {
                float step = cell * 0.75f;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(cell * 0.035f);
                p.setColor(alpha(trim, 0x66));
                for (float d = -h; d < w + h; d += step) {
                    c.drawLine(panel.left + d, panel.top, panel.left + d + h, panel.bottom, p);
                    c.drawLine(panel.left + d, panel.bottom, panel.left + d + h, panel.top, p);
                }
                float half = step / 2f;
                for (float yy = panel.top; yy <= panel.bottom + 1; yy += half) {
                    int row = Math.round((yy - panel.top) / half);
                    for (float xx = panel.left + (row % 2 == 0 ? 0 : half); xx <= panel.right + 1; xx += step) dot(c, p, xx, yy, cell * 0.045f, alpha(lighten(trim, 0.3f), 0xcc));
                }
                break;
            }
            case "flag": {
                float sx = panel.centerX() - w * 0.03f, sy = panel.centerY(), sr = w * 0.43f;
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
            case "stars":
                for (int k = 0; k < 40; k++) {
                    dot(c, p, panel.left + rnd.nextFloat() * w, panel.top + rnd.nextFloat() * h, cell * (0.015f + rnd.nextFloat() * 0.03f), Color.argb(110 + rnd.nextInt(145), 255, 255, 255));
                }
                for (int k = 0; k < 4; k++) star(c, p, panel.left + rnd.nextFloat() * w, panel.top + rnd.nextFloat() * h, cell * 0.12f, 0xddffe08a);
                crescent(c, p, panel.right - w * 0.18f, panel.top + h * 0.18f, cell * 0.28f, 0xfffff3c4);
                break;
            case "roses":
                for (int k = 0; k < 5; k++) {
                    float x = panel.left + w * (0.12f + rnd.nextFloat() * 0.76f), y = panel.top + h * (0.12f + rnd.nextFloat() * 0.76f);
                    rose(c, p, x, y, cell * (0.22f + rnd.nextFloat() * 0.1f), k % 2 == 0 ? 0xffd81b47 : 0xffff7aa8);
                }
                break;
            case "petals":
                for (int k = 0; k < 18; k++) {
                    float x = panel.left + rnd.nextFloat() * w, y = panel.top + rnd.nextFloat() * h;
                    c.save();
                    c.rotate(rnd.nextFloat() * 360, x, y);
                    p.setShader(null);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(k % 2 == 0 ? 0x99ffb7cf : 0x88ffd6e4);
                    c.drawOval(new RectF(x - cell * 0.16f, y - cell * 0.08f, x + cell * 0.16f, y + cell * 0.08f), p);
                    c.restore();
                }
                for (int k = 0; k < 3; k++) flower(c, p, panel.left + w * (0.15f + rnd.nextFloat() * 0.7f), panel.top + h * (0.15f + rnd.nextFloat() * 0.7f), cell * 0.25f, 0xffffb7cf, 0xffffe08a, 5);
                break;
            case "vines":
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(cell * 0.05f);
                p.setColor(0x885aa04a);
                for (int k = 0; k < 3; k++) {
                    Path v = new Path();
                    v.moveTo(panel.left, panel.top + h * (0.2f + k * 0.3f));
                    v.cubicTo(panel.left + w * 0.3f, panel.top + h * (k * 0.3f), panel.left + w * 0.6f, panel.top + h * (0.4f + k * 0.3f), panel.right, panel.top + h * (0.2f + k * 0.3f));
                    c.drawPath(v, p);
                }
                for (int k = 0; k < 8; k++) dot(c, p, panel.left + rnd.nextFloat() * w, panel.top + rnd.nextFloat() * h, cell * 0.05f, 0xccc8ff7a);
                for (int k = 0; k < 7; k++) leaf(c, p, panel.left + rnd.nextFloat() * w, panel.top + rnd.nextFloat() * h, cell * 0.16f, 0xff5aa04a, rnd.nextFloat() * 360);
                break;
            case "leaves":
                for (int k = 0; k < 10; k++) {
                    leaf(c, p, panel.left + rnd.nextFloat() * w, panel.top + rnd.nextFloat() * h, cell * (0.18f + rnd.nextFloat() * 0.14f),
                            k % 2 == 0 ? 0xff3ab06a : 0xff6ac04a, rnd.nextFloat() * 360);
                }
                break;
            case "waves":
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(cell * 0.05f);
                for (int k = 0; k < 7; k++) {
                    Path wv = new Path();
                    float y = panel.top + h * (0.1f + k * 0.14f);
                    wv.moveTo(panel.left, y);
                    for (float x = 0; x <= w; x += cell * 0.5f) wv.quadTo(panel.left + x + cell * 0.125f, y - cell * 0.12f, panel.left + x + cell * 0.25f, y);
                    p.setColor(k % 2 == 0 ? 0x5540a0d0 : 0x44ffffff);
                    c.drawPath(wv, p);
                }
                for (int k = 0; k < 6; k++) dot(c, p, panel.left + rnd.nextFloat() * w, panel.top + rnd.nextFloat() * h, cell * 0.06f, 0x66ffffff);
                break;
            case "sunset": {
                p.setStyle(Paint.Style.FILL);
                shade(p, new RadialGradient(panel.centerX(), panel.bottom - h * 0.2f, w * 0.3f, 0xfffff0b0, 0x00ffb070, TileMode.CLAMP));
                c.drawCircle(panel.centerX(), panel.bottom - h * 0.2f, w * 0.3f, p);
                p.setShader(null);
                p.setColor(0x55ffffff);
                for (int k = 0; k < 4; k++) c.drawRect(panel.left, panel.bottom - h * (0.12f + k * 0.07f), panel.right, panel.bottom - h * (0.12f + k * 0.07f) + cell * 0.04f, p);
                break;
            }
            case "sand":
                for (int k = 0; k < 120; k++) dot(c, p, panel.left + rnd.nextFloat() * w, panel.top + rnd.nextFloat() * h, cell * 0.02f, rnd.nextBoolean() ? 0x22a0702a : 0x22ffffff);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(cell * 0.03f);
                p.setColor(0x33a0702a);
                for (int k = 0; k < 5; k++) {
                    Path rp = new Path();
                    float y = panel.top + h * (0.15f + k * 0.18f);
                    rp.moveTo(panel.left, y);
                    rp.quadTo(panel.centerX(), y - cell * 0.3f, panel.right, y);
                    c.drawPath(rp, p);
                }
                pearl(c, p, panel.right - w * 0.2f, panel.bottom - h * 0.2f, cell * 0.12f);
                break;
            case "grid":
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                for (int pass = 0; pass < 2; pass++) {
                    p.setStrokeWidth(cell * (pass == 0 ? 0.08f : 0.025f));
                    p.setColor(pass == 0 ? alpha(trim, 0x30) : alpha(trim, 0xcc));
                    for (float x = panel.left; x <= panel.right; x += cell * 0.6f) c.drawLine(x, panel.top, x, panel.bottom, p);
                    for (float y = panel.top; y <= panel.bottom; y += cell * 0.6f) c.drawLine(panel.left, y, panel.right, y, p);
                }
                p.setStyle(Paint.Style.FILL);
                break;
            case "snow":
                for (int k = 0; k < 7; k++) snowflakeMark(c, p, panel.left + rnd.nextFloat() * w, panel.top + rnd.nextFloat() * h, cell * (0.12f + rnd.nextFloat() * 0.1f), 0xaaffffff);
                for (int k = 0; k < 30; k++) dot(c, p, panel.left + rnd.nextFloat() * w, panel.top + rnd.nextFloat() * h, cell * 0.03f, 0x99ffffff);
                break;
            case "nebula":
                for (int k = 0; k < 4; k++) {
                    float x = panel.left + rnd.nextFloat() * w, y = panel.top + rnd.nextFloat() * h, rr = w * (0.3f + rnd.nextFloat() * 0.3f);
                    int col = k % 2 == 0 ? 0xffc77dff : 0xff5ad0ff;
                    shade(p, new RadialGradient(x, y, rr, alpha(col, 0x66), alpha(col, 0), TileMode.CLAMP));
                    c.drawCircle(x, y, rr, p);
                }
                p.setShader(null);
                for (int k = 0; k < 40; k++) {
                    dot(c, p, panel.left + rnd.nextFloat() * w, panel.top + rnd.nextFloat() * h, cell * (0.012f + rnd.nextFloat() * 0.025f), Color.argb(120 + rnd.nextInt(135), 255, 255, 255));
                }
                break;
            case "hieroglyphs":
                p.setShader(null);
                p.setStyle(Paint.Style.FILL);
                p.setColor(alpha(t.edge, 0x55));
                for (int row = 0; row < 5; row++) {
                    for (int col = 0; col < 6; col++) {
                        float x = panel.left + w * (0.1f + col * 0.16f), y = panel.top + h * (0.12f + row * 0.19f), s = cell * 0.12f;
                        switch ((row * 7 + col) % 4) {
                            case 0: c.drawCircle(x, y, s * 0.6f, p); break;
                            case 1: c.drawRect(x - s * 0.15f, y - s, x + s * 0.15f, y + s, p); break;
                            case 2: {
                                Path tri = new Path();
                                tri.moveTo(x - s, y + s * 0.6f);
                                tri.lineTo(x, y - s);
                                tri.lineTo(x + s, y + s * 0.6f);
                                tri.close();
                                c.drawPath(tri, p);
                                break;
                            }
                            default: c.drawOval(new RectF(x - s, y - s * 0.4f, x + s, y + s * 0.4f), p); break;
                        }
                    }
                }
                break;
            case "damask":
                // A repeating royal ornament: little diamonds with a bead.
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(cell * 0.03f);
                p.setColor(alpha(trim, 0x55));
                for (float yy = panel.top + cell * 0.4f; yy < panel.bottom; yy += cell * 0.9f) {
                    for (float xx = panel.left + cell * 0.4f; xx < panel.right; xx += cell * 0.9f) {
                        Path d = new Path();
                        d.moveTo(xx, yy - cell * 0.25f);
                        d.quadTo(xx + cell * 0.25f, yy, xx, yy + cell * 0.25f);
                        d.quadTo(xx - cell * 0.25f, yy, xx, yy - cell * 0.25f);
                        c.drawPath(d, p);
                        c.drawCircle(xx, yy, cell * 0.05f, p);
                    }
                }
                p.setStyle(Paint.Style.FILL);
                break;
            case "bubbles":
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(cell * 0.025f);
                p.setColor(0x88ffffff);
                for (int k = 0; k < 14; k++) c.drawCircle(panel.left + rnd.nextFloat() * w, panel.top + rnd.nextFloat() * h, cell * (0.06f + rnd.nextFloat() * 0.14f), p);
                p.setStyle(Paint.Style.FILL);
                break;
            case "aurora": {
                int[] bands = {0x885affc8, 0x77a06bff, 0x665ad0ff};
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(cell * 0.35f);
                for (int k = 0; k < 3; k++) {
                    Path wv = new Path();
                    float y = panel.top + h * (0.25f + k * 0.18f);
                    wv.moveTo(panel.left, y);
                    wv.cubicTo(panel.left + w * 0.3f, y - h * 0.25f, panel.left + w * 0.6f, y + h * 0.2f, panel.right, y - h * 0.1f);
                    p.setColor(bands[k]);
                    c.drawPath(wv, p);
                }
                for (int k = 0; k < 25; k++) dot(c, p, panel.left + rnd.nextFloat() * w, panel.top + rnd.nextFloat() * h * 0.5f, cell * 0.02f, 0xaaffffff);
                break;
            }
            case "lights": {
                // Strings of warm lights across the panel.
                for (int k = 0; k < 3; k++) {
                    float y0 = panel.top + h * (0.18f + k * 0.3f);
                    Path wire = new Path();
                    wire.moveTo(panel.left, y0);
                    wire.quadTo(panel.centerX(), y0 + h * 0.15f, panel.right, y0);
                    p.setShader(null);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(cell * 0.02f);
                    p.setColor(0x66000000);
                    c.drawPath(wire, p);
                    android.graphics.PathMeasure pm = new android.graphics.PathMeasure(wire, false);
                    float[] pos = new float[2];
                    for (float d = cell * 0.3f; d < pm.getLength(); d += cell * 0.6f) {
                        pm.getPosTan(d, pos, null);
                        p.setStyle(Paint.Style.FILL);
                        shade(p, new RadialGradient(pos[0], pos[1], cell * 0.2f, 0xccffe08a, 0x00ffe08a, TileMode.CLAMP));
                        c.drawCircle(pos[0], pos[1], cell * 0.2f, p);
                        dot(c, p, pos[0], pos[1], cell * 0.06f, 0xfffff3c4);
                    }
                }
                p.setStyle(Paint.Style.FILL);
                break;
            }
            case "flames":
                for (int k = 0; k < 6; k++) flame(c, p, panel.left + w * (0.1f + k * 0.16f), panel.bottom - h * 0.12f, cell * (0.3f + rnd.nextFloat() * 0.2f), 0xffff5a1e);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(cell * 0.05f);
                p.setColor(0x99ff8a1e);
                for (int k = 0; k < 4; k++) {
                    Path cr = new Path();
                    float x = panel.left + rnd.nextFloat() * w, y = panel.top + rnd.nextFloat() * h * 0.6f;
                    cr.moveTo(x, y);
                    for (int j = 0; j < 3; j++) {
                        x += (rnd.nextFloat() - 0.5f) * cell;
                        y += cell * 0.4f;
                        cr.lineTo(x, y);
                    }
                    c.drawPath(cr, p);
                }
                p.setStyle(Paint.Style.FILL);
                break;
            case "crystals":
                for (int k = 0; k < 7; k++) {
                    gem(c, p, panel.left + rnd.nextFloat() * w, panel.top + rnd.nextFloat() * h, cell * (0.12f + rnd.nextFloat() * 0.1f), k % 2 == 0 ? lighten(accent, 0.3f) : 0xffffffff);
                }
                break;
            case "scales": {
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(cell * 0.03f);
                p.setColor(alpha(trim, 0x55));
                float s = cell * 0.5f;
                for (int row = 0; row * s * 0.5f < h + s; row++) {
                    float off = row % 2 == 0 ? 0 : s / 2f;
                    for (float x = panel.left - s + off; x < panel.right + s; x += s) {
                        c.drawArc(new RectF(x, panel.top + row * s * 0.5f - s / 2f, x + s, panel.top + row * s * 0.5f + s / 2f), 0, 180, false, p);
                    }
                }
                p.setStyle(Paint.Style.FILL);
                break;
            }
            case "clouds":
                for (int k = 0; k < 4; k++) {
                    float x = panel.left + w * (0.15f + rnd.nextFloat() * 0.7f), y = panel.top + h * (0.15f + rnd.nextFloat() * 0.7f), r = cell * (0.18f + rnd.nextFloat() * 0.1f);
                    dot(c, p, x, y, r, 0xaaffffff);
                    dot(c, p, x + r * 0.9f, y + r * 0.15f, r * 0.8f, 0xaaffffff);
                    dot(c, p, x - r * 0.9f, y + r * 0.2f, r * 0.7f, 0xaaffffff);
                }
                for (int k = 0; k < 6; k++) star(c, p, panel.left + rnd.nextFloat() * w, panel.top + rnd.nextFloat() * h, cell * 0.08f, 0xccffe08a);
                break;
            default:
                break;
        }
        p.setShader(null);
        c.restore();
    }
}
