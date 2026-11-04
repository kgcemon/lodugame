package com.ludox.game.gl;

import android.opengl.GLES20;
import android.opengl.Matrix;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The 3D gifts (ids in shared/playergifts.ts → GIFT_MODELS). Each is built from a few shared meshes
 * with its own materials, so 22 models cost a handful of vertex buffers. A model fits a sphere of
 * radius about 1 and is drawn with parts that can move: wings flap, wheels turn, lids open.
 * One instance per GL context; use it on that context's thread.
 */
final class GiftModels {
    /** How shiny a part is (0 painted, 1 mirror) and how much it glows on its own. */
    private static final float[] GOLD = {0.92f, 0.08f}, GEM = {0.75f, 0.28f}, SHINY = {0.45f, 0.02f}, SOFT = {0.08f, 0f}, GLOW = {0.1f, 0.9f}, GLASS = {0.85f, 0.05f};
    private static final int FLAP_L = 1, FLAP_R = 2, WHEEL = 3, LID = 4, SWAY = 5;

    private static final class Part {
        Mesh mesh;
        float r, g, b, spec, glow;
        float tx, ty, tz, rx, ry, rz, sx, sy, sz;
        int anim;
    }

    private final class Model {
        final List<Part> parts = new ArrayList<>();

        /** Add a part: uniform size. */
        Model a(Mesh m, float[] mat, int rgb, float x, float y, float z, float s) {
            return b(m, mat, rgb, x, y, z, s, s, s);
        }

        /** Add a part: size per axis. */
        Model b(Mesh m, float[] mat, int rgb, float x, float y, float z, float sx, float sy, float sz) {
            Part p = new Part();
            p.mesh = m;
            p.r = ((rgb >> 16) & 255) / 255f;
            p.g = ((rgb >> 8) & 255) / 255f;
            p.b = (rgb & 255) / 255f;
            p.spec = mat[0];
            p.glow = mat[1];
            p.tx = x;
            p.ty = y;
            p.tz = z;
            p.sx = sx;
            p.sy = sy;
            p.sz = sz;
            parts.add(p);
            return this;
        }

        /** Turn the part just added (degrees). */
        Model rot(float x, float y, float z) {
            Part p = parts.get(parts.size() - 1);
            p.rx = x;
            p.ry = y;
            p.rz = z;
            return this;
        }

        /** The part just added moves. */
        Model anim(int kind) {
            parts.get(parts.size() - 1).anim = kind;
            return this;
        }
    }

    private final Mesh sphere, cyl, cone, ring, box, arch, coin, gem, heart, star, petal, wing, flame, triangle, goblet;
    private final Map<String, Model> models = new HashMap<>();
    private final float[] tmp = new float[16], mvp = new float[16], m1 = new float[16];

    GiftModels() {
        sphere = Mesh.lathe(circle(0, 1, 12, -90, 90), 20);
        cyl = Mesh.lathe(new float[][]{{0, -1}, {1, -1}, {1, -0.96f}, {1, 0.96f}, {1, 1}, {0, 1}}, 20);
        cone = Mesh.lathe(new float[][]{{0, -1}, {1, -1}, {0.98f, -0.96f}, {0, 1}}, 16);
        ring = Mesh.lathe(circle(0.78f, 0.22f, 10, -180, 180), 26);
        box = Mesh.box(1, 1, 1, false);
        arch = Mesh.arch(18);
        coin = Mesh.coin(18);
        gem = Mesh.gem(8);
        goblet = Mesh.lathe(new float[][]{{0, -1}, {0.55f, -1}, {0.55f, -0.9f}, {0.16f, -0.78f}, {0.12f, -0.45f}, {0.22f, -0.3f}, {0.6f, -0.05f}, {0.8f, 0.45f}, {0.85f, 1}, {0.72f, 1}, {0.68f, 0.5f}, {0, 0.1f}}, 22);
        heart = Mesh.extrude(heartOutline(), 0.42f);
        star = Mesh.extrude(starOutline(), 0.25f);
        petal = Mesh.extrude(ellipse(1f, 0.62f, 14), 0.22f);
        wing = Mesh.extrude(wingOutline(), 0.08f);
        flame = Mesh.extrude(flameOutline(), 0.18f);
        triangle = Mesh.extrude(new float[][]{{-1, -0.8f}, {1, -0.8f}, {0, 1}}, 0.08f);
        build();
    }

    // ================================================================= outlines

    private static float[][] circle(float cx, float r, int n, int fromDeg, int toDeg) {
        float[][] p = new float[n + 1][2];
        for (int i = 0; i <= n; i++) {
            double a = Math.toRadians(fromDeg + (toDeg - fromDeg) * i / (double) n);
            p[i][0] = Math.max(0, cx + (float) (r * Math.cos(a)));
            p[i][1] = (float) (r * Math.sin(a));
        }
        return p;
    }

    private static float[][] ellipse(float a, float b, int n) {
        float[][] p = new float[n][2];
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n;
            p[i][0] = (float) (Math.cos(t) * b);
            p[i][1] = (float) (Math.sin(t) * a);
        }
        return p;
    }

    private static float[][] heartOutline() {
        int n = 36;
        float[][] p = new float[n][2];
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n;
            double x = 16 * Math.pow(Math.sin(t), 3);
            double y = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t);
            // Counter-clockwise, centred.
            p[n - 1 - i][0] = (float) (x / 17);
            p[n - 1 - i][1] = (float) ((y + 2) / 17);
        }
        return p;
    }

    private static float[][] starOutline() {
        float[][] p = new float[10][2];
        for (int i = 0; i < 10; i++) {
            double a = Math.PI / 2 + i * Math.PI / 5;
            float r = i % 2 == 0 ? 1f : 0.45f;
            p[i][0] = (float) (Math.cos(a) * r);
            p[i][1] = (float) (Math.sin(a) * r);
        }
        return p;
    }

    /** A wing from its root at the origin, sweeping out along +x. */
    private static float[][] wingOutline() {
        return new float[][]{{0.05f, -0.05f}, {0.45f, -0.3f}, {0.8f, -0.32f}, {1f, -0.15f}, {1.05f, 0.12f}, {0.85f, 0.42f}, {0.5f, 0.5f}, {0.2f, 0.35f}, {0f, 0.1f}};
    }

    private static float[][] flameOutline() {
        return new float[][]{{0, -1}, {0.45f, -0.75f}, {0.62f, -0.3f}, {0.42f, 0.2f}, {0.14f, 0.6f}, {0, 1}, {-0.14f, 0.6f}, {-0.42f, 0.2f}, {-0.62f, -0.3f}, {-0.45f, -0.75f}};
    }

    // ================================================================= the gifts

    private void build() {
        models.put("rose", rose(0xf7c64a, GOLD, 0x7c8f2e));
        models.put("bear", bear(0xa8693a, SOFT, 0xe8c79a, 0xe0364f));
        models.put("letter", letter());
        models.put("chocolates", chocolates());
        models.put("bouquet", bouquet(0xf7a8c8, 0xe53950, 0xfbbf24, SHINY));
        models.put("heart_gold", heartOf(0xf5bf35, GOLD, 0xffe58a));
        models.put("cat", cat());
        models.put("cake", cake());
        models.put("balloon", balloons());
        models.put("trophy", trophy());
        models.put("heart_diamond", heartOf(0xf3a0d8, GEM, 0xbfe9ff).a(gem, GEM, 0xdff6ff, 0, 0.3f, 0.5f, 0.28f).rot(90, 0, 0));
        models.put("royal_bouquet", royalBouquet());
        models.put("butterfly", butterfly());
        models.put("balloon_heart", loveBalloon());
        models.put("crown", crown());
        models.put("bear_diamond", diamondBear());
        models.put("peacock", peacock());
        models.put("phoenix", phoenix());
        models.put("dragon", dragon());
        models.put("chest", chest());
        models.put("car", car());
        models.put("palace", palace());
    }

    private Model rose(int petalRgb, float[] mat, int leaf) {
        Model m = new Model();
        m.b(cyl, SOFT, 0x3f7a35, 0, -0.45f, 0, 0.035f, 0.55f, 0.035f);
        m.b(petal, SOFT, leaf, 0.2f, -0.45f, 0, 0.22f, 0.2f, 0.1f).rot(0, 0, -70);
        m.b(petal, SOFT, leaf, -0.2f, -0.65f, 0, 0.2f, 0.18f, 0.1f).rot(0, 0, 70);
        roseHead(m, 0, 0.3f, 0, 0.62f, petalRgb, mat);
        return m;
    }

    /** Overlapping petals round a closed bud. */
    private void roseHead(Model m, float x, float y, float z, float s, int rgb, float[] mat) {
        int dark = shade(rgb, 0.78f);
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3;
            m.b(sphere, mat, i % 2 == 0 ? rgb : dark, x + (float) Math.cos(a) * s * 0.55f, y - s * 0.1f, z + (float) Math.sin(a) * s * 0.55f, s * 0.52f, s * 0.45f, s * 0.52f);
        }
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2 + 0.6;
            m.b(sphere, mat, i % 2 == 0 ? dark : rgb, x + (float) Math.cos(a) * s * 0.28f, y + s * 0.18f, z + (float) Math.sin(a) * s * 0.28f, s * 0.38f, s * 0.42f, s * 0.38f);
        }
        m.a(sphere, mat, shade(rgb, 0.62f), x, y + s * 0.34f, z, s * 0.26f);
    }

    private static int shade(int rgb, float k) {
        int r = (int) (((rgb >> 16) & 255) * k), g = (int) (((rgb >> 8) & 255) * k), b = (int) ((rgb & 255) * k);
        return (r << 16) | (g << 8) | b;
    }

    private Model bear(int fur, float[] mat, int light, int heartRgb) {
        Model m = new Model();
        m.b(sphere, mat, fur, 0, -0.32f, 0, 0.55f, 0.6f, 0.48f);
        m.b(sphere, mat, light, 0, -0.32f, 0.3f, 0.33f, 0.38f, 0.22f);
        m.a(sphere, mat, fur, 0, 0.42f, 0, 0.48f);
        m.a(sphere, mat, fur, 0.38f, 0.8f, 0, 0.19f);
        m.a(sphere, mat, fur, -0.38f, 0.8f, 0, 0.19f);
        m.a(sphere, mat, light, 0.38f, 0.8f, 0.1f, 0.1f);
        m.a(sphere, mat, light, -0.38f, 0.8f, 0.1f, 0.1f);
        m.b(sphere, mat, light, 0, 0.3f, 0.38f, 0.22f, 0.17f, 0.16f);
        m.a(sphere, SHINY, 0x241510, 0, 0.36f, 0.52f, 0.07f);
        m.a(sphere, GLASS, 0x151015, 0.17f, 0.52f, 0.42f, 0.058f);
        m.a(sphere, GLASS, 0x151015, -0.17f, 0.52f, 0.42f, 0.058f);
        m.b(sphere, mat, fur, 0.56f, -0.12f, 0.14f, 0.17f, 0.3f, 0.17f).rot(0, 0, -35);
        m.b(sphere, mat, fur, -0.56f, -0.12f, 0.14f, 0.17f, 0.3f, 0.17f).rot(0, 0, 35);
        m.b(sphere, mat, fur, 0.32f, -0.86f, 0.22f, 0.21f, 0.17f, 0.27f);
        m.b(sphere, mat, fur, -0.32f, -0.86f, 0.22f, 0.21f, 0.17f, 0.27f);
        m.b(heart, SHINY, heartRgb, 0, -0.26f, 0.5f, 0.2f, 0.2f, 0.16f);
        return m;
    }

    private Model letter() {
        Model m = new Model();
        m.b(box, SOFT, 0xfff4e4, 0, 0, 0, 0.95f, 0.62f, 0.07f);
        m.b(triangle, SOFT, 0xf7dcc3, 0, 0.18f, 0.08f, 0.95f, -0.52f, 0.2f);
        m.b(triangle, SOFT, 0xfbe7d4, -0.42f, -0.2f, 0.09f, 0.55f, 0.48f, 0.15f);
        m.b(triangle, SOFT, 0xfbe7d4, 0.42f, -0.2f, 0.09f, 0.55f, 0.48f, 0.15f);
        m.b(heart, SHINY, 0xdc2645, 0, 0.0f, 0.16f, 0.25f, 0.25f, 0.2f);
        return m;
    }

    private Model chocolates() {
        Model m = new Model();
        m.b(box, SHINY, 0xb01e3f, 0, -0.32f, 0, 0.95f, 0.3f, 0.75f);
        m.b(box, SOFT, 0x2b1208, 0, -0.03f, 0, 0.85f, 0.02f, 0.66f);
        int[] c = {0x4a2412, 0x6b3a1e, 0x3a1a0c, 0xe7d3b3};
        for (int i = 0; i < 6; i++) {
            float x = -0.55f + (i % 3) * 0.55f, z = i < 3 ? -0.3f : 0.3f;
            m.b(i % 2 == 0 ? sphere : heart, SHINY, c[i % 4], x, 0.08f, z, 0.22f, 0.15f, 0.22f);
        }
        // The lid, tied with a gold ribbon, lifts when it arrives.
        m.b(box, SHINY, 0xd02448, 0, 0.1f, -0.75f, 0.98f, 0.1f, 0.78f).anim(LID);
        m.b(box, GOLD, 0xf6c33d, 0, 0.11f, -0.75f, 0.12f, 0.115f, 0.79f).anim(LID);
        m.b(box, GOLD, 0xf6c33d, 0, 0.11f, -0.75f, 0.99f, 0.115f, 0.1f).anim(LID);
        m.b(heart, GOLD, 0xffe58a, 0, 0.28f, -0.75f, 0.2f, 0.2f, 0.16f).rot(-70, 0, 0).anim(LID);
        return m;
    }

    private Model bouquet(int wrap, int rose, int ribbon, float[] wrapMat) {
        Model m = new Model();
        m.b(cone, wrapMat, wrap, 0, -0.35f, 0, 0.55f, -0.75f, 0.55f);
        int[][] at = {{0, 75, 18}, {-38, 48, 12}, {38, 48, 12}, {-20, 42, 42}, {22, 40, 42}, {-62, 18, 2}, {62, 18, 2}};
        for (int[] p : at) {
            m.b(petal, SOFT, 0x3f8a3a, p[0] / 100f * 1.25f, p[1] / 100f - 0.2f, p[2] / 100f - 0.2f, 0.2f, 0.3f, 0.1f).rot(0, 0, -p[0] * 0.9f);
        }
        for (int[] p : at) roseHead(m, p[0] / 100f, p[1] / 100f + 0.02f, p[2] / 100f, 0.3f, rose, SHINY);
        m.b(ring, GOLD, ribbon, 0, -0.42f, 0, 0.24f, 0.12f, 0.24f);
        m.b(heart, GOLD, ribbon, 0, -0.5f, 0.28f, 0.14f, 0.14f, 0.1f);
        return m;
    }

    private Model heartOf(int rgb, float[] mat, int small) {
        Model m = new Model();
        m.b(heart, mat, rgb, 0, 0, 0, 0.9f, 0.9f, 0.85f);
        m.b(heart, mat, small, 0.62f, 0.62f, 0.15f, 0.22f, 0.22f, 0.2f).rot(0, 0, -20);
        m.b(heart, mat, small, -0.68f, 0.42f, 0.05f, 0.15f, 0.15f, 0.14f).rot(0, 0, 20);
        return m;
    }

    private Model cat() {
        int f = 0xf29a3c, w = 0xfff0dc;
        Model m = new Model();
        m.b(sphere, SOFT, f, 0, -0.42f, 0, 0.48f, 0.52f, 0.42f);
        m.b(sphere, SOFT, w, 0, -0.42f, 0.28f, 0.26f, 0.34f, 0.18f);
        m.b(sphere, SOFT, f, 0, 0.32f, 0, 0.52f, 0.44f, 0.44f);
        m.b(cone, SOFT, f, 0.32f, 0.8f, 0, 0.18f, 0.22f, 0.12f).rot(0, 0, -12);
        m.b(cone, SOFT, f, -0.32f, 0.8f, 0, 0.18f, 0.22f, 0.12f).rot(0, 0, 12);
        m.b(cone, SOFT, 0xf7a8b8, 0.32f, 0.78f, 0.06f, 0.1f, 0.13f, 0.06f).rot(0, 0, -12);
        m.b(cone, SOFT, 0xf7a8b8, -0.32f, 0.78f, 0.06f, 0.1f, 0.13f, 0.06f).rot(0, 0, 12);
        m.b(sphere, GLASS, 0x2fae5a, 0.2f, 0.38f, 0.38f, 0.1f, 0.13f, 0.06f);
        m.b(sphere, GLASS, 0x2fae5a, -0.2f, 0.38f, 0.38f, 0.1f, 0.13f, 0.06f);
        m.b(sphere, GLASS, 0x101010, 0.2f, 0.38f, 0.43f, 0.04f, 0.1f, 0.03f);
        m.b(sphere, GLASS, 0x101010, -0.2f, 0.38f, 0.43f, 0.04f, 0.1f, 0.03f);
        m.b(heart, SHINY, 0xf7839a, 0, 0.22f, 0.43f, 0.06f, 0.06f, 0.04f);
        for (int s = -1; s <= 1; s += 2) {
            m.b(box, SOFT, 0xffffff, s * 0.46f, 0.24f, 0.32f, 0.2f, 0.008f, 0.008f).rot(0, 0, s * 10);
            m.b(box, SOFT, 0xffffff, s * 0.46f, 0.17f, 0.32f, 0.2f, 0.008f, 0.008f).rot(0, 0, -s * 10);
        }
        m.b(ring, SOFT, f, 0.45f, -0.45f, -0.1f, 0.38f, 0.38f, 0.14f).rot(90, 0, 0);
        m.b(sphere, SOFT, w, 0.2f, -0.86f, 0.25f, 0.15f, 0.11f, 0.2f);
        m.b(sphere, SOFT, w, -0.2f, -0.86f, 0.25f, 0.15f, 0.11f, 0.2f);
        return m;
    }

    private Model cake() {
        Model m = new Model();
        m.b(coin, GOLD, 0xf4c648, 0, -0.78f, 0, 0.98f, 0.05f, 0.98f);
        m.b(cyl, SOFT, 0xf9b6cf, 0, -0.42f, 0, 0.82f, 0.32f, 0.82f);
        m.b(ring, SOFT, 0xffffff, 0, -0.12f, 0, 0.85f, 0.14f, 0.85f);
        m.b(cyl, SOFT, 0xfff3e0, 0, 0.14f, 0, 0.55f, 0.26f, 0.55f);
        m.b(ring, SOFT, 0xf56f9e, 0, 0.38f, 0, 0.57f, 0.12f, 0.57f);
        for (int i = 0; i < 5; i++) {
            double a = i * 2 * Math.PI / 5;
            float x = (float) Math.cos(a) * 0.32f, z = (float) Math.sin(a) * 0.32f;
            m.b(cyl, SHINY, i % 2 == 0 ? 0x60a5fa : 0xfde047, x, 0.56f, z, 0.03f, 0.15f, 0.03f);
            m.b(flame, GLOW, 0xffb52e, x, 0.8f, z, 0.07f, 0.1f, 0.07f);
        }
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            m.a(sphere, SHINY, 0xe11d48, (float) Math.cos(a) * 0.7f, -0.05f, (float) Math.sin(a) * 0.7f, 0.08f);
        }
        return m;
    }

    private Model balloons() {
        Model m = new Model();
        int[][] b = {{0, 42, 0xfacc15, 46}, {-48, 18, 0xef4444, 40}, {50, 16, 0x3b82f6, 40}};
        for (int[] x : b) {
            float px = x[0] / 100f, py = x[1] / 100f, s = x[3] / 100f;
            m.b(sphere, GLASS, x[2], px, py, 0, s, s * 1.18f, s);
            m.a(sphere, SOFT, 0xffffff, px - s * 0.3f, py + s * 0.45f, s * 0.8f, s * 0.13f);
            m.b(cone, SHINY, x[2], px, py - s * 1.22f, 0, 0.05f, 0.05f, 0.05f);
            float dx = -px * 0.5f;
            m.b(box, SOFT, 0xffffff, px + dx, (py - s * 1.25f - 0.9f) / 2f, 0, 0.008f, Math.abs(py - s * 1.25f + 0.9f) / 2f, 0.008f).rot(0, 0, (float) Math.toDegrees(Math.atan2(px, py - s * 1.25f + 0.9f)));
        }
        return m;
    }

    private Model trophy() {
        Model m = new Model();
        m.b(goblet, GOLD, 0xf6c33d, 0, 0.1f, 0, 0.72f, 0.82f, 0.72f);
        for (int s = -1; s <= 1; s += 2) m.b(ring, GOLD, 0xf6c33d, s * 0.62f, 0.45f, 0, 0.26f, 0.32f, 0.14f).rot(90, 0, 0);
        m.b(box, SHINY, 0x3b1d6e, 0, -0.84f, 0, 0.52f, 0.13f, 0.42f);
        m.b(star, GOLD, 0xfff3b0, 0, 0.62f, 0.56f, 0.2f, 0.2f, 0.1f);
        return m;
    }

    private Model royalBouquet() {
        Model m = bouquet(0x4c1d95, 0xa30f2d, 0xf6c33d, GOLD);
        m.a(gem, GEM, 0xc084fc, 0, -0.32f, 0.36f, 0.12f).rot(90, 0, 0);
        m.a(gem, GEM, 0x93c5fd, 0, 0.85f, 0.1f, 0.08f);
        return m;
    }

    private Model butterfly() {
        Model m = new Model();
        m.b(sphere, SHINY, 0x24143d, 0, 0, 0, 0.09f, 0.55f, 0.09f);
        m.a(sphere, SHINY, 0x24143d, 0, 0.58f, 0, 0.12f);
        for (int s = -1; s <= 1; s += 2) {
            m.b(box, SOFT, 0x24143d, s * 0.12f, 0.82f, 0, 0.008f, 0.2f, 0.008f).rot(0, 0, -s * 22);
            m.a(sphere, GLOW, 0xfde047, s * 0.2f, 1f, 0, 0.035f);
            int a = s < 0 ? FLAP_L : FLAP_R;
            m.b(wing, GEM, 0x8b5cf6, s * 0.06f, 0.08f, 0, s * 0.92f, 0.92f, 1f).anim(a);
            m.b(wing, GEM, 0x22d3ee, s * 0.06f, -0.08f, 0, s * 0.62f, -0.72f, 1f).anim(a);
            m.b(wing, GLOW, 0xf0abfc, s * 0.05f, 0.1f, 0.03f, s * 0.45f, 0.5f, 1f).anim(a);
        }
        return m;
    }

    private Model loveBalloon() {
        Model m = new Model();
        m.b(heart, GLASS, 0xf472b6, 0, 0.25f, 0, 0.78f, 0.78f, 0.7f);
        m.a(sphere, SOFT, 0xffffff, -0.32f, 0.55f, 0.36f, 0.09f);
        m.b(cone, SHINY, 0xf472b6, 0, -0.56f, 0, 0.06f, 0.06f, 0.06f);
        m.b(box, SOFT, 0xffffff, 0, -0.82f, 0, 0.008f, 0.24f, 0.008f);
        m.b(heart, SHINY, 0xfecdd3, 0.46f, -0.56f, 0, 0.16f, 0.16f, 0.12f);
        return m;
    }

    private Model crown() {
        int g = 0xf6c33d;
        Model m = new Model();
        m.b(cyl, GOLD, g, 0, -0.42f, 0, 0.82f, 0.2f, 0.82f);
        m.b(sphere, SOFT, 0x5b21b6, 0, -0.1f, 0, 0.72f, 0.45f, 0.72f);
        m.b(ring, GOLD, 0xffe58a, 0, -0.62f, 0, 0.86f, 0.1f, 0.86f);
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3 + Math.PI / 6;
            float x = (float) Math.cos(a) * 0.72f, z = (float) Math.sin(a) * 0.72f;
            m.b(cone, GOLD, g, x, 0.02f, z, 0.21f, 0.36f, 0.14f).rot(0, -(float) Math.toDegrees(a), 0);
            m.a(sphere, GOLD, 0xffe58a, x, 0.42f, z, 0.075f);
            m.a(gem, GEM, i % 2 == 0 ? 0xc026d3 : 0xef4444, (float) Math.cos(a) * 0.82f, -0.42f, (float) Math.sin(a) * 0.82f, 0.1f).rot(90, (float) Math.toDegrees(Math.PI / 2 - a), 0);
        }
        m.a(sphere, GOLD, g, 0, 0.38f, 0, 0.1f);
        m.a(gem, GEM, 0xe9d5ff, 0, 0.56f, 0, 0.13f);
        return m;
    }

    private Model diamondBear() {
        Model m = bear(0x9bd5ff, GEM, 0xe8f7ff, 0xc084fc);
        m.b(cyl, GOLD, 0xf6c33d, 0, 0.92f, 0, 0.26f, 0.08f, 0.26f);
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2 + 0.78;
            m.b(cone, GOLD, 0xf6c33d, (float) Math.cos(a) * 0.2f, 1.04f, (float) Math.sin(a) * 0.2f, 0.07f, 0.1f, 0.07f);
        }
        return m;
    }

    private Model peacock() {
        Model m = new Model();
        // The fan behind, gently breathing.
        for (int i = -5; i <= 5; i++) {
            float a = i * 15f;
            double ra = Math.toRadians(a);
            float x = (float) Math.sin(ra) * 0.72f, y = (float) Math.cos(ra) * 0.72f - 0.25f;
            m.b(flame, SHINY, i % 2 == 0 ? 0x0e7490 : 0x047857, x, y, -0.3f, 0.2f, 0.48f, 0.12f).rot(0, 0, -a).anim(SWAY);
            m.b(petal, GEM, 0x2563eb, x * 1.33f, y * 1.2f + 0.08f, -0.2f, 0.09f, 0.08f, 0.06f).anim(SWAY);
            m.a(sphere, GEM, 0xfbbf24, x * 1.33f, y * 1.2f + 0.08f, -0.14f, 0.035f).anim(SWAY);
        }
        m.b(sphere, GEM, 0x1e40af, 0, -0.45f, 0.15f, 0.32f, 0.4f, 0.3f);
        m.b(sphere, GEM, 0x1d4ed8, 0, -0.04f, 0.26f, 0.12f, 0.34f, 0.12f);
        m.a(sphere, GEM, 0x2563eb, 0, 0.32f, 0.3f, 0.14f);
        m.b(cone, GOLD, 0xf59e0b, 0, 0.3f, 0.47f, 0.05f, 0.09f, 0.05f).rot(90, 0, 0);
        m.a(sphere, GLASS, 0x0a0a0a, 0.08f, 0.36f, 0.42f, 0.028f);
        m.a(sphere, GLASS, 0x0a0a0a, -0.08f, 0.36f, 0.42f, 0.028f);
        for (int i = -1; i <= 1; i++) m.a(sphere, GOLD, 0xfde047, i * 0.07f, 0.56f, 0.28f, 0.03f);
        m.b(cone, GOLD, 0xd97706, 0.12f, -0.84f, 0.2f, 0.05f, 0.08f, 0.1f);
        m.b(cone, GOLD, 0xd97706, -0.12f, -0.84f, 0.2f, 0.05f, 0.08f, 0.1f);
        return m;
    }

    private Model phoenix() {
        Model m = new Model();
        for (int s = -1; s <= 1; s += 2) {
            int a = s < 0 ? FLAP_L : FLAP_R;
            m.b(wing, GOLD, 0xf59e0b, s * 0.12f, 0.14f, 0, s * 1.02f, 0.9f, 1f).anim(a);
            m.b(wing, GLOW, 0xef4444, s * 0.12f, 0.14f, 0.02f, s * 0.72f, 0.62f, 1f).anim(a);
            m.b(wing, GLOW, 0xfde047, s * 0.12f, 0.16f, 0.04f, s * 0.42f, 0.36f, 1f).anim(a);
        }
        m.b(sphere, GOLD, 0xfb923c, 0, -0.02f, 0, 0.24f, 0.42f, 0.24f);
        m.a(sphere, GOLD, 0xfbbf24, 0, 0.48f, 0.08f, 0.17f);
        m.b(cone, GOLD, 0xfff3b0, 0, 0.46f, 0.3f, 0.05f, 0.1f, 0.05f).rot(90, 0, 0);
        m.a(sphere, GLASS, 0x1a0505, 0.08f, 0.53f, 0.2f, 0.026f);
        m.a(sphere, GLASS, 0x1a0505, -0.08f, 0.53f, 0.2f, 0.026f);
        for (int i = -1; i <= 1; i++) m.b(flame, GLOW, 0xf97316, i * 0.09f, 0.72f, 0, 0.05f, 0.12f, 0.05f).rot(0, 0, -i * 16);
        for (int i = -2; i <= 2; i++) m.b(flame, i == 0 ? GLOW : GOLD, i % 2 == 0 ? 0xfacc15 : 0xef4444, i * 0.16f, -0.72f, -0.05f, 0.13f, 0.34f, 0.08f).rot(0, 0, i * 15).anim(SWAY);
        return m;
    }

    private Model dragon() {
        int s = 0x047857, b = 0xa7f3d0;
        Model m = new Model();
        for (int w = -1; w <= 1; w += 2) {
            int a = w < 0 ? FLAP_L : FLAP_R;
            m.b(wing, GEM, 0x5b21b6, w * 0.15f, 0.12f, -0.1f, w * 0.98f, 0.82f, 1f).anim(a);
            m.b(wing, GEM, 0xa855f7, w * 0.15f, 0.12f, -0.08f, w * 0.6f, 0.45f, 1f).anim(a);
        }
        // A serpentine body from tail to neck.
        float[][] seg = {{0.55f, -0.82f, 0.06f}, {0.42f, -0.72f, 0.1f}, {0.22f, -0.62f, 0.15f}, {0.02f, -0.46f, 0.21f}, {-0.05f, -0.2f, 0.28f}, {0, 0.1f, 0.28f}, {0.04f, 0.36f, 0.2f}};
        for (int i = 0; i < seg.length; i++) {
            m.a(sphere, SHINY, s, seg[i][0], seg[i][1], 0, seg[i][2]);
            if (i >= 2) m.b(cone, GOLD, 0xf59e0b, seg[i][0], seg[i][1] + seg[i][2], -seg[i][2] * 0.6f, 0.05f, 0.09f, 0.05f);
        }
        m.b(sphere, SOFT, b, -0.05f, -0.2f, 0.17f, 0.17f, 0.24f, 0.13f);
        m.b(sphere, SHINY, s, 0.06f, 0.58f, 0.08f, 0.22f, 0.18f, 0.24f);
        m.b(sphere, SHINY, s, 0.06f, 0.52f, 0.28f, 0.14f, 0.1f, 0.16f);
        m.a(sphere, GLOW, 0xfde047, 0.18f, 0.65f, 0.2f, 0.04f);
        m.a(sphere, GLOW, 0xfde047, -0.06f, 0.65f, 0.2f, 0.04f);
        m.b(cone, GOLD, 0xfff3b0, 0.18f, 0.84f, -0.04f, 0.04f, 0.14f, 0.04f).rot(-25, 0, -12);
        m.b(cone, GOLD, 0xfff3b0, -0.06f, 0.84f, -0.04f, 0.04f, 0.14f, 0.04f).rot(-25, 0, 12);
        m.b(flame, GLOW, 0x22d3ee, 0.06f, 0.46f, 0.52f, 0.08f, 0.14f, 0.08f).rot(90, 0, 0);
        m.b(flame, GEM, 0xf59e0b, 0.68f, -0.9f, 0, 0.1f, 0.14f, 0.05f).rot(0, 0, -65);
        return m;
    }

    private Model chest() {
        int g = 0xf6c33d;
        float h = 0.42f;
        Model m = new Model();
        m.b(box, SHINY, 0x7a3d12, 0, -0.42f, 0, 0.92f, h, 0.6f);
        m.b(box, SOFT, 0x241005, 0, -0.03f, 0, 0.85f, 0.02f, 0.52f);
        for (int i = 0; i < 7; i++) m.b(coin, GOLD, 0xffd34d, -0.6f + i * 0.2f, 0.03f, (i % 2 == 0 ? 0.15f : -0.18f), 0.14f, 0.02f, 0.14f).rot(i * 12f, 0, i * 7f);
        for (int i = 0; i < 3; i++) m.a(gem, GEM, i == 1 ? 0xa855f7 : 0x93c5fd, -0.35f + i * 0.35f, 0.14f, 0.05f, 0.12f);
        m.b(box, GOLD, g, 0, -0.8f, 0, 0.97f, 0.05f, 0.65f);
        m.b(box, GOLD, g, 0.68f, -0.42f, 0.61f, 0.06f, h, 0.02f);
        m.b(box, GOLD, g, -0.68f, -0.42f, 0.61f, 0.06f, h, 0.02f);
        m.b(box, GOLD, 0xffe58a, 0, -0.16f, 0.62f, 0.16f, 0.17f, 0.03f);
        m.a(gem, GEM, 0xc026d3, 0, -0.16f, 0.67f, 0.1f).rot(90, 0, 0);
        // The lid.
        m.b(arch, SHINY, 0x8a4716, 0, 0, -0.6f, 0.92f, 0.46f, 0.6f).anim(LID);
        m.b(arch, GOLD, g, 0.68f, 0, -0.6f, 0.06f, 0.475f, 0.62f).anim(LID);
        m.b(arch, GOLD, g, -0.68f, 0, -0.6f, 0.06f, 0.475f, 0.62f).anim(LID);
        m.b(arch, GOLD, 0xffe58a, 0.92f, 0, -0.6f, 0.03f, 0.47f, 0.61f).anim(LID);
        m.b(arch, GOLD, 0xffe58a, -0.92f, 0, -0.6f, 0.03f, 0.47f, 0.61f).anim(LID);
        return m;
    }

    private Model car() {
        int c = 0xdc2626, d = 0x991b1b;
        Model m = new Model();
        m.b(box, GLASS, c, 0, -0.32f, 0, 0.5f, 0.17f, 1f);
        m.b(box, GLASS, d, 0, -0.45f, 0, 0.52f, 0.05f, 1.02f);
        m.b(sphere, GLASS, c, 0, -0.2f, 0.58f, 0.48f, 0.11f, 0.46f);
        m.b(arch, GLASS, 0x1f2937, 0, -0.16f, -0.12f, 0.41f, 0.3f, 0.48f).rot(0, 0, 0);
        m.b(box, GLASS, c, 0, 0.12f, -0.14f, 0.42f, 0.015f, 0.2f);
        m.b(box, SHINY, d, 0, 0.0f, -0.92f, 0.5f, 0.02f, 0.1f);
        for (int s = -1; s <= 1; s += 2) {
            m.b(box, GLASS, d, s * 0.42f, -0.08f, -0.92f, 0.02f, 0.1f, 0.05f);
            m.a(sphere, GLOW, 0xfff7c2, s * 0.34f, -0.26f, 1f, 0.07f);
            m.a(sphere, GLOW, 0xff2d2d, s * 0.34f, -0.26f, -1f, 0.06f);
            for (int z = -1; z <= 1; z += 2) {
                float zz = z * 0.6f;
                m.b(coin, SOFT, 0x111827, s * 0.5f, -0.52f, zz, 0.22f, 0.07f, 0.22f).rot(0, 0, 90);
                m.b(coin, GOLD, 0xe5e7eb, s * 0.56f, -0.52f, zz, 0.12f, 0.02f, 0.12f).rot(0, 0, 90).anim(WHEEL);
            }
        }
        return m;
    }

    private Model palace() {
        int w = 0xfff7ed, g = 0xf6c33d, p = 0x7c3aed;
        Model m = new Model();
        m.b(box, GOLD, g, 0, -0.84f, 0, 0.98f, 0.06f, 0.72f);
        m.b(box, SHINY, w, 0, -0.45f, 0, 0.72f, 0.34f, 0.46f);
        m.b(box, SOFT, 0x4c1d95, 0, -0.58f, 0.47f, 0.13f, 0.21f, 0.015f);
        m.b(arch, SOFT, 0x4c1d95, 0, -0.37f, 0.47f, 0.13f, 0.12f, 0.015f).rot(0, 90, 0);
        for (int i = -1; i <= 1; i += 2) {
            m.b(box, SOFT, 0x60a5fa, i * 0.42f, -0.42f, 0.47f, 0.07f, 0.11f, 0.012f);
            m.b(cyl, SHINY, w, i * 0.8f, -0.42f, 0.42f, 0.13f, 0.42f, 0.13f);
            m.b(cone, GOLD, p, i * 0.8f, 0.14f, 0.42f, 0.17f, 0.18f, 0.17f);
            m.a(sphere, GOLD, g, i * 0.8f, 0.34f, 0.42f, 0.035f);
            m.b(cyl, SHINY, w, i * 0.8f, -0.42f, -0.4f, 0.11f, 0.38f, 0.11f);
            m.b(cone, GOLD, p, i * 0.8f, 0.1f, -0.4f, 0.14f, 0.15f, 0.14f);
        }
        m.b(cyl, SHINY, w, 0, 0.0f, 0, 0.34f, 0.22f, 0.34f);
        m.b(sphere, GOLD, g, 0, 0.3f, 0, 0.36f, 0.32f, 0.36f);
        m.a(gem, GEM, 0xc084fc, 0, 0.66f, 0, 0.09f);
        m.b(cyl, GOLD, g, 0, 0.84f, 0, 0.012f, 0.14f, 0.012f);
        m.b(triangle, SHINY, 0xdc2626, 0.09f, 0.92f, 0, 0.08f, 0.055f, 0.3f).rot(0, 0, -90).anim(SWAY);
        return m;
    }

    // ================================================================= drawing

    /**
     * Draw a gift under `base`, on a shader with uGlow. `secs` drives wings and wheels, `flap` their
     * speed, `open` a lid from 0 to 1. The caller has set the view-projection matrix, depth and blending.
     */
    void draw(String id, Shader s, int uGlow, float[] vp, float[] base, float secs, float flap, float open) {
        Model model = models.get(id);
        if (model == null) model = models.get("heart_gold");
        GLES20.glUniform1f(s.uUseTex, 0f);
        GLES20.glUniform1f(s.uUnlit, 0f);
        float beat = (float) Math.sin(secs * flap);
        for (int i = 0, n = model.parts.size(); i < n; i++) {
            Part p = model.parts.get(i);
            Matrix.translateM(m1, 0, base, 0, p.tx, p.ty, p.tz);
            switch (p.anim) {
                case FLAP_L: Matrix.rotateM(m1, 0, -beat * 32f, 0, 1, 0); break;
                case FLAP_R: Matrix.rotateM(m1, 0, beat * 32f, 0, 1, 0); break;
                case WHEEL: Matrix.rotateM(m1, 0, secs * flap * 60f, 1, 0, 0); break;
                case LID:
                    // The hinge is the part's origin, along the back edge; the lid itself sits forward of it.
                    Matrix.rotateM(m1, 0, -open * 105f, 1, 0, 0);
                    Matrix.translateM(m1, 0, 0, 0, Math.abs(p.tz));
                    break;
                case SWAY: Matrix.rotateM(m1, 0, (float) Math.sin(secs * 2.2f + p.tx * 3f) * 4f, 0, 0, 1); break;
                default: break;
            }
            if (p.rz != 0) Matrix.rotateM(m1, 0, p.rz, 0, 0, 1);
            if (p.ry != 0) Matrix.rotateM(m1, 0, p.ry, 0, 1, 0);
            if (p.rx != 0) Matrix.rotateM(m1, 0, p.rx, 1, 0, 0);
            Matrix.scaleM(m1, 0, p.sx, p.sy, p.sz);
            Matrix.multiplyMM(mvp, 0, vp, 0, m1, 0);
            GLES20.glUniformMatrix4fv(s.uMVP, 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(s.uModel, 1, false, m1, 0);
            GLES20.glUniform4f(s.uColor, p.r, p.g, p.b, 1f);
            GLES20.glUniform1f(s.uSpec, p.spec);
            GLES20.glUniform1f(uGlow, p.glow);
            p.mesh.draw(s);
        }
    }
}
