package com.ludox.game.gl;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Shader.TileMode;
import android.util.LruCache;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * How every die in the dice shop looks, and the painting of it. Each premium die has its own
 * material (polished metal, faceted gemstone, clear crystal, obsidian, nebula, neon, lava),
 * its own engravings and its own pips, painted onto six face pictures; {@link #drawCube}
 * maps those onto a real cube in perspective with bevelled edges, light, shade and a
 * specular highlight. The same faces make the 3D board's die texture ({@link #atlas}).
 * Looks never change a roll: the server rolls the same for every die.
 */
public final class DiceArt {
    private DiceArt() {}

    public enum Mat { GLOSS, METAL, GEM, CRYSTAL, STONE, COSMIC, NEON, LAVA, FLAG }

    public enum Motif {
        NONE, FILIGREE, CROWN, CROWN_GEMS, DRAGON, PHOENIX, SNOWFLAKE, NEBULA_RING, CIRCUIT, HEARTS, SKULL, BOLT,
        TIGER, PEACOCK, PHARAOH, BUTTERFLY, LUDOX, FACETS
    }

    public enum Pip { DOT, GEM, GLOW, GOLD }

    public static final class Look {
        public final String id;
        final Mat mat;
        /** Light, middle and dark tones of the material; the bevel; the engravings. */
        final int light, mid, dark, edge, engrave;
        final int pip, one;
        final Motif motif;
        final Pip pipStyle;
        /** How shiny (0 = matte, 1 = mirror). */
        final float shine;
        /** Glow colour around the die in the shop (0 = none). */
        public final int aura;
        /** A light sweep keeps moving over the faces (legendary and up). */
        public final boolean sheen;
        /** Country flag on the faces (flag dice). */
        final String flag;

        Look(String id, Mat mat, int light, int mid, int dark, int edge, int engrave, int pip, int one, Motif motif, Pip pipStyle,
             float shine, int aura, boolean sheen, String flag) {
            this.id = id;
            this.mat = mat;
            this.light = light;
            this.mid = mid;
            this.dark = dark;
            this.edge = edge;
            this.engrave = engrave;
            this.pip = pip;
            this.one = one;
            this.motif = motif;
            this.pipStyle = pipStyle;
            this.shine = shine;
            this.aura = aura;
            this.sheen = sheen;
            this.flag = flag;
        }
    }

    private static final int GOLD = 0xffffcf40, GOLD_DARK = 0xff8a5a00, RUBY = 0xffe0115f, PURPLE = 0xff9b4dff;

    private static final Map<String, Look> LOOKS = new HashMap<>();

    private static void add(String id, Mat mat, int light, int mid, int dark, int edge, int engrave, int pip, int one, Motif motif, Pip ps,
                            float shine, int aura, boolean sheen) {
        LOOKS.put(id, new Look(id, mat, light, mid, dark, edge, engrave, pip, one, motif, ps, shine, aura, sheen, null));
    }

    static {
        // Classic.
        add("classic", Mat.GLOSS, 0xffffffff, 0xffeeeaf6, 0xffc9c5d8, 0xffbdb8cf, 0, 0xff23202e, 0xffd62839, Motif.NONE, Pip.DOT, 0.35f, 0, false);
        add("midnight", Mat.GLOSS, 0xff4a5080, 0xff262a52, 0xff10122a, 0xff05060f, 0, 0xffffd34d, 0xffff5c6c, Motif.NONE, Pip.GOLD, 0.45f, 0, false);
        add("candy", Mat.GLOSS, 0xffffe3f3, 0xffffa6d6, 0xffff7ac2, 0xffb33c86, 0, 0xff7a1f5c, 0xffffffff, Motif.NONE, Pip.DOT, 0.6f, 0, false);
        // Royal.
        add("gold", Mat.METAL, 0xfffff1b0, 0xffffcf40, 0xffb07800, 0xff7a4f00, 0xff9a6a00, 0xff5a2d00, 0xffc4142d, Motif.FILIGREE, Pip.GEM, 1f, 0xffffc94a, true);
        add("emperor", Mat.METAL, 0xff4a4234, 0xff201c15, 0xff080706, 0xffd4a017, GOLD, GOLD, GOLD, Motif.CROWN, Pip.GOLD, 0.9f, 0xffd4a017, true);
        add("king_crown", Mat.METAL, 0xffffffff, 0xffeee9df, 0xffc8bfa8, 0xffd9c27a, 0xffc9a64a, 0xffb8860b, 0xff1a4fc4, Motif.CROWN_GEMS, Pip.GEM, 0.85f, 0xfff3e2a8, false);
        add("royal_diamond", Mat.CRYSTAL, 0xffffffff, 0xffd6f4ff, 0xff8fd3f5, 0xffd4a017, 0xffffffff, GOLD, GOLD, Motif.FACETS, Pip.GOLD, 1f, 0xffbfeaff, true);
        add("black_royal", Mat.STONE, 0xff3a3640, 0xff16141a, 0xff050406, 0xffd4a017, 0x55d4a017, RUBY, RUBY, Motif.FILIGREE, Pip.GEM, 0.85f, 0xffd4a017, false);
        // Gemstones.
        add("diamond", Mat.CRYSTAL, 0xffffffff, 0xffc8eeff, 0xff6fcaf0, 0xff9adfff, 0xffffffff, 0xff1e9bff, 0xff1e9bff, Motif.FACETS, Pip.GLOW, 1f, 0xff7fdcff, false);
        add("ruby", Mat.GEM, 0xffff8a96, 0xffd01530, 0xff6a0612, 0xffd4a017, 0xffff9aa8, GOLD, 0xffffe27a, Motif.FACETS, Pip.GOLD, 0.95f, 0xffff4060, false);
        add("sapphire", Mat.GEM, 0xff8ec0ff, 0xff1a4fc4, 0xff081e5a, 0xffc9d1e0, 0xffa8c8ff, 0xffeef3fa, 0xffeef3fa, Motif.FACETS, Pip.GOLD, 0.95f, 0xff4f8cff, false);
        add("emerald", Mat.GEM, 0xff7dffc0, 0xff0f9a56, 0xff04401f, 0xffd4a017, 0xff9affd0, GOLD, 0xffffe27a, Motif.FACETS, Pip.GOLD, 0.95f, 0xff2fe08a, false);
        add("amethyst", Mat.CRYSTAL, 0xfff2e2ff, 0xffb57cf0, 0xff5a1aa0, 0xffd6b3ff, 0xffffffff, 0xffffffff, 0xffffe27a, Motif.FACETS, Pip.GLOW, 0.95f, 0xffb57cf0, false);
        // Fantasy.
        add("dragon", Mat.METAL, 0xff55505a, 0xff2a272e, 0xff0d0c10, 0xffd4a017, GOLD, GOLD, 0xffff4500, Motif.DRAGON, Pip.GOLD, 0.8f, 0xffff8a00, true);
        add("phoenix", Mat.LAVA, 0xffffe08a, 0xffff6a1e, 0xff7a1408, 0xffffb347, 0xfffff3c4, 0xfffff8e0, 0xfffff8e0, Motif.PHOENIX, Pip.GLOW, 0.6f, 0xffff6a1e, true);
        add("ice_king", Mat.CRYSTAL, 0xffffffff, 0xffd2f2ff, 0xff8cc8e6, 0xffbfe6f5, 0xffffffff, 0xff1f6f9e, 0xff1f6f9e, Motif.SNOWFLAKE, Pip.GEM, 0.95f, 0xff9fe6ff, false);
        add("galaxy", Mat.COSMIC, 0xff6f4bd8, 0xff2a1a6e, 0xff07041a, 0xff6f4bd8, 0xffffffff, 0xff9ef0ff, 0xffff7ad9, Motif.NONE, Pip.GLOW, 0.6f, 0xff8a5cff, false);
        add("cosmic", Mat.COSMIC, 0xffb06bff, 0xff4a1a8a, 0xff12052a, 0xffffcf40, GOLD, 0xffffe27a, 0xffffe27a, Motif.NEBULA_RING, Pip.GLOW, 0.8f, 0xffc77dff, true);
        // Special.
        add("neon", Mat.NEON, 0xff2a3070, 0xff111538, 0xff05061a, 0xff39ffd6, 0xffff3df2, 0xff39ffd6, 0xffff3df2, Motif.CIRCUIT, Pip.GLOW, 0.5f, 0xff39ffd6, false);
        add("fire", Mat.LAVA, 0xff4a3530, 0xff1e120e, 0xff080302, 0xffff6a00, 0xffff8a1e, 0xffffc070, 0xffffc070, Motif.NONE, Pip.GLOW, 0.4f, 0xffff5a00, false);
        add("love_heart", Mat.CRYSTAL, 0xffffffff, 0xffffc4dc, 0xffff7aa8, 0xffffb3cf, 0xffff4f8a, 0xffffffff, 0xffe91e63, Motif.HEARTS, Pip.GEM, 0.9f, 0xffff7aa8, false);
        add("skull_king", Mat.METAL, 0xff6a6a72, 0xff34343a, 0xff121214, 0xff9aa0a8, 0xffc8ccd2, 0xffeeeeee, 0xffff3b3b, Motif.SKULL, Pip.DOT, 0.7f, 0xff9aa0a8, false);
        add("lightning", Mat.GEM, 0xff6fa0ff, 0xff1e4fd8, 0xff07175a, 0xff9ee6ff, 0xffeaffff, 0xffeaffff, 0xffffe600, Motif.BOLT, Pip.GLOW, 0.8f, 0xff7fd8ff, false);
        // Exclusive.
        add("golden_tiger", Mat.METAL, 0xffffe9a0, 0xffffc230, 0xffa86a00, 0xff6a4200, 0xff2a1600, 0xff2a1600, 0xff2a1600, Motif.TIGER, Pip.DOT, 1f, 0xffffb020, true);
        add("peacock", Mat.GEM, 0xff5fe0d0, 0xff0f7a8a, 0xff06304a, 0xffd4a017, GOLD, GOLD, GOLD, Motif.PEACOCK, Pip.GOLD, 0.9f, 0xff1fd0c0, false);
        add("pharaoh", Mat.METAL, 0xfffff0b8, 0xfff0c35a, 0xff9a6a08, 0xff1a3a8a, 0xff1a3a8a, 0xff1a3a8a, 0xff1a3a8a, Motif.PHARAOH, Pip.GEM, 1f, 0xffffc94a, true);
        add("butterfly", Mat.CRYSTAL, 0xffffffff, 0xffe8dcff, 0xffb69cf0, 0xffd6c4ff, 0xffff7ad9, 0xff7a4fd8, 0xffff7ad9, Motif.BUTTERFLY, Pip.GEM, 0.95f, 0xffd6a8ff, false);
        add("ludox_ultimate", Mat.GEM, 0xffc690ff, 0xff6a1fd0, 0xff1e0548, GOLD, GOLD, GOLD, GOLD, Motif.LUDOX, Pip.GOLD, 1f, 0xffc77dff, true);
    }

    /** The look for this die: a premium one by id, else plain colours (old or unknown dice, flags). */
    public static Look look(String id, int face, int faceBottom, int rim, int pip, int one, String flag) {
        Look l = id != null ? LOOKS.get(id) : null;
        if (l != null) return l;
        String key = id + "|" + face + "|" + faceBottom + "|" + rim + "|" + pip + "|" + one + "|" + flag;
        synchronized (PLAIN) {
            Look p = PLAIN.get(key);
            if (p == null) {
                p = new Look(id != null ? id : "plain", flag != null ? Mat.FLAG : Mat.GLOSS, face, blend(face, faceBottom, 0.5f), faceBottom, rim, 0,
                        pip, one, Motif.NONE, Pip.DOT, 0.4f, 0, false, flag);
                PLAIN.put(key, p);
            }
            return p;
        }
    }

    private static final Map<String, Look> PLAIN = new HashMap<>();

    public static boolean premium(String id) {
        return id != null && LOOKS.containsKey(id);
    }

    // ================================================================= faces

    /** Flag pictures are drawn by the app's flag code (in the ui package). */
    public interface FlagSource {
        Bitmap flag(String code);
    }

    public static volatile FlagSource flags;

    /** Painted faces, by look, value and size (painting is slow, faces are drawn often). */
    private static final LruCache<String, Bitmap> FACES = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    /** The face showing `value`, as a square picture with rounded (transparent) corners. */
    public static Bitmap face(Look l, int value, int size) {
        String key = l.id + "|" + l.light + "|" + value + "|" + size;
        Bitmap b = FACES.get(key);
        if (b == null) {
            b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            paintFace(new Canvas(b), size, l, value);
            FACES.put(key, b);
        }
        return b;
    }

    private static final float[] AT = {0.27f, 0.5f, 0.73f};
    private static final int[][][] FACE_PIPS = {
            {},
            {{1, 1}},
            {{2, 0}, {0, 2}},
            {{2, 0}, {1, 1}, {0, 2}},
            {{0, 0}, {2, 0}, {0, 2}, {2, 2}},
            {{0, 0}, {2, 0}, {1, 1}, {0, 2}, {2, 2}},
            {{0, 0}, {2, 0}, {0, 1}, {2, 1}, {0, 2}, {2, 2}},
    };

    static void paintFace(Canvas c, float s, Look l, int value) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        RectF r = new RectF(0, 0, s, s);
        float round = s * 0.2f;
        Path clip = new Path();
        clip.addRoundRect(r, round, round, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        Random rnd = new Random(l.id.hashCode() * 31L + value * 7L);
        material(c, p, s, l, rnd);
        motif(c, p, s, l, value, rnd);
        // Glossy coat: a soft light across the top of the face.
        float coat = 0.08f + 0.22f * l.shine;
        shade(p, new LinearGradient(0, 0, 0, s * 0.55f, Color.argb((int) (255 * coat), 255, 255, 255), 0x00ffffff, TileMode.CLAMP));
        c.drawRect(0, 0, s, s * 0.55f, p);
        p.setShader(null);
        c.restore();
        bevel(c, p, s, l);
        pips(c, p, s, l, value);
    }

    /** The surface the face is made of. */
    private static void material(Canvas c, Paint p, float s, Look l, Random rnd) {
        p.setStyle(Paint.Style.FILL);
        switch (l.mat) {
            case METAL: {
                // Polished metal: bright and dark bands across the face, then fine brushing.
                shade(p, new LinearGradient(0, 0, s, s, new int[]{l.light, l.mid, l.dark, l.mid, l.light, l.mid},
                        new float[]{0f, 0.25f, 0.45f, 0.62f, 0.8f, 1f}, TileMode.CLAMP));
                c.drawRect(0, 0, s, s, p);
                p.setShader(null);
                p.setStrokeWidth(Math.max(1f, s * 0.003f));
                for (int i = 0; i < 70; i++) {
                    float y = rnd.nextFloat() * s;
                    p.setColor(rnd.nextBoolean() ? 0x10ffffff : 0x0c000000);
                    c.drawLine(0, y, s, y + (rnd.nextFloat() - 0.5f) * s * 0.02f, p);
                }
                break;
            }
            case GEM: {
                // A cut gemstone: light from inside, a flat table in the middle and facets round it.
                shade(p, new RadialGradient(s * 0.4f, s * 0.35f, s * 0.85f, new int[]{l.light, l.mid, l.dark}, new float[]{0f, 0.45f, 1f}, TileMode.CLAMP));
                c.drawRect(0, 0, s, s, p);
                p.setShader(null);
                float t = s * 0.3f;
                float[][] o = {{0, 0}, {s, 0}, {s, s}, {0, s}};
                float[][] in = {{t, t}, {s - t, t}, {s - t, s - t}, {t, s - t}};
                for (int k = 0; k < 4; k++) {
                    Path f = new Path();
                    f.moveTo(o[k][0], o[k][1]);
                    f.lineTo(o[(k + 1) % 4][0], o[(k + 1) % 4][1]);
                    f.lineTo(in[(k + 1) % 4][0], in[(k + 1) % 4][1]);
                    f.lineTo(in[k][0], in[k][1]);
                    f.close();
                    p.setColor(k == 0 ? 0x30ffffff : k == 1 ? 0x18ffffff : k == 2 ? 0x28000000 : 0x14000000);
                    c.drawPath(f, p);
                }
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.006f);
                p.setColor(0x40ffffff);
                for (int k = 0; k < 4; k++) c.drawLine(o[k][0], o[k][1], in[k][0], in[k][1], p);
                c.drawRect(t, t, s - t, s - t, p);
                p.setStyle(Paint.Style.FILL);
                break;
            }
            case CRYSTAL: {
                // Clear crystal: bright, with the cube's far edges seen through it and glare streaks.
                shade(p, new RadialGradient(s * 0.5f, s * 0.45f, s * 0.8f, new int[]{l.light, l.mid, l.dark}, new float[]{0f, 0.6f, 1f}, TileMode.CLAMP));
                c.drawRect(0, 0, s, s, p);
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.008f);
                p.setColor((l.dark & 0x00ffffff) | 0x55000000);
                float in = s * 0.2f;
                c.drawRoundRect(new RectF(in, in, s - in, s - in), s * 0.1f, s * 0.1f, p);
                c.drawLine(0, 0, in, in, p);
                c.drawLine(s, 0, s - in, in, p);
                c.drawLine(0, s, in, s - in, p);
                c.drawLine(s, s, s - in, s - in, p);
                p.setStyle(Paint.Style.FILL);
                // Rainbow fire and glare.
                for (int k = 0; k < 3; k++) {
                    float x = s * (0.15f + k * 0.28f);
                    Path g = new Path();
                    g.moveTo(x, 0);
                    g.lineTo(x + s * 0.12f, 0);
                    g.lineTo(x - s * 0.25f, s);
                    g.lineTo(x - s * 0.37f, s);
                    g.close();
                    p.setColor(k == 1 ? 0x33ffffff : 0x1fffffff);
                    c.drawPath(g, p);
                }
                int[] fire = {0x22ff5c8a, 0x2240c4ff, 0x22ffe066, 0x2280ff9a};
                for (int k = 0; k < 6; k++) {
                    p.setColor(fire[k % fire.length]);
                    c.drawCircle(rnd.nextFloat() * s, rnd.nextFloat() * s, s * (0.05f + rnd.nextFloat() * 0.08f), p);
                }
                break;
            }
            case STONE: {
                // Obsidian: near-black with soft veins and a glassy sheen.
                shade(p, new LinearGradient(0, 0, s, s, l.light, l.dark, TileMode.CLAMP));
                c.drawRect(0, 0, s, s, p);
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                for (int v = 0; v < 5; v++) {
                    Path path = new Path();
                    float x = rnd.nextFloat() * s, y = 0;
                    path.moveTo(x, y);
                    for (int k = 0; k < 5; k++) {
                        x += (rnd.nextFloat() - 0.45f) * s * 0.35f;
                        y += s / 4.5f;
                        path.lineTo(x, y);
                    }
                    p.setStrokeWidth(s * (0.004f + rnd.nextFloat() * 0.012f));
                    p.setColor(v % 2 == 0 ? 0x22ffffff : 0x18d4a017);
                    c.drawPath(path, p);
                }
                p.setStyle(Paint.Style.FILL);
                break;
            }
            case COSMIC: {
                // Deep space: a dark base, glowing nebula clouds and stars.
                p.setColor(l.dark);
                c.drawRect(0, 0, s, s, p);
                for (int k = 0; k < 4; k++) {
                    float x = rnd.nextFloat() * s, y = rnd.nextFloat() * s, rr = s * (0.35f + rnd.nextFloat() * 0.35f);
                    int col = k % 2 == 0 ? l.light : l.mid;
                    shade(p, new RadialGradient(x, y, rr, (col & 0x00ffffff) | 0x99000000, col & 0x00ffffff, TileMode.CLAMP));
                    c.drawCircle(x, y, rr, p);
                }
                p.setShader(null);
                if (l.engrave != 0) {
                    shade(p, new RadialGradient(s * 0.7f, s * 0.3f, s * 0.4f, (l.engrave & 0x00ffffff) | 0x44000000, l.engrave & 0x00ffffff, TileMode.CLAMP));
                    c.drawCircle(s * 0.7f, s * 0.3f, s * 0.4f, p);
                    p.setShader(null);
                }
                for (int k = 0; k < 60; k++) {
                    float sr = s * (0.002f + rnd.nextFloat() * rnd.nextFloat() * 0.012f);
                    p.setColor(Color.argb(120 + rnd.nextInt(135), 255, 255, 255));
                    c.drawCircle(rnd.nextFloat() * s, rnd.nextFloat() * s, sr, p);
                }
                break;
            }
            case NEON: {
                shade(p, new LinearGradient(0, 0, 0, s, l.light, l.dark, TileMode.CLAMP));
                c.drawRect(0, 0, s, s, p);
                p.setShader(null);
                // Glowing tube just inside the edge: wide and faint, then narrow and bright.
                p.setStyle(Paint.Style.STROKE);
                float in = s * 0.11f;
                for (int k = 3; k >= 0; k--) {
                    p.setStrokeWidth(s * (0.012f + k * 0.02f));
                    p.setColor(k == 0 ? 0xffffffff : (l.edge & 0x00ffffff) | ((0x50 - k * 0x10) << 24));
                    c.drawRoundRect(new RectF(in, in, s - in, s - in), s * 0.12f, s * 0.12f, p);
                }
                p.setStyle(Paint.Style.FILL);
                break;
            }
            case LAVA: {
                // Volcanic rock with glowing cracks.
                shade(p, new RadialGradient(s * 0.5f, s * 0.5f, s * 0.8f, l.mid, l.dark, TileMode.CLAMP));
                c.drawRect(0, 0, s, s, p);
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                for (int k = 0; k < 7; k++) {
                    Path crack = new Path();
                    float x = rnd.nextFloat() * s, y = rnd.nextFloat() * s;
                    crack.moveTo(x, y);
                    for (int j = 0; j < 4; j++) {
                        x += (rnd.nextFloat() - 0.5f) * s * 0.35f;
                        y += (rnd.nextFloat() - 0.5f) * s * 0.35f;
                        crack.lineTo(x, y);
                    }
                    p.setStrokeWidth(s * 0.04f);
                    p.setColor((l.edge & 0x00ffffff) | 0x55000000);
                    c.drawPath(crack, p);
                    p.setStrokeWidth(s * 0.014f);
                    p.setColor(l.light);
                    c.drawPath(crack, p);
                }
                p.setStyle(Paint.Style.FILL);
                break;
            }
            case FLAG: {
                p.setColor(l.mid);
                c.drawRect(0, 0, s, s, p);
                FlagSource f = flags;
                Bitmap flag = f != null && l.flag != null ? f.flag(l.flag) : null;
                if (flag != null) c.drawBitmap(flag, null, new RectF(0, 0, s, s), p);
                break;
            }
            default: {
                shade(p, new LinearGradient(0, 0, 0, s, l.light, l.dark, TileMode.CLAMP));
                c.drawRect(0, 0, s, s, p);
                p.setShader(null);
                break;
            }
        }
    }

    /** Raised edge round the face, lit from the top-left. */
    private static void bevel(Canvas c, Paint p, float s, Look l) {
        p.setStyle(Paint.Style.STROKE);
        float w = s * 0.05f, round = s * 0.2f;
        RectF r = new RectF(w / 2, w / 2, s - w / 2, s - w / 2);
        p.setStrokeWidth(w);
        shade(p, new LinearGradient(0, 0, s, s, new int[]{lighten(l.edge, 0.55f), l.edge, darken(l.edge, 0.45f)}, null, TileMode.CLAMP));
        c.drawRoundRect(r, round, round, p);
        p.setShader(null);
        p.setStrokeWidth(s * 0.01f);
        p.setColor(0x33000000);
        float k = w * 1.05f;
        c.drawRoundRect(new RectF(k, k, s - k, s - k), round * 0.8f, round * 0.8f, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** The dots, in the die's own style. */
    private static void pips(Canvas c, Paint p, float s, Look l, int value) {
        if (value < 1 || value > 6) return;
        for (int[] at : FACE_PIPS[value]) {
            float cx = s * AT[at[0]], cy = s * AT[at[1]];
            float r = s * (value == 1 ? (l.motif == Motif.LUDOX ? 0.15f : 0.12f) : 0.085f);
            int col = value == 1 ? l.one : l.pip;
            switch (l.pipStyle) {
                case GEM: {
                    p.setColor(0x55000000);
                    c.drawCircle(cx, cy + r * 0.15f, r * 1.12f, p);
                    shade(p, new RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 1.4f, new int[]{lighten(col, 0.65f), col, darken(col, 0.45f)}, null, TileMode.CLAMP));
                    c.drawCircle(cx, cy, r, p);
                    p.setShader(null);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * 0.2f);
                    shade(p, new LinearGradient(cx - r, cy - r, cx + r, cy + r, 0xfffff1b0, GOLD_DARK, TileMode.CLAMP));
                    c.drawCircle(cx, cy, r * 1.05f, p);
                    p.setShader(null);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0xccffffff);
                    c.drawCircle(cx - r * 0.35f, cy - r * 0.38f, r * 0.24f, p);
                    break;
                }
                case GLOW: {
                    shade(p, new RadialGradient(cx, cy, r * 2.1f, (col & 0x00ffffff) | 0x99000000, col & 0x00ffffff, TileMode.CLAMP));
                    c.drawCircle(cx, cy, r * 2.1f, p);
                    shade(p, new RadialGradient(cx, cy, r, new int[]{0xffffffff, lighten(col, 0.4f), col}, new float[]{0f, 0.45f, 1f}, TileMode.CLAMP));
                    c.drawCircle(cx, cy, r, p);
                    p.setShader(null);
                    break;
                }
                case GOLD: {
                    p.setColor(0x66000000);
                    c.drawCircle(cx, cy + r * 0.18f, r * 1.08f, p);
                    shade(p, new RadialGradient(cx - r * 0.35f, cy - r * 0.45f, r * 1.5f, new int[]{lighten(col, 0.7f), col, darken(col, 0.55f)},
                            new float[]{0f, 0.45f, 1f}, TileMode.CLAMP));
                    c.drawCircle(cx, cy, r, p);
                    p.setShader(null);
                    p.setColor(0xbbffffff);
                    c.drawCircle(cx - r * 0.32f, cy - r * 0.38f, r * 0.22f, p);
                    break;
                }
                default: {
                    // Recessed: a dark rim on top, light catching the lower edge.
                    p.setColor(0x55ffffff);
                    c.drawCircle(cx, cy + r * 0.22f, r, p);
                    p.setColor(col);
                    c.drawCircle(cx, cy, r, p);
                    shade(p, new LinearGradient(cx, cy - r, cx, cy + r, 0x66000000, 0x00000000, TileMode.CLAMP));
                    c.drawCircle(cx, cy, r, p);
                    p.setShader(null);
                    break;
                }
            }
        }
    }

    // ================================================================= engravings

    /** Engraved (or inlaid) decoration behind the pips. */
    private static void motif(Canvas c, Paint p, float s, Look l, int value, Random rnd) {
        int col = l.engrave;
        switch (l.motif) {
            case FILIGREE:
                filigree(c, p, s, col);
                break;
            case CROWN:
                frameLine(c, p, s, col, 0.12f);
                engrave(c, p, crownPath(s * 0.5f, s * 0.5f, s * 0.36f), col, s * 0.018f, true);
                break;
            case CROWN_GEMS: {
                frameLine(c, p, s, col, 0.12f);
                engrave(c, p, crownPath(s * 0.5f, s * 0.5f, s * 0.3f), col, s * 0.014f, true);
                int[] gems = {0xff1a4fc4, RUBY, 0xff0f9a56, RUBY};
                float[][] at = {{0.16f, 0.16f}, {0.84f, 0.16f}, {0.84f, 0.84f}, {0.16f, 0.84f}};
                for (int k = 0; k < 4; k++) jewel(c, p, s * at[k][0], s * at[k][1], s * 0.04f, gems[k]);
                break;
            }
            case FACETS:
                // The gem material already has its facets; add a few glints.
                for (int k = 0; k < 4; k++) twinkle(c, p, s * (0.15f + rnd.nextFloat() * 0.7f), s * (0.15f + rnd.nextFloat() * 0.7f), s * 0.035f, 0xddffffff);
                break;
            case DRAGON:
                dragon(c, p, s, col);
                break;
            case PHOENIX:
                phoenix(c, p, s, col);
                break;
            case SNOWFLAKE:
                engrave(c, p, snowflake(s * 0.5f, s * 0.5f, s * 0.36f), (col & 0x00ffffff) | 0x99000000, s * 0.016f, false);
                engrave(c, p, snowflake(s * 0.17f, s * 0.17f, s * 0.08f), (col & 0x00ffffff) | 0xaa000000, s * 0.008f, false);
                engrave(c, p, snowflake(s * 0.83f, s * 0.83f, s * 0.08f), (col & 0x00ffffff) | 0xaa000000, s * 0.008f, false);
                break;
            case NEBULA_RING: {
                // A golden ring of runes: cosmic energy held in gold.
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.012f);
                p.setColor((col & 0x00ffffff) | 0xaa000000);
                c.drawCircle(s * 0.5f, s * 0.5f, s * 0.38f, p);
                c.drawCircle(s * 0.5f, s * 0.5f, s * 0.33f, p);
                for (int k = 0; k < 24; k++) {
                    double a = Math.PI * 2 * k / 24;
                    float r0 = s * 0.335f, r1 = s * (k % 3 == 0 ? 0.39f : 0.375f);
                    c.drawLine(s * 0.5f + (float) Math.cos(a) * r0, s * 0.5f + (float) Math.sin(a) * r0,
                            s * 0.5f + (float) Math.cos(a) * r1, s * 0.5f + (float) Math.sin(a) * r1, p);
                }
                p.setStyle(Paint.Style.FILL);
                frameLine(c, p, s, col, 0.1f);
                break;
            }
            case CIRCUIT:
                circuit(c, p, s, col, rnd);
                break;
            case HEARTS:
                for (int k = 0; k < 7; k++) {
                    float hx = s * (0.1f + rnd.nextFloat() * 0.8f), hy = s * (0.1f + rnd.nextFloat() * 0.8f), hr = s * (0.04f + rnd.nextFloat() * 0.05f);
                    p.setColor((col & 0x00ffffff) | 0x66000000);
                    c.drawPath(heart(hx, hy, hr), p);
                }
                engrave(c, p, heart(s * 0.5f, s * 0.52f, s * 0.3f), (col & 0x00ffffff) | 0x88000000, s * 0.014f, false);
                break;
            case SKULL:
                skull(c, p, s, col);
                break;
            case BOLT:
                for (int k = 0; k < 3; k++) {
                    float x = s * (0.2f + 0.3f * k);
                    engrave(c, p, bolt(x, s * 0.08f, s * 0.22f, s * 0.84f, rnd), (col & 0x00ffffff) | (k == 1 ? 0xcc000000 : 0x77000000), s * 0.016f, false);
                }
                break;
            case TIGER:
                tiger(c, p, s, col, rnd);
                break;
            case PEACOCK:
                peacock(c, p, s);
                break;
            case PHARAOH:
                pharaoh(c, p, s, col);
                break;
            case BUTTERFLY:
                butterfly(c, p, s * 0.5f, s * 0.5f, s * 0.34f, (col & 0x00ffffff) | 0x77000000, s * 0.012f);
                butterfly(c, p, s * 0.17f, s * 0.18f, s * 0.08f, 0x8840c4ff, s * 0.006f);
                butterfly(c, p, s * 0.83f, s * 0.82f, s * 0.08f, 0x88ffe066, s * 0.006f);
                break;
            case LUDOX:
                ludox(c, p, s, value);
                break;
            default:
                break;
        }
    }

    /**
     * An engraved line: cut dark into the surface with light catching its lower lip (on
     * metal), or simply inlaid (on glassy surfaces).
     */
    private static void engrave(Canvas c, Paint p, Path path, int col, float w, boolean emboss) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(w);
        if (emboss) {
            c.save();
            c.translate(w * 0.5f, w * 0.6f);
            p.setColor(0x55ffffff);
            c.drawPath(path, p);
            c.restore();
            c.save();
            c.translate(-w * 0.3f, -w * 0.3f);
            p.setColor(0x66000000);
            c.drawPath(path, p);
            c.restore();
        }
        p.setColor(col);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }

    private static void frameLine(Canvas c, Paint p, float s, int col, float inset) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.01f);
        p.setColor((col & 0x00ffffff) | 0x99000000);
        float in = s * inset;
        c.drawRoundRect(new RectF(in, in, s - in, s - in), s * 0.1f, s * 0.1f, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** Royal scrollwork: a double border and spirals in each corner. */
    private static void filigree(Canvas c, Paint p, float s, int col) {
        frameLine(c, p, s, col, 0.1f);
        frameLine(c, p, s, col, 0.135f);
        for (int k = 0; k < 4; k++) {
            c.save();
            c.rotate(90 * k, s / 2, s / 2);
            Path sp = new Path();
            float cx = s * 0.2f, cy = s * 0.2f;
            sp.moveTo(cx, cy);
            for (int i = 1; i <= 30; i++) {
                double a = i * 0.32;
                float rr = s * 0.004f * i;
                sp.lineTo(cx + (float) Math.cos(a) * rr, cy + (float) Math.sin(a) * rr);
            }
            engrave(c, p, sp, col, s * 0.012f, true);
            Path leaf = new Path();
            leaf.moveTo(s * 0.32f, s * 0.13f);
            leaf.quadTo(s * 0.42f, s * 0.06f, s * 0.5f, s * 0.13f);
            leaf.quadTo(s * 0.58f, s * 0.06f, s * 0.68f, s * 0.13f);
            engrave(c, p, leaf, col, s * 0.01f, true);
            c.restore();
        }
    }

    static Path crownPath(float cx, float cy, float r) {
        float w = r, top = cy - r * 0.6f, band = cy + r * 0.25f, bottom = cy + r * 0.55f;
        Path path = new Path();
        path.moveTo(cx - w, bottom);
        path.lineTo(cx - w, top + r * 0.15f);
        path.lineTo(cx - w * 0.5f, band - r * 0.25f);
        path.lineTo(cx, top);
        path.lineTo(cx + w * 0.5f, band - r * 0.25f);
        path.lineTo(cx + w, top + r * 0.15f);
        path.lineTo(cx + w, bottom);
        path.close();
        path.moveTo(cx - w, band);
        path.lineTo(cx + w, band);
        path.addCircle(cx, top - r * 0.12f, r * 0.08f, Path.Direction.CW);
        path.addCircle(cx - w, top + r * 0.02f, r * 0.07f, Path.Direction.CW);
        path.addCircle(cx + w, top + r * 0.02f, r * 0.07f, Path.Direction.CW);
        return path;
    }

    /** A golden dragon coiling round the face: a scaled body, a horned head, a tail. */
    private static void dragon(Canvas c, Paint p, float s, int col) {
        Path body = new Path();
        float m = s * 0.13f;
        body.moveTo(s * 0.22f, s - m);
        body.cubicTo(s * 0.05f, s * 0.7f, s * 0.05f, s * 0.3f, s * 0.25f, m);
        body.cubicTo(s * 0.45f, s * 0.02f, s * 0.6f, s * 0.2f, s * 0.78f, m);
        body.cubicTo(s * 0.98f, s * 0.08f, s * 0.95f, s * 0.45f, s - m, s * 0.6f);
        body.cubicTo(s * 0.85f, s * 0.8f, s * 0.7f, s * 0.95f, s * 0.5f, s - m * 0.7f);
        engrave(c, p, body, (col & 0x00ffffff) | 0x99000000, s * 0.05f, true);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.008f);
        p.setColor(0x88000000);
        android.graphics.PathMeasure pm = new android.graphics.PathMeasure(body, false);
        float len = pm.getLength();
        float[] pos = new float[2], tan = new float[2];
        for (float d = s * 0.03f; d < len; d += s * 0.04f) {
            pm.getPosTan(d, pos, tan);
            c.drawArc(new RectF(pos[0] - s * 0.018f, pos[1] - s * 0.018f, pos[0] + s * 0.018f, pos[1] + s * 0.018f),
                    (float) Math.toDegrees(Math.atan2(tan[1], tan[0])), 180, false, p);
        }
        p.setStyle(Paint.Style.FILL);
        // Head at the start of the body.
        float hx = s * 0.22f, hy = s - m;
        Path head = new Path();
        head.moveTo(hx - s * 0.08f, hy);
        head.lineTo(hx + s * 0.02f, hy - s * 0.07f);
        head.lineTo(hx + s * 0.1f, hy - s * 0.02f);
        head.lineTo(hx + s * 0.02f, hy + s * 0.04f);
        head.close();
        p.setColor(col);
        c.drawPath(head, p);
        Path horn = new Path();
        horn.moveTo(hx, hy - s * 0.06f);
        horn.lineTo(hx - s * 0.04f, hy - s * 0.13f);
        horn.lineTo(hx + s * 0.02f, hy - s * 0.07f);
        horn.close();
        c.drawPath(horn, p);
        p.setColor(0xffff4500);
        c.drawCircle(hx + s * 0.03f, hy - s * 0.025f, s * 0.012f, p);
    }

    /** Fire feathers fanning up from the bottom. */
    private static void phoenix(Canvas c, Paint p, float s, int col) {
        for (int k = -4; k <= 4; k++) {
            c.save();
            c.rotate(k * 14, s * 0.5f, s * 0.95f);
            Path f = new Path();
            f.moveTo(s * 0.5f, s * 0.95f);
            f.quadTo(s * 0.44f, s * 0.5f, s * 0.5f, s * 0.12f);
            f.quadTo(s * 0.56f, s * 0.5f, s * 0.5f, s * 0.95f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(k % 2 == 0 ? 0x44fff3c4 : 0x33ffb347);
            c.drawPath(f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(s * 0.006f);
            p.setColor((col & 0x00ffffff) | 0x88000000);
            c.drawLine(s * 0.5f, s * 0.92f, s * 0.5f, s * 0.18f, p);
            c.restore();
        }
        p.setStyle(Paint.Style.FILL);
    }

    private static Path snowflake(float cx, float cy, float r) {
        Path f = new Path();
        for (int k = 0; k < 6; k++) {
            double a = Math.PI / 3 * k;
            float ex = cx + (float) Math.cos(a) * r, ey = cy + (float) Math.sin(a) * r;
            f.moveTo(cx, cy);
            f.lineTo(ex, ey);
            for (float t : new float[]{0.45f, 0.7f}) {
                float bx = cx + (float) Math.cos(a) * r * t, by = cy + (float) Math.sin(a) * r * t;
                float bl = r * (t == 0.45f ? 0.28f : 0.2f);
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    double b = a + sgn * Math.PI / 4;
                    f.moveTo(bx, by);
                    f.lineTo(bx + (float) Math.cos(b) * bl, by + (float) Math.sin(b) * bl);
                }
            }
        }
        return f;
    }

    private static void circuit(Canvas c, Paint p, float s, int col, Random rnd) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.01f);
        for (int k = 0; k < 7; k++) {
            float x = s * (0.15f + rnd.nextFloat() * 0.7f), y = s * (0.15f + rnd.nextFloat() * 0.7f);
            Path t = new Path();
            t.moveTo(x, y);
            float nx = x + (rnd.nextBoolean() ? 1 : -1) * s * 0.15f;
            t.lineTo(nx, y);
            t.lineTo(nx + s * 0.06f, y + s * 0.06f);
            t.lineTo(nx + s * 0.06f, y + s * 0.16f);
            p.setColor((col & 0x00ffffff) | 0x77000000);
            c.drawPath(t, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(col);
            c.drawCircle(x, y, s * 0.014f, p);
            p.setStyle(Paint.Style.STROKE);
        }
        p.setStyle(Paint.Style.FILL);
    }

    public static Path heart(float cx, float cy, float r) {
        Path h = new Path();
        h.moveTo(cx, cy + r * 0.9f);
        h.cubicTo(cx - r * 1.35f, cy + r * 0.05f, cx - r * 0.95f, cy - r * 1.05f, cx, cy - r * 0.42f);
        h.cubicTo(cx + r * 0.95f, cy - r * 1.05f, cx + r * 1.35f, cy + r * 0.05f, cx, cy + r * 0.9f);
        h.close();
        return h;
    }

    private static void skull(Canvas c, Paint p, float s, int col) {
        float cx = s * 0.5f, cy = s * 0.46f, r = s * 0.27f;
        Path sk = new Path();
        sk.addCircle(cx, cy, r, Path.Direction.CW);
        sk.addRoundRect(new RectF(cx - r * 0.55f, cy + r * 0.6f, cx + r * 0.55f, cy + r * 1.15f), r * 0.15f, r * 0.15f, Path.Direction.CW);
        engrave(c, p, sk, (col & 0x00ffffff) | 0x77000000, s * 0.014f, true);
        p.setColor(0x66000000);
        c.drawOval(new RectF(cx - r * 0.62f, cy - r * 0.15f, cx - r * 0.12f, cy + r * 0.35f), p);
        c.drawOval(new RectF(cx + r * 0.12f, cy - r * 0.15f, cx + r * 0.62f, cy + r * 0.35f), p);
        // Crossed bones behind.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(s * 0.03f);
        p.setColor((col & 0x00ffffff) | 0x55000000);
        c.drawLine(s * 0.12f, s * 0.12f, s * 0.88f, s * 0.88f, p);
        c.drawLine(s * 0.88f, s * 0.12f, s * 0.12f, s * 0.88f, p);
        p.setStyle(Paint.Style.FILL);
    }

    private static Path bolt(float x, float top, float w, float h, Random rnd) {
        Path b = new Path();
        b.moveTo(x, top);
        float y = top;
        float cx = x;
        for (int k = 0; k < 4; k++) {
            y += h / 4f;
            cx += (k % 2 == 0 ? -1 : 1) * w * (0.3f + rnd.nextFloat() * 0.3f);
            b.lineTo(cx, y);
        }
        return b;
    }

    /** Tiger stripes: tapered dark strokes reaching in from the edges. */
    private static void tiger(Canvas c, Paint p, float s, int col, Random rnd) {
        p.setStyle(Paint.Style.FILL);
        p.setColor((col & 0x00ffffff) | 0xcc000000);
        for (int side = 0; side < 4; side++) {
            c.save();
            c.rotate(90 * side, s / 2, s / 2);
            for (int k = 0; k < 3; k++) {
                float y = s * (0.2f + k * 0.28f + rnd.nextFloat() * 0.05f);
                float len = s * (0.18f + rnd.nextFloat() * 0.12f);
                Path st = new Path();
                st.moveTo(0, y - s * 0.035f);
                st.quadTo(len * 0.6f, y - s * 0.05f, len, y + s * 0.02f);
                st.quadTo(len * 0.55f, y + s * 0.0f, 0, y + s * 0.035f);
                st.close();
                c.drawPath(st, p);
            }
            c.restore();
        }
    }

    /** Peacock feather eyes in the corners with fine barbs. */
    private static void peacock(Canvas c, Paint p, float s) {
        float[][] at = {{0.18f, 0.18f, 45}, {0.82f, 0.18f, 135}, {0.82f, 0.82f, 225}, {0.18f, 0.82f, 315}};
        for (float[] a : at) {
            float x = s * a[0], y = s * a[1];
            c.save();
            c.rotate(a[2] - 90, x, y);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(s * 0.004f);
            p.setColor(0x66ffcf40);
            for (int k = -5; k <= 5; k++) c.drawLine(x, y, x + k * s * 0.02f, y + s * 0.16f, p);
            p.setStyle(Paint.Style.FILL);
            int[] rings = {0xffd4a017, 0xff1fd0c0, 0xff0b4f9a, 0xff081a3a};
            float[] rr = {0.1f, 0.075f, 0.05f, 0.028f};
            for (int k = 0; k < 4; k++) {
                p.setColor(rings[k]);
                c.drawOval(new RectF(x - s * rr[k] * 0.75f, y - s * rr[k], x + s * rr[k] * 0.75f, y + s * rr[k]), p);
            }
            c.restore();
        }
    }

    /** Egyptian gold: a border band of symbols, lapis inlay and an ankh. */
    private static void pharaoh(Canvas c, Paint p, float s, int col) {
        p.setStyle(Paint.Style.FILL);
        p.setColor((col & 0x00ffffff) | 0xcc000000);
        float b = s * 0.09f;
        c.drawRect(0, 0, s, b, p);
        c.drawRect(0, s - b, s, s, p);
        p.setColor(GOLD);
        for (int k = 0; k < 7; k++) {
            float x = s * (0.08f + k * 0.14f);
            if (k % 2 == 0) {
                Path tri = new Path();
                tri.moveTo(x - b * 0.3f, b * 0.8f);
                tri.lineTo(x, b * 0.2f);
                tri.lineTo(x + b * 0.3f, b * 0.8f);
                tri.close();
                c.drawPath(tri, p);
                c.drawCircle(x, s - b * 0.5f, b * 0.22f, p);
            } else {
                c.drawRect(x - b * 0.08f, b * 0.2f, x + b * 0.08f, b * 0.8f, p);
                c.drawRect(x - b * 0.25f, s - b * 0.6f, x + b * 0.25f, s - b * 0.4f, p);
            }
        }
        // Ankh, faint, in the middle.
        Path ankh = new Path();
        ankh.addOval(new RectF(s * 0.42f, s * 0.2f, s * 0.58f, s * 0.42f), Path.Direction.CW);
        ankh.moveTo(s * 0.5f, s * 0.42f);
        ankh.lineTo(s * 0.5f, s * 0.8f);
        ankh.moveTo(s * 0.36f, s * 0.5f);
        ankh.lineTo(s * 0.64f, s * 0.5f);
        engrave(c, p, ankh, (col & 0x00ffffff) | 0x66000000, s * 0.03f, true);
    }

    private static void butterfly(Canvas c, Paint p, float cx, float cy, float r, int col, float w) {
        Path b = new Path();
        b.addOval(new RectF(cx - r, cy - r * 0.85f, cx - r * 0.05f, cy + r * 0.05f), Path.Direction.CW);
        b.addOval(new RectF(cx + r * 0.05f, cy - r * 0.85f, cx + r, cy + r * 0.05f), Path.Direction.CW);
        b.addOval(new RectF(cx - r * 0.75f, cy, cx - r * 0.05f, cy + r * 0.7f), Path.Direction.CW);
        b.addOval(new RectF(cx + r * 0.05f, cy, cx + r * 0.75f, cy + r * 0.7f), Path.Direction.CW);
        p.setStyle(Paint.Style.FILL);
        p.setColor((col & 0x00ffffff) | 0x22000000);
        c.drawPath(b, p);
        engrave(c, p, b, col, w, false);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * 1.6f);
        p.setColor(col);
        c.drawLine(cx, cy - r * 0.6f, cx, cy + r * 0.6f, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** The LudoX signature: gold filigree, a crown, purple gems round the edge and the LX mark. */
    private static void ludox(Canvas c, Paint p, float s, int value) {
        filigree(c, p, s, GOLD);
        for (int k = 0; k < 8; k++) {
            double a = Math.PI / 4 * k;
            jewel(c, p, s * 0.5f + (float) Math.cos(a) * s * 0.4f, s * 0.5f + (float) Math.sin(a) * s * 0.4f, s * 0.025f, PURPLE);
        }
        if (value == 1) {
            // The 1 is the signature face: a crown over the LX mark round the pip.
            engrave(c, p, crownPath(s * 0.5f, s * 0.2f, s * 0.12f), GOLD, s * 0.012f, true);
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setTextAlign(Paint.Align.CENTER);
            t.setTextSize(s * 0.13f);
            t.setFakeBoldText(true);
            t.setColor(GOLD);
            t.setShadowLayer(s * 0.01f, 0, s * 0.006f, 0x99000000);
            c.drawText("L U D O X", s * 0.5f, s * 0.86f, t);
        }
    }

    private static void twinkle(Canvas c, Paint p, float cx, float cy, float r, int col) {
        Path t = new Path();
        t.moveTo(cx, cy - r);
        t.quadTo(cx, cy, cx + r, cy);
        t.quadTo(cx, cy, cx, cy + r);
        t.quadTo(cx, cy, cx - r, cy);
        t.quadTo(cx, cy, cx, cy - r);
        t.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(col);
        c.drawPath(t, p);
    }

    private static void jewel(Canvas c, Paint p, float cx, float cy, float r, int col) {
        Path j = new Path();
        j.moveTo(cx, cy - r);
        j.lineTo(cx + r, cy);
        j.lineTo(cx, cy + r);
        j.lineTo(cx - r, cy);
        j.close();
        p.setStyle(Paint.Style.FILL);
        shade(p, new LinearGradient(cx - r, cy - r, cx + r, cy + r, lighten(col, 0.5f), darken(col, 0.3f), TileMode.CLAMP));
        c.drawPath(j, p);
        p.setShader(null);
        p.setColor(0xaaffffff);
        c.drawCircle(cx - r * 0.25f, cy - r * 0.25f, r * 0.22f, p);
    }

    // ================================================================= the cube

    // Face normal, u axis, v axis (v points "up" on the face), and its value: opposite faces sum to 7.
    private static final float[][] N = {{0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
    private static final float[][] U = {{1, 0, 0}, {-1, 0, 0}, {0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {1, 0, 0}};
    private static final float[][] V = {{0, 1, 0}, {0, 1, 0}, {0, 1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
    private static final int[] VAL = {1, 6, 2, 5, 3, 4};
    private static final float[] LIGHT = norm(new float[]{-0.4f, 0.7f, 0.6f});
    private static final float[] HALF = norm(new float[]{-0.4f, 0.7f, 1.6f});

    /**
     * Draw the die as a cube rotated by `m` (3x3, row-major), centred at cx, cy with half-size
     * `half` pixels. `lit` false dims it (not this player's turn); `sheen` 0..1 moves a light
     * sweep across it (negative = none).
     */
    public static void drawCube(Canvas c, Paint paint, Path path, float cx, float cy, float half, float[] m, boolean lit, Look l, float sheen) {
        float persp = 9f;
        int[] order = new int[6];
        float[] depth = new float[6];
        int count = 0;
        for (int f = 0; f < 6; f++) {
            float[] n = apply(m, N[f]);
            if (n[2] <= 0.02f) continue;
            order[count] = f;
            depth[count] = n[2];
            count++;
        }
        for (int i = 1; i < count; i++) {
            for (int j = i; j > 0 && depth[j - 1] > depth[j]; j--) {
                float td = depth[j];
                depth[j] = depth[j - 1];
                depth[j - 1] = td;
                int to = order[j];
                order[j] = order[j - 1];
                order[j - 1] = to;
            }
        }
        int size = half * 2 < 110 ? 128 : half * 2 < 240 ? 256 : 512;
        float[] p = new float[2];
        float[] dst = new float[8];
        Matrix mx = new Matrix();
        float[][] corners = {{-1, 1}, {1, 1}, {1, -1}, {-1, -1}};
        // The rounded body first: each visible face's outline with a fat round stroke in the
        // edge colour, so the edges between faces read as bevelled, rounded corners.
        for (int k = 0; k < count; k++) {
            int f = order[k];
            float[] n = apply(m, N[f]), u = apply(m, U[f]), v = apply(m, V[f]);
            float lightK = 0.45f + 0.55f * Math.max(0f, dot(n, LIGHT));
            path.reset();
            for (int i = 0; i < 4; i++) {
                project(n, u, v, corners[i][0] * 0.9f, corners[i][1] * 0.9f, 0.995f, cx, cy, half, persp, p);
                if (i == 0) path.moveTo(p[0], p[1]);
                else path.lineTo(p[0], p[1]);
            }
            path.close();
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL_AND_STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(half * 0.26f);
            paint.setColor(dim(scale(l.edge, lightK), lit));
            c.drawPath(path, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        for (int k = 0; k < count; k++) {
            int f = order[k];
            float[] n = apply(m, N[f]), u = apply(m, U[f]), v = apply(m, V[f]);
            for (int i = 0; i < 4; i++) {
                project(n, u, v, corners[i][0], corners[i][1], 1f, cx, cy, half, persp, p);
                dst[i * 2] = p[0];
                dst[i * 2 + 1] = p[1];
            }
            Bitmap face = face(l, VAL[f], size);
            float fs = face.getWidth();
            mx.setPolyToPoly(new float[]{0, 0, fs, 0, fs, fs, 0, fs}, 0, dst, 0, 4);
            paint.setColor(Color.BLACK);
            paint.setFilterBitmap(true);
            c.save();
            c.concat(mx);
            c.drawBitmap(face, 0, 0, paint);
            // Light and shade: faces turned from the light darken; the lit side gets a highlight.
            float diffuse = Math.max(0f, dot(n, LIGHT));
            float dark = (1f - (0.5f + 0.5f * diffuse)) * 0.85f + (lit ? 0f : 0.35f);
            float rr = fs * 0.2f;
            if (dark > 0.01f) {
                paint.setColor(Color.argb((int) (255 * Math.min(0.8f, dark)), 8, 4, 20));
                c.drawRoundRect(0, 0, fs, fs, rr, rr, paint);
            }
            float spec = (float) Math.pow(Math.max(0f, dot(n, HALF)), 10) * l.shine;
            if (spec > 0.02f) {
                shade(paint, new LinearGradient(0, 0, fs, fs, Color.argb((int) (230 * Math.min(1f, spec)), 255, 255, 255), 0x00ffffff, TileMode.CLAMP));
                c.drawRoundRect(0, 0, fs, fs, rr, rr, paint);
                paint.setShader(null);
            }
            if (sheen >= 0 && l.sheen) {
                // A band of light sliding across the die (legendary dice keep shimmering).
                float x = (sheen * 2.6f - 0.8f) * fs;
                shade(paint, new LinearGradient(x - fs * 0.25f, 0, x + fs * 0.25f, fs,
                        new int[]{0x00ffffff, 0x55ffffff, 0x00ffffff}, new float[]{0f, 0.5f, 1f}, TileMode.CLAMP));
                c.drawRoundRect(0, 0, fs, fs, rr, rr, paint);
                paint.setShader(null);
            }
            c.restore();
        }
    }

    /** The resting die seen face-on (in the dice box): shadow, bevelled face, shading. */
    public static void drawFlat(Canvas c, Paint paint, float x, float y, float s, int value, boolean lit, Look l) {
        float r = s * 0.22f;
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x55000000);
        c.drawRoundRect(x + s * 0.03f, y + s * 0.08f, x + s * 1.03f, y + s * 1.08f, r, r, paint);
        paint.setColor(dim(darken(l.edge, 0.25f), lit));
        c.drawRoundRect(x, y + s * 0.04f, x + s, y + s * 1.04f, r, r, paint);
        Bitmap face = face(l, value, s < 110 ? 128 : s < 240 ? 256 : 512);
        paint.setColor(Color.BLACK);
        paint.setFilterBitmap(true);
        c.drawBitmap(face, null, new RectF(x, y, x + s, y + s), paint);
        if (!lit) {
            paint.setColor(0x66080414);
            c.drawRoundRect(x, y, x + s, y + s, r, r, paint);
        }
    }

    /** A soft contact shadow on the ground under a die. */
    public static void contactShadow(Canvas c, Paint paint, float cx, float groundY, float w, float strength) {
        paint.setStyle(Paint.Style.FILL);
        shade(paint, new RadialGradient(cx, groundY, w, Color.argb((int) (150 * strength), 0, 0, 0), 0x00000000, TileMode.CLAMP));
        c.save();
        c.scale(1f, 0.28f, cx, groundY);
        c.drawCircle(cx, groundY, w, paint);
        c.restore();
        paint.setShader(null);
    }

    /** The six faces as a 3x2 atlas for the 3D board's die (row-major, values 1..6). */
    public static Bitmap atlas(Look l, int faceSize) {
        Bitmap bmp = Bitmap.createBitmap(faceSize * 3, faceSize * 2, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        for (int v = 0; v < 6; v++) {
            float x = (v % 3) * faceSize, y = (v / 3) * faceSize;
            p.setColor(darken(l.edge, 0.2f));
            c.drawRect(x, y, x + faceSize, y + faceSize, p);
            p.setColor(Color.BLACK);
            c.drawBitmap(face(l, v + 1, faceSize), x, y, p);
        }
        return bmp;
    }

    /** A still picture of the die at a flattering three-quarter angle (shop thumbnails). */
    public static Bitmap thumbnail(Look l, int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Path path = new Path();
        float half = size * 0.27f;
        contactShadow(c, p, size / 2f, size * 0.83f, size * 0.36f, 1f);
        float[] m = mul(rot(new float[]{1, 0, 0}, 24f), mul(rot(new float[]{0, 1, 0}, -32f), face(5)));
        drawCube(c, p, path, size / 2f, size * 0.47f, half, m, true, l, -1f);
        return bmp;
    }

    // ================================================================= math and colour helpers

    /** Rotation that turns the face with `value` toward the viewer (+z). */
    public static float[] face(int value) {
        switch (value) {
            case 6: return rot(new float[]{0, 1, 0}, 180f);
            case 2: return rot(new float[]{0, 1, 0}, -90f);
            case 5: return rot(new float[]{0, 1, 0}, 90f);
            case 3: return rot(new float[]{1, 0, 0}, 90f);
            case 4: return rot(new float[]{1, 0, 0}, -90f);
            default: return rot(new float[]{0, 0, 1}, 0f);
        }
    }

    private static void project(float[] n, float[] u, float[] v, float a, float b, float outward, float cx, float cy,
                                float half, float persp, float[] out) {
        float x = n[0] * outward + u[0] * a + v[0] * b;
        float y = n[1] * outward + u[1] * a + v[1] * b;
        float z = n[2] * outward + u[2] * a + v[2] * b;
        float k = persp / (persp - z);
        out[0] = cx + x * half * k;
        out[1] = cy - y * half * k;
    }

    public static float[] rot(float[] axis, float deg) {
        float[] a = norm(axis);
        double r = Math.toRadians(deg);
        float c = (float) Math.cos(r), s = (float) Math.sin(r), t = 1 - c;
        float x = a[0], y = a[1], z = a[2];
        return new float[]{
                t * x * x + c, t * x * y - s * z, t * x * z + s * y,
                t * x * y + s * z, t * y * y + c, t * y * z - s * x,
                t * x * z - s * y, t * y * z + s * x, t * z * z + c,
        };
    }

    public static float[] mul(float[] a, float[] b) {
        float[] o = new float[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                o[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
            }
        }
        return o;
    }

    static float[] apply(float[] m, float[] v) {
        return new float[]{
                m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
                m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
                m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
        };
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float[] norm(float[] v) {
        float l = (float) Math.sqrt(dot(v, v));
        return new float[]{v[0] / l, v[1] / l, v[2] / l};
    }

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

    private static int scale(int c, float k) {
        return Color.rgb(Math.min(255, (int) (Color.red(c) * k)), Math.min(255, (int) (Color.green(c) * k)), Math.min(255, (int) (Color.blue(c) * k)));
    }

    private static int dim(int c, boolean lit) {
        return lit ? c : blend(c, 0xff5f5b75, 0.4f);
    }

    static int blend(int a, int b, float t) {
        return Color.argb(
                (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
