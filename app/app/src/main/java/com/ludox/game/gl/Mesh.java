package com.ludox.game.gl;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

/** Indexed triangle mesh in a VBO; vertices are interleaved position(3) normal(3) uv(2). */
final class Mesh {
    private static final int STRIDE = 8 * 4;

    private final int vbo, ibo, count;

    private Mesh(float[] verts, short[] idx) {
        int[] b = new int[2];
        GLES20.glGenBuffers(2, b, 0);
        vbo = b[0];
        ibo = b[1];
        FloatBuffer fb = ByteBuffer.allocateDirect(verts.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(verts).position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.length * 4, fb, GLES20.GL_STATIC_DRAW);
        ShortBuffer sb = ByteBuffer.allocateDirect(idx.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        sb.put(idx).position(0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo);
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, idx.length * 2, sb, GLES20.GL_STATIC_DRAW);
        count = idx.length;
    }

    void draw(Shader s) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo);
        GLES20.glEnableVertexAttribArray(s.aPos);
        GLES20.glVertexAttribPointer(s.aPos, 3, GLES20.GL_FLOAT, false, STRIDE, 0);
        if (s.aNormal >= 0) {
            GLES20.glEnableVertexAttribArray(s.aNormal);
            GLES20.glVertexAttribPointer(s.aNormal, 3, GLES20.GL_FLOAT, false, STRIDE, 12);
        }
        if (s.aUV >= 0) {
            GLES20.glEnableVertexAttribArray(s.aUV);
            GLES20.glVertexAttribPointer(s.aUV, 2, GLES20.GL_FLOAT, false, STRIDE, 24);
        }
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, count, GLES20.GL_UNSIGNED_SHORT, 0);
    }

    // ------------------------------------------------------------------ builders

    private static final class Builder {
        final List<Float> v = new ArrayList<>();
        final List<Short> i = new ArrayList<>();

        int vertex(float x, float y, float z, float nx, float ny, float nz, float u, float w) {
            v.add(x); v.add(y); v.add(z);
            v.add(nx); v.add(ny); v.add(nz);
            v.add(u); v.add(w);
            return v.size() / 8 - 1;
        }

        void tri(int a, int b, int c) {
            i.add((short) a);
            i.add((short) b);
            i.add((short) c);
        }

        /** A quad (a,b,c,d counter-clockwise when seen from the front). */
        void quad(float[] a, float[] b, float[] c, float[] d, float[] n, float u0, float v0, float u1, float v1) {
            int ia = vertex(a[0], a[1], a[2], n[0], n[1], n[2], u0, v1);
            int ib = vertex(b[0], b[1], b[2], n[0], n[1], n[2], u1, v1);
            int ic = vertex(c[0], c[1], c[2], n[0], n[1], n[2], u1, v0);
            int id = vertex(d[0], d[1], d[2], n[0], n[1], n[2], u0, v0);
            tri(ia, ib, ic);
            tri(ia, ic, id);
        }

        Mesh build() {
            float[] fv = new float[v.size()];
            for (int k = 0; k < fv.length; k++) fv[k] = v.get(k);
            short[] si = new short[i.size()];
            for (int k = 0; k < si.length; k++) si[k] = i.get(k);
            return new Mesh(fv, si);
        }
    }

    /** Horizontal square in the XZ plane at y=0, facing up; u follows +x, v follows +z. */
    static Mesh plane(float half) {
        Builder b = new Builder();
        b.quad(new float[]{-half, 0, half}, new float[]{half, 0, half}, new float[]{half, 0, -half}, new float[]{-half, 0, -half},
                new float[]{0, 1, 0}, 0, 0, 1, 1);
        // quad() puts v1 on a/b (the +z edge) and v0 on c/d: matches bitmap rows growing with z.
        return b.build();
    }

    /** Axis-aligned box centred at the origin. UVs map each face to the dice atlas (value per face). */
    static Mesh box(float hx, float hy, float hz, boolean diceUv) {
        Builder b = new Builder();
        // Faces: +X=3, -X=4, +Y=1, -Y=6, +Z=2, -Z=5 (opposite faces sum to 7).
        float[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int[] value = {3, 4, 1, 6, 2, 5};
        for (int f = 0; f < 6; f++) {
            float[] nn = n[f];
            // Two tangent axes for this face.
            float[] t1 = nn[1] != 0 ? new float[]{1, 0, 0} : new float[]{0, 1, 0};
            float[] t2 = cross(nn, t1);
            float[][] c = new float[4][3];
            float[][] sgn = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            for (int k = 0; k < 4; k++) {
                for (int ax = 0; ax < 3; ax++) {
                    float h = ax == 0 ? hx : ax == 1 ? hy : hz;
                    c[k][ax] = (nn[ax] + t2[ax] * sgn[k][0] + t1[ax] * sgn[k][1]) * h;
                }
            }
            float u0 = 0, v0 = 0, u1 = 1, v1 = 1;
            if (diceUv) {
                int cell = value[f] - 1;
                u0 = (cell % 3) / 3f;
                u1 = u0 + 1 / 3f;
                v0 = (cell / 3) / 2f;
                v1 = v0 + 0.5f;
            }
            b.quad(c[0], c[1], c[2], c[3], nn, u0, v0, u1, v1);
        }
        return b.build();
    }

    /** A flat square in the XY plane, -1..1, facing +z (sprites, and full screen with an identity matrix). */
    static Mesh quad() {
        Builder b = new Builder();
        b.quad(new float[]{-1, -1, 0}, new float[]{1, -1, 0}, new float[]{1, 1, 0}, new float[]{-1, 1, 0}, new float[]{0, 0, 1}, 0, 0, 1, 1);
        return b.build();
    }

    /** A half cylinder lying along X (x -1..1, y 0..1, z -1..1): the arched lid of a chest, closed at both ends. */
    static Mesh arch(int segments) {
        Builder b = new Builder();
        int[] near = new int[segments + 1], far = new int[segments + 1];
        for (int k = 0; k <= segments; k++) {
            double a = Math.PI * k / segments;
            float y = (float) Math.sin(a), z = (float) Math.cos(a), u = k / (float) segments;
            near[k] = b.vertex(-1, y, z, 0, y, z, 0, u);
            far[k] = b.vertex(1, y, z, 0, y, z, 1, u);
        }
        for (int k = 0; k < segments; k++) {
            b.tri(near[k], far[k], far[k + 1]);
            b.tri(near[k], far[k + 1], near[k + 1]);
        }
        for (int side = -1; side <= 1; side += 2) {
            int centre = b.vertex(side, 0, 0, side, 0, 0, 0.5f, 0.5f);
            int prev = -1;
            for (int k = 0; k <= segments; k++) {
                double a = Math.PI * k / segments;
                float y = (float) Math.sin(a), z = (float) Math.cos(a);
                int v = b.vertex(side, y, z, side, 0, 0, 0.5f + z * 0.5f, 0.5f - y * 0.5f);
                if (prev >= 0) b.tri(centre, prev, v);
                prev = v;
            }
        }
        return b.build();
    }

    /** A coin (radius 1, y -1..1): a picture of its face on both sides, a plain rim. */
    static Mesh coin(int segments) {
        Builder b = new Builder();
        for (int side = -1; side <= 1; side += 2) {
            int centre = b.vertex(0, side, 0, 0, side, 0, 0.5f, 0.5f);
            int prev = -1;
            for (int k = 0; k <= segments; k++) {
                double a = 2 * Math.PI * k / segments;
                float x = (float) Math.cos(a), z = (float) Math.sin(a);
                int v = b.vertex(x, side, z, 0, side, 0, 0.5f + x * 0.5f, 0.5f + z * 0.5f);
                if (prev >= 0) b.tri(centre, prev, v);
                prev = v;
            }
        }
        int prevTop = -1, prevBottom = -1;
        for (int k = 0; k <= segments; k++) {
            double a = 2 * Math.PI * k / segments;
            float x = (float) Math.cos(a), z = (float) Math.sin(a);
            int t = b.vertex(x, 1, z, x, 0, z, 0.02f, 0.5f);
            int d = b.vertex(x, -1, z, x, 0, z, 0.02f, 0.5f);
            if (prevTop >= 0) {
                b.tri(prevTop, t, d);
                b.tri(prevTop, d, prevBottom);
            }
            prevTop = t;
            prevBottom = d;
        }
        return b.build();
    }

    /**
     * A puffy solid from a flat outline around (0,0), counter-clockwise and visible from its centre:
     * hearts, wings, flames. `depth` is its half thickness at the middle.
     */
    static Mesh extrude(float[][] outline, float depth) {
        Builder b = new Builder();
        int n = outline.length;
        for (int side = -1; side <= 1; side += 2) {
            int centre = b.vertex(0, 0, side * depth, 0, 0, side, 0.5f, 0.5f);
            int first = -1, prev = -1;
            for (float[] p : outline) {
                float l = (float) Math.sqrt(p[0] * p[0] + p[1] * p[1]);
                if (l == 0) l = 1;
                int v = b.vertex(p[0], p[1], side * depth * 0.3f, p[0] / l * 0.75f, p[1] / l * 0.75f, side * 0.66f, 0.5f, 0.5f);
                if (prev >= 0) b.tri(centre, prev, v);
                else first = v;
                prev = v;
            }
            b.tri(centre, prev, first);
        }
        for (int k = 0; k < n; k++) {
            float[] p = outline[k], q = outline[(k + 1) % n];
            float nx = q[1] - p[1], ny = p[0] - q[0];
            float l = (float) Math.sqrt(nx * nx + ny * ny);
            if (l == 0) l = 1;
            float[] nn = {nx / l, ny / l, 0};
            float z = depth * 0.3f;
            b.quad(new float[]{p[0], p[1], -z}, new float[]{q[0], q[1], -z}, new float[]{q[0], q[1], z}, new float[]{p[0], p[1], z}, nn, 0, 0, 1, 1);
        }
        return b.build();
    }

    /** A brilliant-cut gem, table up (+y), with hard facets. */
    static Mesh gem(int sides) {
        Builder b = new Builder();
        float tableR = 0.58f, tableY = 0.42f, girdleY = 0.1f, culetY = -0.75f;
        float[] up = {0, 1, 0};
        int centre = b.vertex(0, tableY, 0, 0, 1, 0, 0.5f, 0.5f);
        for (int k = 0; k < sides; k++) {
            double a0 = 2 * Math.PI * k / sides, a1 = 2 * Math.PI * (k + 1) / sides;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float[] t0 = {c0 * tableR, tableY, s0 * tableR}, t1 = {c1 * tableR, tableY, s1 * tableR};
            float[] g0 = {c0, girdleY, s0}, g1 = {c1, girdleY, s1};
            float[] culet = {0, culetY, 0};
            // Table.
            b.tri(centre, b.vertex(t0[0], t0[1], t0[2], up[0], up[1], up[2], 0.5f, 0.5f), b.vertex(t1[0], t1[1], t1[2], up[0], up[1], up[2], 0.5f, 0.5f));
            // Crown facet and pavilion facet, each with its own flat normal.
            float[] crown = facet(t0, g0, t1);
            b.quad(g0, g1, t1, t0, crown, 0, 0, 1, 1);
            float[] pav = facet(g0, culet, g1);
            int p0 = b.vertex(g0[0], g0[1], g0[2], pav[0], pav[1], pav[2], 0, 0);
            int p1 = b.vertex(g1[0], g1[1], g1[2], pav[0], pav[1], pav[2], 1, 0);
            int p2 = b.vertex(culet[0], culet[1], culet[2], pav[0], pav[1], pav[2], 0.5f, 1);
            b.tri(p0, p1, p2);
        }
        return b.build();
    }

    /** Outward unit normal of a gem's facet, whichever way round its corners came. */
    private static float[] facet(float[] a, float[] b, float[] c) {
        float[] n = cross(new float[]{b[0] - a[0], b[1] - a[1], b[2] - a[2]}, new float[]{c[0] - a[0], c[1] - a[1], c[2] - a[2]});
        float l = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        if (l == 0) l = 1;
        float cx = (a[0] + b[0] + c[0]) / 3, cz = (a[2] + b[2] + c[2]) / 3;
        float dir = n[0] * cx + n[2] * cz >= 0 ? 1 : -1;
        return new float[]{dir * n[0] / l, dir * n[1] / l, dir * n[2] / l};
    }

    private static float[] cross(float[] a, float[] c) {
        return new float[]{a[1] * c[2] - a[2] * c[1], a[2] * c[0] - a[0] * c[2], a[0] * c[1] - a[1] * c[0]};
    }

    /**
     * Surface of revolution around +Y from a profile of (radius, y) points, top to bottom
     * or bottom to top. Normals are smoothed along the profile.
     */
    static Mesh lathe(float[][] profile, int segments) {
        Builder b = new Builder();
        int n = profile.length;
        float[][] segN = new float[n - 1][2];
        for (int k = 0; k < n - 1; k++) {
            float dr = profile[k + 1][0] - profile[k][0];
            float dy = profile[k + 1][1] - profile[k][1];
            float len = (float) Math.sqrt(dr * dr + dy * dy);
            if (len == 0) len = 1;
            // Outward normal of a profile going upwards: (dy, -dr).
            segN[k][0] = dy / len;
            segN[k][1] = -dr / len;
        }
        int[][] ring = new int[n][segments + 1];
        for (int k = 0; k < n; k++) {
            float nr, ny;
            if (k == 0) { nr = segN[0][0]; ny = segN[0][1]; }
            else if (k == n - 1) { nr = segN[n - 2][0]; ny = segN[n - 2][1]; }
            else { nr = segN[k - 1][0] + segN[k][0]; ny = segN[k - 1][1] + segN[k][1]; }
            float l = (float) Math.sqrt(nr * nr + ny * ny);
            if (l == 0) l = 1;
            nr /= l;
            ny /= l;
            for (int s = 0; s <= segments; s++) {
                double a = 2 * Math.PI * s / segments;
                float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                ring[k][s] = b.vertex(profile[k][0] * cos, profile[k][1], profile[k][0] * sin, nr * cos, ny, nr * sin, s / (float) segments, k / (float) (n - 1));
            }
        }
        for (int k = 0; k < n - 1; k++) {
            for (int s = 0; s < segments; s++) {
                int a = ring[k][s], c = ring[k][s + 1], d = ring[k + 1][s + 1], e = ring[k + 1][s];
                b.tri(a, d, c);
                b.tri(a, e, d);
            }
        }
        return b.build();
    }

    /** The playing piece: wide base, waist, collar and a round head (height ~0.82 cells). */
    static Mesh pawn() {
        List<float[]> p = new ArrayList<>();
        p.add(new float[]{0f, 0f});
        p.add(new float[]{0.34f, 0f});
        p.add(new float[]{0.37f, 0.04f});
        p.add(new float[]{0.35f, 0.10f});
        p.add(new float[]{0.27f, 0.15f});
        p.add(new float[]{0.20f, 0.22f});
        p.add(new float[]{0.16f, 0.34f});
        p.add(new float[]{0.14f, 0.44f});
        p.add(new float[]{0.21f, 0.48f});
        p.add(new float[]{0.21f, 0.51f});
        p.add(new float[]{0.13f, 0.54f});
        float cy = 0.66f, r = 0.165f;
        for (int deg = -60; deg <= 90; deg += 15) {
            double a = Math.toRadians(deg);
            p.add(new float[]{(float) (r * Math.cos(a)), (float) (cy + r * Math.sin(a))});
        }
        p.get(p.size() - 1)[0] = 0f;
        return lathe(p.toArray(new float[0][]), 28);
    }
}
