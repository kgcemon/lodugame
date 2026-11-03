package com.ludo3d.game.gl;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;

import com.ludo3d.game.audio.Sfx;
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
    private BoardThemes.Theme theme = BoardThemes.CLASSIC;

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

    /** Callbacks to the screen (called on the GL thread). */
    public interface Listener {
        void onMyTokenCaptured();
    }

    public volatile Listener listener;

    /** Told when a roll starts animating (GL thread), so the HUD die can tumble in step. */
    public interface RollListener {
        void onRoll(int player, int value, long durationMs);
    }

    public volatile RollListener rollListener;

    /**
     * Classic view: the board seen straight down, filling the view, with the die drawn on the
     * player cards instead of on the board.
     */
    public volatile boolean classic = true;

    /** Camera yaw added by dragging (degrees). Written by the UI thread; eases back to 0 on release. */
    public volatile float yawOffset;
    /** True while a finger is turning the board. */
    public volatile boolean dragging;
    /**
     * Asks for another frame. The view renders on demand (saves battery): a frame that is
     * still animating requests the next one, and a still board draws nothing.
     */
    public volatile Runnable requestRender;
    /** Camera inputs of the last solved camera; it is only solved again when one changes. */
    private int camW = -1, camH = -1;
    private float camYaw = Float.NaN;
    private boolean camClassic;
    private String camColor;
    /** Token rest positions need recomputing (state changed or a token is moving). */
    private boolean layoutDirty = true;
    private final List<Integer> noMoves = new ArrayList<>();
    /** Token the player just tapped (shown with a bright ring until the server answers). */
    private int selPlayer = -1, selToken = -1;
    /** When the selected token was tapped (for its pop and flash). */
    private long selAt;

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

    /** Play on this board theme (the room's: its host's); repaints the board face if it changed. */
    public void setBoard(String id) {
        BoardThemes.Theme next = BoardThemes.get(id);
        if (next == theme) return;
        theme = next;
        if (texBoard != 0) {
            GLES20.glDeleteTextures(1, new int[]{texBoard}, 0);
            texBoard = Textures.upload(BoardThemes.bitmap(theme, 1024), true);
        }
    }

    /**
     * Show a server update. Contiguous updates (seq = previous + 1) animate their events;
     * anything else (first update, reconnect, rematch) snaps straight to the state.
     */
    public void applyUpdate(GameState next, JSONArray events, boolean contiguous) {
        boolean first = state == null;
        // Still playing an older update (a stalled frame, network bunching): finish it at
        // once and quietly, so the board and its sounds never fall behind the server.
        if (!first && contiguous && events != null && pendingMs(SystemClock.uptimeMillis()) > 300) {
            dropAnimations();
            snap();
        }
        state = next;
        selPlayer = selToken = -1;
        if (first || !contiguous || events == null) {
            dropAnimations();
            snap();
            if (next.dice > 0) diceValue = next.dice;
            return;
        }
        int me = next.indexOfColor(myColor);
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
                    queue.add(new CaptureAnim(e.optInt("player"), e.optInt("token"), e.optInt("from"), e.optInt("player") == me));
                    break;
                case "tokenHome":
                    queue.add(new Cue(Sfx.S.HOME));
                    break;
                case "extraTurn":
                    if (e.optInt("player") == me) queue.add(new Cue(Sfx.S.EXTRA));
                    break;
                case "turn":
                    if (e.optInt("player") == me) queue.add(new Cue(Sfx.S.TURN));
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

    /** Animation time still to play (the current step's rest plus everything queued). */
    private long pendingMs(long now) {
        long ms = cur != null ? Math.max(0, cur.dur - (now - curStart)) : 0;
        for (Anim a : queue) ms += a.dur;
        return ms;
    }

    /** Forget queued and running animations, and silence the dice rattle they started. */
    private void dropAnimations() {
        queue.clear();
        cur = null;
        animPlayer = -1;
        // An interrupted roll must not leave the die hanging in the air.
        rollT = -1;
        diceOwner = -1;
        idle = true;
        Sfx x = Sfx.get();
        if (x != null) x.stop(Sfx.S.DICE_SHAKE);
    }

    /** Jump to the current state with no animation (back from the background). */
    public void skipAnimations() {
        dropAnimations();
        snap();
    }

    public void setHighlight(boolean on) {
        highlight = on;
    }

    /** Mark the token the player tapped; -1 clears it. */
    public void setSelected(int player, int token) {
        selPlayer = player;
        selToken = token;
        selAt = SystemClock.uptimeMillis();
    }

    private void snap() {
        layoutDirty = true;
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

    /** A sound at this point in the animation sequence (takes no time). */
    private static final class Cue extends Anim {
        final Sfx.S sound;

        Cue(Sfx.S sound) {
            this.sound = sound;
        }

        @Override
        void begin() {
            sfx(sound, 1f, 1f);
        }

        @Override
        void frame(float t) {}
    }

    private static void sfx(Sfx.S s, float volume, float rate) {
        Sfx x = Sfx.get();
        if (x != null) x.play(s, volume, rate);
    }

    private final class RollAnim extends Anim {
        final int player, value;

        RollAnim(int player, int value) {
            this.player = player;
            this.value = value;
            // Timed to the dice recording: its hits run from 0.3 s to 0.8 s, so the die
            // comes down through them and lands just as the dice settle.
            dur = 850;
        }

        @Override
        void begin() {
            diceOwner = player;
            diceValue = value;
            diceYaw = rng.nextFloat() * 360f;
            // Started with the throw itself (not on the tap), so sound and picture stay together
            // however long the server takes; only a hint of pitch change keeps the timing.
            sfx(Sfx.S.DICE_SHAKE, 0.9f, 0.98f + rng.nextFloat() * 0.04f);
            RollListener l = rollListener;
            if (l != null) l.onRoll(player, value, (long) dur);
        }

        @Override
        void frame(float t) {
            rollT = t;
        }

        @Override
        void end() {
            // The dice recording carries its own landing and settle.
            rollT = -1;
        }
    }

    private final class MoveAnim extends Anim {
        final int player, token;
        final int[] path;
        /** Hops already heard (one tap per square landed on). */
        int landed;

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
            int done = (int) f;
            while (landed < Math.min(done, steps)) hop();
        }

        private void hop() {
            landed++;
            // Slight pitch rise along the way so long moves feel alive.
            sfx(Sfx.S.STEP, 0.8f, 0.92f + 0.03f * Math.min(landed, 6) + rng.nextFloat() * 0.04f);
        }

        @Override
        void end() {
            while (landed < path.length - 1) hop();
            if (player < 4) shown[player][token] = path[path.length - 1];
            animPlayer = -1;
        }
    }

    private final class CaptureAnim extends Anim {
        final int player, token, from;
        final boolean mine;

        CaptureAnim(int player, int token, int from, boolean mine) {
            this.player = player;
            this.token = token;
            this.from = from;
            this.mine = mine;
            dur = 520;
        }

        @Override
        void begin() {
            animPlayer = player;
            animToken = token;
            sfx(Sfx.S.CAPTURE, 1f, 1f);
            if (mine && listener != null) listener.onMyTokenCaptured();
            if (com.ludo3d.game.BuildConfig.DEBUG) {
                android.util.Log.d("LudoAnim", "capture player=" + player + " token=" + token + " from=" + from + " shownBefore=" + shown[player][token]);
            }
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
        if (cur != null || !queue.isEmpty()) layoutDirty = true;
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
                if (queue.isEmpty()) {
                    if (com.ludo3d.game.BuildConfig.DEBUG) checkAgainstServer();
                    snap();
                }
            }
        }
        idle = cur == null && queue.isEmpty();
    }

    /**
     * Debug builds: after the animations of an update have played, every token should be
     * exactly where the server says. A mismatch means an animation (e.g. a capture) went wrong.
     */
    private void checkAgainstServer() {
        int checked = 0;
        for (int p = 0; p < state.players.size(); p++) {
            for (int t = 0; t < 4; t++) {
                checked++;
                int want = state.players.get(p).tokens[t];
                if (shown[p][t] != want) {
                    android.util.Log.e("LudoAnim", "MISMATCH seq=" + state.seq + " player=" + p + " token=" + t + " shown=" + shown[p][t] + " server=" + want);
                }
            }
        }
        android.util.Log.d("LudoAnim", "checked seq=" + state.seq + " tokens=" + checked);
    }

    /** Rest positions, with co-located tokens fanned out and shrunk a little. */
    private void layout() {
        if (state == null || !layoutDirty) return;
        layoutDirty = false;
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
        GLES20.glClearColor(0x43 / 255f, 0x16 / 255f, 0xa0 / 255f, 1f); // matches the purple app background
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        shader = new Shader();
        boardTop = Mesh.plane(7.5f);
        boardBase = Mesh.box(7.9f, 0.3f, 7.9f, false);
        pawn = Mesh.pawn();
        dice = Mesh.box(DICE_HALF, DICE_HALF, DICE_HALF, true);
        flat = Mesh.plane(0.5f);
        unitBox = Mesh.box(0.5f, 0.5f, 0.5f, false);
        texBoard = Textures.upload(BoardThemes.bitmap(theme, 1024), true);
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
        float yawNow = yawOffset;
        if (width == camW && height == camH && yawNow == camYaw && classic == camClassic && myColor.equals(camColor)) return;
        camW = width;
        camH = height;
        camYaw = yawNow;
        camClassic = classic;
        camColor = myColor;
        float aspect = width / (float) height;
        float fovy = 40f;
        // Phones (portrait): higher camera so the board reads clearly; wider screens: more cinematic.
        // In a tall area a steeper view lets the (width-limited) board also fill more height.
        double pitch = Math.toRadians(classic ? 90 : aspect < 0.7f ? 70 : aspect < 0.85f ? 64 : aspect < 1.2f ? 56 : 50);
        double yaw = Math.toRadians(baseYaw(myColor) + yawNow);
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
        if (pitch > Math.toRadians(89)) {
            // Straight down: "up" on screen is the far side of the board for this yaw.
            Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], 0f, 0f, 0f, (float) -Math.sin(yaw), 0f, (float) -Math.cos(yaw));
        } else {
            Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], 0f, -0.2f, 0f, 0f, 1f, 0f);
        }
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
            float lim = classic ? 0.995f : 0.97f;
            if (Math.abs(x) > lim || Math.abs(y) > (classic ? lim : 0.95f)) return false;
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
        use(0, theme.wood[0], theme.wood[1], theme.wood[2], 1, false, 0.08f);
        draw(boardBase);
        drawFrame();
        Matrix.setIdentityM(model, 0);
        use(texBoard, 1, 1, 1, 1, false, 0.05f);
        draw(boardTop);

        float pulse = (float) Math.sin(now / 160.0);
        List<Integer> movable = highlight && idle && state != null ? state.movableTokens() : noMoves;
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
                    if (selected) {
                        // Tapped: a bright white ring plus a quick flash spreading out.
                        float age = Math.min(1f, (now - selAt) / 450f);
                        ring(q, 1.45f, 1f, 1f, 0.85f, 1f);
                        ring(q, 1.3f + 1.6f * age, 1f, 1f, 1f, 1f - age);
                    } else if (p == curPlayer && movable.contains(t)) {
                        // "Pick me": a glowing gold ring that breathes, and ripples rolling out of it.
                        float breathe = 0.5f + 0.5f * pulse;
                        // Soft gold halo under the token.
                        Matrix.setIdentityM(model, 0);
                        Matrix.translateM(model, 0, q[0], 0.01f, q[2]);
                        float hs = 1.5f + 0.25f * breathe;
                        Matrix.scaleM(model, 0, hs, 1, hs);
                        use(texShadow, 1f, 0.82f, 0.15f, 0.45f + 0.35f * breathe, true, 0);
                        draw(flat);
                        ring(q, 1.25f + 0.22f * breathe, 1f, 0.86f, 0.25f, 0.75f + 0.25f * breathe);
                        for (int k = 0; k < 2; k++) {
                            float ph = ((now + k * 450L + t * 120L) % 900L) / 900f;
                            ring(q, 1.2f + 1.3f * ph, 1f, 0.95f, 0.55f, 0.9f * (1f - ph));
                        }
                    }
                }
            }
        }
        float diceLift = rollT >= 0 ? (float) Math.sin(Math.PI * rollT) * 1.4f : 0f;
        float ds = 1.3f / (1f + diceLift * 0.5f);
        boolean boardDice = !classic;
        if (boardDice) {
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, dicePos[0], 0.01f, dicePos[2]);
            Matrix.scaleM(model, 0, ds, 1, ds);
            use(texShadow, 0, 0, 0, 0.4f, true, 0);
            draw(flat);
        }
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);

        // Tokens.
        if (state != null) {
            for (int p = 0; p < state.players.size(); p++) {
                float[] rgb = RGB[state.players.get(p).ci];
                for (int t = 0; t < 4; t++) {
                    float[] q = pos[p][t];
                    boolean pick = p == curPlayer && movable.contains(t);
                    float bob = pick ? 0.08f + 0.08f * pulse : 0f;
                    // Seen from above a hop barely shows, so movable tokens also swell and shrink,
                    // and a tapped token pops.
                    float grow = pick ? 1.08f + 0.1f * (0.5f + 0.5f * pulse) : 1f;
                    if (p == selPlayer && t == selToken) {
                        float age = Math.min(1f, (now - selAt) / 300f);
                        grow = 1.15f + 0.3f * (1f - age) * (1f - age);
                    }
                    Matrix.setIdentityM(model, 0);
                    Matrix.translateM(model, 0, q[0], q[1] + bob, q[2]);
                    float sc = scale[p][t] * grow;
                    Matrix.scaleM(model, 0, sc, sc, sc);
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
            diceTarget[0] = tx;
            diceTarget[1] = tz;
            dicePos[0] += (tx - dicePos[0]) * k;
            dicePos[2] += (tz - dicePos[2]) * k;
            if (Math.abs(tx - dicePos[0]) < 0.002f && Math.abs(tz - dicePos[2]) < 0.002f) {
                dicePos[0] = tx;
                dicePos[2] = tz;
            }
        }
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, dicePos[0], DICE_HALF + diceLift, dicePos[2]);
        Matrix.rotateM(model, 0, diceYaw, 0, 1, 0);
        if (rollT >= 0) {
            float spin = (1 - rollT) * (1 - rollT) * 1080f;
            Matrix.rotateM(model, 0, spin, 1f, 0.7f, 0.35f);
        }
        faceUp(diceValue);
        if (boardDice) {
            use(texDice, 1, 1, 1, 1, false, 0.35f);
            draw(dice);
        }

        publishScreen();

        // Keep drawing only while something on the board moves or glows.
        boolean diceSettled = state == null
                || (Math.abs(dicePos[0] - diceTarget[0]) < 0.002f && Math.abs(dicePos[2] - diceTarget[1]) < 0.002f);
        boolean moving = !idle || cur != null || !queue.isEmpty() || rollT >= 0 || dragging || yawOffset != 0f
                || !movable.isEmpty() || (selPlayer >= 0 && now - selAt < 500) || !diceSettled;
        Runnable rr = requestRender;
        if (moving && rr != null) rr.run();
    }

    /** Where the die is gliding to (x, z). */
    private final float[] diceTarget = new float[2];

    /** A flat ring decal around a token: radius scale, colour and alpha. */
    private void ring(float[] q, float rs, float r, float g, float b, float a) {
        if (a <= 0.01f) return;
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, q[0], 0.01f, q[2]);
        Matrix.scaleM(model, 0, rs, 1, rs);
        use(texRing, r, g, b, a, true, 0);
        draw(flat);
    }

    /** A raised rim (wood, or gold on royal boards) around the playing surface (four bars of the unit box). */
    private void drawFrame() {
        float h = 0.16f, y = 0.1f - h / 2; // top edge 10 cm above the board face
        float[][] bars = {
                {0, 7.7f, 15.8f, 0.4f}, {0, -7.7f, 15.8f, 0.4f},
                {7.7f, 0, 0.4f, 15.0f}, {-7.7f, 0, 0.4f, 15.0f},
        };
        use(0, theme.rim[0], theme.rim[1], theme.rim[2], 1, false, theme.rimSpec);
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
