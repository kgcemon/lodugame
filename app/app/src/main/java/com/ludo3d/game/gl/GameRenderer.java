package com.ludo3d.game.gl;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;

import com.ludo3d.game.game.Board;
import com.ludo3d.game.game.GameState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The 3D board. It never decides anything: it animates the server's authoritative
 * updates (dice rolls, hops along the track, captures flying home) and then rests on
 * the server state. All scene methods run on the GL thread (call them via queueEvent).
 */
public final class GameRenderer implements GLSurfaceView.Renderer {
    /** Half size of the die: big enough to read, small enough to sit between the yard's token wells. */
    private static final float DICE_HALF = 0.36f;
    private static final float[][] RGB = new float[4][];

    static {
        for (int i = 0; i < 4; i++) {
            int c = Board.COLOR_ARGB[i];
            RGB[i] = new float[]{((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f};
        }
    }

    private Shader shader;
    private Mesh boardTop, boardBase, pawn, dice, flat, unitBox;
    private int texBoard, texDice, texShadow, texRing;

    private final float[] proj = new float[16], view = new float[16], vp = new float[16], model = new float[16], mvp = new float[16];
    private final float[] eye = new float[3];
    private int width = 1, height = 1;

    // ---------------------------------------------------------------- scene state (GL thread)
    private GameState state;
    private String myColor = "red";
    private boolean highlight;
    private final int[][] shown = new int[4][4];
    private final ArrayDeque<Anim> queue = new ArrayDeque<>();
    private Anim cur;
    private long curStart;
    private int animPlayer = -1, animToken = -1;
    private final float[] animPos = new float[3];
    private final float[][][] pos = new float[4][4][3];
    private final float[][] scale = new float[4][4];

    private int diceValue = 6;
    /** Player whose roll is being animated (the die stays with them until the queue drains). */
    private int diceOwner = -1;
    private float diceYaw = 20f;
    private final float[] dicePos = {0, DICE_HALF, 0};
    private float rollT = -1;
    private final Random rng = new Random();

    /** Camera yaw added by dragging (degrees). Written by the UI thread; eases back to 0 on release. */
    public volatile float yawOffset;
    /** True while a finger is turning the board. */
    public volatile boolean dragging;
    /** Token the player just tapped (shown with a bright ring until the server answers). */
    private int selPlayer = -1, selToken = -1;

    // ---------------------------------------------------------------- outputs for the UI thread
    private final float[] screen = new float[4 * 4 * 2];
    private final float[] diceScreen = new float[2];
    private volatile boolean idle = true;

    public GameRenderer() {
        for (int[] row : shown) java.util.Arrays.fill(row, -1);
    }

    // ================================================================= scene input (GL thread)

    public void setMyColor(String color) {
        myColor = color;
    }

    /**
     * Show a server update. Contiguous updates (seq = previous + 1) animate their events;
     * anything else (first update, reconnect, rematch) snaps straight to the state.
     */
    public void applyUpdate(GameState next, JSONArray events, boolean contiguous) {
        boolean first = state == null;
        state = next;
        selPlayer = selToken = -1;
        if (first || !contiguous || events == null) {
            queue.clear();
            cur = null;
            animPlayer = -1;
            snap();
            if (next.dice > 0) diceValue = next.dice;
            return;
        }
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null) continue;
            switch (e.optString("type")) {
                case "rolled":
                    queue.add(new RollAnim(e.optInt("player"), e.optInt("value", 1)));
                    break;
                case "moved":
                    queue.add(new MoveAnim(e.optInt("player"), e.optInt("token"), e.optInt("from"), e.optInt("to")));
                    break;
                case "captured":
                    queue.add(new CaptureAnim(e.optInt("player"), e.optInt("token"), e.optInt("from")));
                    break;
                case "noMoves":
                case "forfeitSixes":
                    queue.add(new Pause(450));
                    break;
                default:
                    break;
            }
        }
        if (queue.isEmpty() && cur == null) snap();
    }

    public void setHighlight(boolean on) {
        highlight = on;
    }

    /** Mark the token the player tapped; -1 clears it. */
    public void setSelected(int player, int token) {
        selPlayer = player;
        selToken = token;
    }

    private void snap() {
        if (state == null) return;
        for (int p = 0; p < state.players.size(); p++) System.arraycopy(state.players.get(p).tokens, 0, shown[p], 0, 4);
    }

    // ================================================================= outputs (any thread)

    public boolean isIdle() {
        return idle;
    }

    /** Screen position of a token's head, or null if unknown. */
    public float[] tokenScreen(int player, int token) {
        synchronized (screen) {
            int k = (player * 4 + token) * 2;
            return new float[]{screen[k], screen[k + 1]};
        }
    }

    public float[] diceScreen() {
        synchronized (screen) {
            return new float[]{diceScreen[0], diceScreen[1]};
        }
    }

    // ================================================================= animations

    private abstract static class Anim {
        long dur;

        void begin() {}

        abstract void frame(float t);

        void end() {}
    }

    private final class Pause extends Anim {
        Pause(long ms) {
            dur = ms;
        }

        @Override
        void frame(float t) {}
    }

    private final class RollAnim extends Anim {
        final int player, value;

        RollAnim(int player, int value) {
            this.player = player;
            this.value = value;
            dur = 700;
        }

        @Override
        void begin() {
            diceOwner = player;
            diceValue = value;
            diceYaw = rng.nextFloat() * 360f;
        }

        @Override
        void frame(float t) {
            rollT = t;
        }

        @Override
        void end() {
            rollT = -1;
        }
    }

    private final class MoveAnim extends Anim {
        final int player, token;
        final int[] path;

        MoveAnim(int player, int token, int from, int to) {
            this.player = player;
            this.token = token;
            if (from < 0) {
                path = new int[]{-1, 0};
                dur = 320;
            } else {
                int n = Math.max(1, to - from);
                path = new int[n + 1];
                for (int i = 0; i <= n; i++) path[i] = from + i;
                dur = 170L * n;
            }
        }

        @Override
        void begin() {
            animPlayer = player;
            animToken = token;
        }

        @Override
        void frame(float t) {
            int steps = path.length - 1;
            float f = t * steps;
            int i = Math.min(steps - 1, (int) f);
            float k = f - i;
            float[] a = world(player, path[i], token);
            float[] b = world(player, path[i + 1], token);
            animPos[0] = a[0] + (b[0] - a[0]) * k;
            animPos[2] = a[2] + (b[2] - a[2]) * k;
            animPos[1] = (float) Math.sin(Math.PI * k) * 0.45f;
        }

        @Override
        void end() {
            if (player < 4) shown[player][token] = path[path.length - 1];
            animPlayer = -1;
        }
    }

    private final class CaptureAnim extends Anim {
        final int player, token, from;

        CaptureAnim(int player, int token, int from) {
            this.player = player;
            this.token = token;
            this.from = from;
            dur = 480;
        }

        @Override
        void begin() {
            animPlayer = player;
            animToken = token;
        }

        @Override
        void frame(float t) {
            float[] a = world(player, from, token);
            float[] b = world(player, -1, token);
            float e = t * t * (3 - 2 * t);
            animPos[0] = a[0] + (b[0] - a[0]) * e;
            animPos[2] = a[2] + (b[2] - a[2]) * e;
            animPos[1] = (float) Math.sin(Math.PI * t) * 1.6f;
        }

        @Override
        void end() {
            if (player < 4) shown[player][token] = -1;
            animPlayer = -1;
        }
    }

    private float[] world(int player, int progress, int token) {
        int ci = state != null && player < state.players.size() ? state.players.get(player).ci : 0;
        float[] c = Board.cellOf(ci, progress, token);
        return new float[]{c[0] - 7f, 0f, c[1] - 7f};
    }

    private void step(long now) {
        if (cur == null && !queue.isEmpty()) {
            cur = queue.poll();
            curStart = now;
            cur.begin();
        }
        if (cur != null) {
            float t = Math.min(1f, (now - curStart) / (float) Math.max(1, cur.dur));
            cur.frame(t);
            if (t >= 1f) {
                cur.end();
                cur = null;
                if (queue.isEmpty()) snap();
            }
        }
        idle = cur == null && queue.isEmpty();
    }

    /** Rest positions, with co-located tokens fanned out and shrunk a little. */
    private void layout() {
        if (state == null) return;
        Map<String, List<int[]>> groups = new HashMap<>();
        for (int p = 0; p < state.players.size(); p++) {
            int ci = state.players.get(p).ci;
            for (int t = 0; t < 4; t++) {
                if (p == animPlayer && t == animToken) continue;
                String key = Board.locationKey(ci, shown[p][t], t);
                List<int[]> g = groups.get(key);
                if (g == null) groups.put(key, g = new ArrayList<>());
                g.add(new int[]{p, t});
            }
        }
        for (List<int[]> g : groups.values()) {
            int n = g.size();
            for (int k = 0; k < n; k++) {
                int p = g.get(k)[0], t = g.get(k)[1];
                float[] w = world(p, shown[p][t], t);
                if (n > 1) {
                    double ang = 2 * Math.PI * k / n + Math.PI / 4;
                    w[0] += (float) Math.cos(ang) * 0.2f;
                    w[2] += (float) Math.sin(ang) * 0.2f;
                }
                pos[p][t][0] = w[0];
                pos[p][t][1] = 0;
                pos[p][t][2] = w[2];
                scale[p][t] = n > 1 ? 0.78f : 1f;
            }
        }
        if (animPlayer >= 0 && animPlayer < 4) {
            System.arraycopy(animPos, 0, pos[animPlayer][animToken], 0, 3);
            scale[animPlayer][animToken] = 1.05f;
        }
    }

    // ================================================================= GL

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        GLES20.glClearColor(0x0b / 255f, 0x0e / 255f, 0x2a / 255f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        shader = new Shader();
        boardTop = Mesh.plane(7.5f);
        boardBase = Mesh.box(7.9f, 0.3f, 7.9f, false);
        pawn = Mesh.pawn();
        dice = Mesh.box(DICE_HALF, DICE_HALF, DICE_HALF, true);
        flat = Mesh.plane(0.5f);
        unitBox = Mesh.box(0.5f, 0.5f, 0.5f, false);
        texBoard = Textures.upload(Textures.board(), true);
        texDice = Textures.upload(Textures.dice(), false);
        texShadow = Textures.upload(Textures.shadow(), false);
        texRing = Textures.upload(Textures.ring(), false);
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
        GLES20.glViewport(0, 0, width, height);
    }

    private static float baseYaw(String color) {
        // Rotate so the player's own yard is at the bottom-left, nearest the camera.
        switch (color) {
            case "red": return -90f;
            case "green": return 180f;
            case "yellow": return 90f;
            default: return 0f;
        }
    }

    private void camera() {
        float aspect = width / (float) height;
        float fovy = 40f;
        // Phones (portrait): higher camera so the board reads clearly; wider screens: more cinematic.
        // In a tall area a steeper view lets the (width-limited) board also fill more height.
        double pitch = Math.toRadians(aspect < 0.7f ? 70 : aspect < 0.85f ? 64 : aspect < 1.2f ? 56 : 50);
        double yaw = Math.toRadians(baseYaw(myColor) + yawOffset);
        // Closest distance at which every corner of the board (frame included) stays on screen.
        float lo = 5f, hi = 200f;
        for (int i = 0; i < 24; i++) {
            float mid = (lo + hi) / 2;
            placeCamera(mid, fovy, aspect, pitch, yaw);
            if (boardFits()) hi = mid;
            else lo = mid;
        }
        placeCamera(hi, fovy, aspect, pitch, yaw);
    }

    private void placeCamera(float dist, float fovy, float aspect, double pitch, double yaw) {
        // Tight near/far planes keep depth precise even with a 16-bit depth buffer.
        Matrix.perspectiveM(proj, 0, fovy, aspect, Math.max(0.5f, dist - 16f), dist + 16f);
        eye[0] = (float) (Math.sin(yaw) * Math.cos(pitch) * dist);
        eye[1] = (float) (Math.sin(pitch) * dist);
        eye[2] = (float) (Math.cos(yaw) * Math.cos(pitch) * dist);
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], 0f, -0.2f, 0f, 0f, 1f, 0f);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
    }

    private static final float EDGE = 7.95f;

    private boolean boardFits() {
        for (int k = 0; k < 8; k++) {
            tmpIn[0] = (k & 1) == 0 ? -EDGE : EDGE;
            tmpIn[2] = (k & 2) == 0 ? -EDGE : EDGE;
            tmpIn[1] = (k & 4) == 0 ? 0.15f : -0.7f;
            tmpIn[3] = 1;
            Matrix.multiplyMV(tmpOut, 0, vp, 0, tmpIn, 0);
            if (tmpOut[3] <= 0) return false;
            float x = tmpOut[0] / tmpOut[3], y = tmpOut[1] / tmpOut[3];
            if (Math.abs(x) > 0.97f || Math.abs(y) > 0.95f) return false;
        }
        return true;
    }

    private void use(int tex, float r, float g, float b, float a, boolean unlit, float spec) {
        GLES20.glUniform4f(shader.uColor, r, g, b, a);
        GLES20.glUniform1f(shader.uUseTex, tex != 0 ? 1f : 0f);
        GLES20.glUniform1f(shader.uUnlit, unlit ? 1f : 0f);
        GLES20.glUniform1f(shader.uSpec, spec);
        if (tex != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            GLES20.glUniform1i(shader.uTex, 0);
        }
    }

    private void draw(Mesh m) {
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);
        GLES20.glUniformMatrix4fv(shader.uMVP, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(shader.uModel, 1, false, model, 0);
        m.draw(shader);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        long now = SystemClock.uptimeMillis();
        step(now);
        layout();
        // After a drag the board eases back to the player's own view.
        if (!dragging) {
            yawOffset *= 0.86f;
            if (Math.abs(yawOffset) < 0.05f) yawOffset = 0f;
        }
        camera();

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glUseProgram(shader.program);
        GLES20.glUniform3f(shader.uLight, 0.35f, 1f, 0.45f);
        GLES20.glUniform3f(shader.uEye, eye[0], eye[1], eye[2]);

        // Board: wooden base (its top 4 cm below the painted face, so the two never z-fight),
        // a raised wooden frame, then the painted top at y = 0.
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, 0, -0.34f, 0);
        use(0, 0.40f, 0.26f, 0.15f, 1, false, 0.08f);
        draw(boardBase);
        drawFrame();
        Matrix.setIdentityM(model, 0);
        use(texBoard, 1, 1, 1, 1, false, 0.05f);
        draw(boardTop);

        float pulse = (float) Math.sin(now / 160.0);
        List<Integer> movable = highlight && idle && state != null ? state.movableTokens() : new ArrayList<>();
        int curPlayer = state != null ? state.current : -1;

        // Decals lying on the board (soft shadows, "pick me" rings): drawn without depth testing,
        // so they can't flicker against the board surface on low-precision depth buffers.
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        if (state != null) {
            for (int p = 0; p < state.players.size(); p++) {
                for (int t = 0; t < 4; t++) {
                    float[] q = pos[p][t];
                    float s = 0.8f * scale[p][t] / (1f + q[1] * 0.6f);
                    Matrix.setIdentityM(model, 0);
                    Matrix.translateM(model, 0, q[0], 0.01f, q[2]);
                    Matrix.scaleM(model, 0, s, 1, s);
                    use(texShadow, 0, 0, 0, 0.42f, true, 0);
                    draw(flat);
                    boolean selected = p == selPlayer && t == selToken;
                    if (selected || (p == curPlayer && movable.contains(t))) {
                        float rs = selected ? 1.35f : 1.05f + 0.12f * pulse;
                        Matrix.setIdentityM(model, 0);
                        Matrix.translateM(model, 0, q[0], 0.01f, q[2]);
                        Matrix.scaleM(model, 0, rs, 1, rs);
                        use(texRing, 1f, selected ? 1f : 0.84f, selected ? 0.75f : 0.3f, 1f, true, 0);
                        draw(flat);
                    }
                }
            }
        }
        float diceLift = rollT >= 0 ? (float) Math.sin(Math.PI * rollT) * 1.4f : 0f;
        float ds = 1.3f / (1f + diceLift * 0.5f);
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, dicePos[0], 0.01f, dicePos[2]);
        Matrix.scaleM(model, 0, ds, 1, ds);
        use(texShadow, 0, 0, 0, 0.4f, true, 0);
        draw(flat);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);

        // Tokens.
        if (state != null) {
            for (int p = 0; p < state.players.size(); p++) {
                float[] rgb = RGB[state.players.get(p).ci];
                for (int t = 0; t < 4; t++) {
                    float[] q = pos[p][t];
                    float bob = p == curPlayer && movable.contains(t) ? 0.08f + 0.08f * pulse : 0f;
                    Matrix.setIdentityM(model, 0);
                    Matrix.translateM(model, 0, q[0], q[1] + bob, q[2]);
                    Matrix.scaleM(model, 0, scale[p][t], scale[p][t], scale[p][t]);
                    use(0, rgb[0], rgb[1], rgb[2], 1, false, 0.55f);
                    draw(pawn);
                }
            }
        }

        // Dice: rests in the middle of the current player's yard; tumbles while rolling.
        if (state != null) {
            if (idle) diceOwner = -1;
            int owner = diceOwner >= 0 && diceOwner < state.players.size() ? diceOwner : state.current;
            float[] o = Board.yardOrigin(state.players.get(owner).ci);
            float tx = o[0] + 2.5f - 7f, tz = o[1] + 2.5f - 7f;
            float k = 0.15f;
            dicePos[0] += (tx - dicePos[0]) * k;
            dicePos[2] += (tz - dicePos[2]) * k;
        }
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, dicePos[0], DICE_HALF + diceLift, dicePos[2]);
        Matrix.rotateM(model, 0, diceYaw, 0, 1, 0);
        if (rollT >= 0) {
            float spin = (1 - rollT) * (1 - rollT) * 1080f;
            Matrix.rotateM(model, 0, spin, 1f, 0.7f, 0.35f);
        }
        faceUp(diceValue);
        use(texDice, 1, 1, 1, 1, false, 0.35f);
        draw(dice);

        publishScreen();
    }

    /** A raised wooden rim around the playing surface (four bars of the unit box). */
    private void drawFrame() {
        float h = 0.16f, y = 0.1f - h / 2; // top edge 10 cm above the board face
        float[][] bars = {
                {0, 7.7f, 15.8f, 0.4f}, {0, -7.7f, 15.8f, 0.4f},
                {7.7f, 0, 0.4f, 15.0f}, {-7.7f, 0, 0.4f, 15.0f},
        };
        use(0, 0.33f, 0.21f, 0.12f, 1, false, 0.18f);
        for (float[] b : bars) {
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, b[0], y, b[1]);
            Matrix.scaleM(model, 0, b[2], h, b[3]);
            draw(unitBox);
        }
    }

    /** Rotate the die so `value` faces up (faces: +X=3, -X=4, +Y=1, -Y=6, +Z=2, -Z=5). */
    private void faceUp(int value) {
        switch (value) {
            case 2: Matrix.rotateM(model, 0, -90, 1, 0, 0); break;
            case 3: Matrix.rotateM(model, 0, 90, 0, 0, 1); break;
            case 4: Matrix.rotateM(model, 0, -90, 0, 0, 1); break;
            case 5: Matrix.rotateM(model, 0, 90, 1, 0, 0); break;
            case 6: Matrix.rotateM(model, 0, 180, 1, 0, 0); break;
            default: break;
        }
    }

    private final float[] tmpIn = new float[4], tmpOut = new float[4];

    private void project(float x, float y, float z, float[] out, int k) {
        tmpIn[0] = x;
        tmpIn[1] = y;
        tmpIn[2] = z;
        tmpIn[3] = 1;
        Matrix.multiplyMV(tmpOut, 0, vp, 0, tmpIn, 0);
        float w = tmpOut[3] == 0 ? 1 : tmpOut[3];
        out[k] = (tmpOut[0] / w + 1) / 2 * width;
        out[k + 1] = (1 - tmpOut[1] / w) / 2 * height;
    }

    private void publishScreen() {
        synchronized (screen) {
            if (state != null) {
                for (int p = 0; p < state.players.size(); p++) {
                    for (int t = 0; t < 4; t++) {
                        float[] q = pos[p][t];
                        project(q[0], q[1] + 0.45f, q[2], screen, (p * 4 + t) * 2);
                    }
                }
            }
            project(dicePos[0], DICE_HALF, dicePos[2], diceScreen, 0);
        }
    }
}
