package com.ludo3d.game.gl;

import android.opengl.GLES20;

/** One lit shader for everything: Lambert + Blinn specular, optional texture, optional unlit mode. */
final class Shader {
    private static final String VS =
            "uniform mat4 uMVP;\n" +
            "uniform mat4 uModel;\n" +
            "attribute vec3 aPos;\n" +
            "attribute vec3 aNormal;\n" +
            "attribute vec2 aUV;\n" +
            "varying vec3 vN;\n" +
            "varying vec2 vUV;\n" +
            "varying vec3 vW;\n" +
            "void main() {\n" +
            "  vN = (uModel * vec4(aNormal, 0.0)).xyz;\n" +
            "  vUV = aUV;\n" +
            "  vW = (uModel * vec4(aPos, 1.0)).xyz;\n" +
            "  gl_Position = uMVP * vec4(aPos, 1.0);\n" +
            "}\n";

    private static final String FS =
            "precision mediump float;\n" +
            "uniform vec4 uColor;\n" +
            "uniform sampler2D uTex;\n" +
            "uniform float uUseTex;\n" +
            "uniform float uUnlit;\n" +
            "uniform float uSpec;\n" +
            "uniform vec3 uLight;\n" +
            "uniform vec3 uEye;\n" +
            "varying vec3 vN;\n" +
            "varying vec2 vUV;\n" +
            "varying vec3 vW;\n" +
            "void main() {\n" +
            "  vec4 base = uColor;\n" +
            "  if (uUseTex > 0.5) base *= texture2D(uTex, vUV);\n" +
            "  if (uUnlit > 0.5) { gl_FragColor = base; return; }\n" +
            "  vec3 n = normalize(vN);\n" +
            "  vec3 L = normalize(uLight);\n" +
            "  float d = max(dot(n, L), 0.0);\n" +
            "  vec3 V = normalize(uEye - vW);\n" +
            "  vec3 H = normalize(L + V);\n" +
            "  float s = pow(max(dot(n, H), 0.0), 40.0) * uSpec;\n" +
            "  float rim = pow(1.0 - max(dot(n, V), 0.0), 3.0) * 0.18;\n" +
            "  gl_FragColor = vec4(base.rgb * (0.46 + 0.62 * d) + vec3(s + rim), base.a);\n" +
            "}\n";

    final int program;
    final int aPos, aNormal, aUV;
    final int uMVP, uModel, uColor, uTex, uUseTex, uUnlit, uSpec, uLight, uEye;

    Shader() {
        program = link(compile(GLES20.GL_VERTEX_SHADER, VS), compile(GLES20.GL_FRAGMENT_SHADER, FS));
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aNormal = GLES20.glGetAttribLocation(program, "aNormal");
        aUV = GLES20.glGetAttribLocation(program, "aUV");
        uMVP = GLES20.glGetUniformLocation(program, "uMVP");
        uModel = GLES20.glGetUniformLocation(program, "uModel");
        uColor = GLES20.glGetUniformLocation(program, "uColor");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        uUseTex = GLES20.glGetUniformLocation(program, "uUseTex");
        uUnlit = GLES20.glGetUniformLocation(program, "uUnlit");
        uSpec = GLES20.glGetUniformLocation(program, "uSpec");
        uLight = GLES20.glGetUniformLocation(program, "uLight");
        uEye = GLES20.glGetUniformLocation(program, "uEye");
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("Shader compile failed: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static int link(int vs, int fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("Shader link failed: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }
}
