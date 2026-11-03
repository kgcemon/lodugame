package com.ludo3d.game.ui;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.audio.Sfx;
import com.ludo3d.game.game.Board;
import com.ludo3d.game.game.GameState;
import com.ludo3d.game.game.LocalGame;
import com.ludo3d.game.game.Social;
import com.ludo3d.game.gl.ConfigChooser;
import com.ludo3d.game.gl.GameRenderer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * An online match: the 3D board plus the HUD (players, turn timer, roll button, chat,
 * emotes, result). Tap the die to roll, tap a glowing token to move it, drag sideways
 * to turn the board (double-tap to reset). Started with {@link OfflineSetup#EXTRA_NAMES}
 * it is instead an offline pass-and-play match run by {@link LocalGame} on this phone.
 */
public class GameActivity extends BaseActivity {
    private GLSurfaceView gl;
    private GameRenderer renderer;
    private GameState state;
    private int lastSeq = -1;
    private long deadline;
    private boolean busy;
    private int resultShownFor = -1;
    private long lastEmoteAt;

    /** Length of the current turn timer, for the countdown bar. */
    private long deadlineTotal;
    /** Gems for the next re-roll of my last roll (0 = no offer) and when the offer ends (0 = until I move). */
    private int rerollCost;
    private long rerollUntil;
    /** Last whole second of my turn timer that beeped, so each of the final 3 seconds beeps once. */
    private long warnedAt = -1;

    /** Player card slots at the board's corners, in screen order: bottom-left, top-left, top-right, bottom-right. */
    private final FrameLayout[] corners = new FrameLayout[4];
    /** The rows of corner cards above and below the board, and the square board area between them. */
    private LinearLayout topRow, bottomRow;
    private FrameLayout middle, boardArea;
    private int boardSize = -1;
    private TextView status, chatBtn, titleView, feeView, connView;
    /** Mic on/off for voice with friends in the room (hidden without friends). */
    private TextView micBtn;
    private LinearLayout statusBox;
    /** Turn timer drawn over the current player's avatar. */
    private TimerRing timerPie;
    /** Last value each colour rolled, so a card's die keeps showing it after the move. */
    private final java.util.Map<String, Integer> lastRolls = new java.util.HashMap<>();
    /** The roll in flight (colour, value, when it lands), so a rebuilt card re-throws it. */
    private String rollColor;
    private int rollValue;
    private long rollEndAt;
    /** My re-roll button on my card: the button, its countdown ring and the gem cost under it. */
    private TextView retryBtn, retryCostView;
    private TimerRing retryRing;
    /** Length of the current re-roll offer window, for its countdown ring. */
    private long rerollTotal;
    private View timerBar;
    private android.animation.ValueAnimator pulse;
    /** Short screens (e.g. 320x568 dp): smaller panels and controls, so the board keeps its room. */
    private boolean compact;
    private FrameLayout effects;
    private LinearLayout chatPanel, emotePanel, chatList;
    private ScrollView chatScroll;
    private android.app.Dialog resultDialog;
    /** The offline pass-and-play match, or null for an online one. */
    private LocalGame local;
    private JSONObject localUpdate;
    private long localActedAt;
    /** 2 vs 2: chat goes to my partner only while this is on. */
    private boolean teamChat;
    private TextView chatAllBtn, chatTeamBtn, chatTitle;
    /**
     * When the previous player's roll handed me the turn but they may still pay to re-roll
     * it, the server holds my turn until this time (wall clock): my dice stays asleep.
     */
    private long holdUntil;
    /** When the last update arrived (uptime): the renderer picks it up on its next frame. */
    private long updateAt;
    /** Auto-move: the update it is waiting on, and when the board settled for it. */
    private int autoSeq = -1;
    private long autoReadyAt;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            renderStatus();
            maybeAutoMove();
            renderVoice();
            LudoApp.MAIN.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String[] names = getIntent().getStringArrayExtra(OfflineSetup.EXTRA_NAMES);
        if (names != null) {
            local = new LocalGame(names);
            localUpdate = local.snapshot();
        }
        // Nothing from the lobby (search music, countdown ticks) carries on into the match.
        Sfx.get().stop(Sfx.S.SEARCH);
        Sfx.get().stop(Sfx.S.TICK);
        renderer = new GameRenderer();
        // Feel it when one of your tokens is sent home.
        renderer.listener = () -> runOnUiThread(() -> gl.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS));
        // The die tumbles on the roller's card, in step with the board animation.
        renderer.rollListener = (player, value, ms) -> runOnUiThread(() -> animateRoll(player, value, ms));
        gl = new GLSurfaceView(this);
        gl.setEGLContextClientVersion(2);
        gl.setEGLConfigChooser(new ConfigChooser());
        gl.setRenderer(renderer);
        // Frames are drawn on demand: the renderer asks for the next one only while animating.
        gl.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        renderer.requestRender = gl::requestRender;
        compact = getResources().getConfiguration().screenHeightDp < 700;
        setupTouch();
        buildHud();
        // The board keeps its size when the keyboard opens: only the chat panel moves up.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            float lift = -Math.max(0, ime.bottom - bars.bottom);
            chatPanel.setTranslationY(lift);
            emotePanel.setTranslationY(lift);
            return WindowInsetsCompat.CONSUMED;
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (chatPanel.getVisibility() == View.VISIBLE || emotePanel.getVisibility() == View.VISIBLE) closePanels();
                else confirmLeave();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        gl.onResume();
        if (local == null && app.socket.room == null) {
            finish();
            return;
        }
        String color = myColor();
        onGl(() -> renderer.setMyColor(color));
        String board = boardId();
        onGl(() -> renderer.setBoard(board));
        syncDiceLooks();
        // Animations left over from before the pause would replay old rolls and their sounds.
        onGl(renderer::skipAnimations);
        JSONObject last = local != null ? localUpdate : app.socket.lastUpdate;
        if (last != null) onUpdate(last, true);
        renderChips();
        renderChat();
        LudoApp.MAIN.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        gl.onPause();
        LudoApp.MAIN.removeCallbacks(ticker);
        Sfx.get().stopAll();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pulse != null) pulse.cancel();
    }

    // ================================================================= socket

    @Override
    public void onSocketEvent(String event, Object data) {
        super.onSocketEvent(event, data);
        // An offline match ignores the server (online rooms carry on without it).
        if (local != null) return;
        switch (event) {
            case "game:update":
                onUpdate((JSONObject) data, false);
                break;
            case "room:state":
                JSONObject room = (JSONObject) data;
                if ("lobby".equals(room.optString("status"))) {
                    // Rematch: back to the waiting room.
                    dismissResult();
                    openRoom();
                    finish();
                } else {
                    // The host may have put on another board.
                    String board = room.optString("board", null);
                    onGl(() -> renderer.setBoard(board));
                    syncDiceLooks();
                    renderChips();
                }
                break;
            case "room:closed":
                dismissResult();
                Popup.message(this, "Match closed", String.valueOf(data), Popup.ok("OK", this::finish)).setOnCancelListener(d -> finish());
                break;
            case "room:left":
                finish();
                break;
            case "match:settled":
                if (state != null && "over".equals(state.phase)) showResult(true);
                break;
            case "chat:message":
                if (!app.socket.mySeatId().equals(((JSONObject) data).optString("seatId"))) Sfx.get().play(Sfx.S.MESSAGE, 0.8f, 1f);
                renderChat();
                break;
            case "chat:history":
                renderChat();
                break;
            case "emote":
                JSONObject e = (JSONObject) data;
                showEmote(e.optString("id"), e.optString("color"));
                break;
            case com.ludo3d.game.net.GameSocket.CONNECTION:
                renderStatus();
                break;
            default:
                break;
        }
    }

    private void onUpdate(JSONObject u, boolean resume) {
        JSONObject st = u.optJSONObject("state");
        if (st == null) return;
        GameState next = GameState.parse(st);
        // An update we already have (or an older one) must never roll the board back.
        if (!resume && lastSeq >= 0 && next.seq <= lastSeq) return;
        JSONArray events = u.optJSONArray("events");
        boolean contiguous = !resume && lastSeq >= 0 && next.seq == lastSeq + 1 && events != null && events.length() > 0;
        if (resume && next.seq == lastSeq) return;
        boolean wasMine = state != null && isMyTurn(state);
        updateAt = android.os.SystemClock.uptimeMillis();
        lastSeq = next.seq;
        state = next;
        // Animated rolls reveal their value when the card die lands (animateRoll); a state we
        // jump straight to (first update, reconnect) shows its roll right away.
        if (!contiguous && "move".equals(next.phase) && next.dice > 0) lastRolls.put(next.currentPlayer().color, next.dice);
        long nextDeadline = u.isNull("deadline") ? 0 : u.optLong("deadline") - u.optLong("serverTime") + System.currentTimeMillis();
        // A new deadline (not just the same turn re-sent) restarts the countdown bar.
        if (Math.abs(nextDeadline - deadline) > 1500) deadlineTotal = Math.max(1, nextDeadline - System.currentTimeMillis());
        deadline = nextDeadline;
        // A re-roll offer for my own last roll (cost, and a deadline if the turn already passed).
        JSONObject rr = u.optJSONObject("reroll");
        if (rr != null && myColor().equals(rr.optString("color")) && !rr.isNull("nextCost")) {
            rerollCost = rr.optInt("nextCost");
            rerollUntil = rr.isNull("msLeft") ? 0 : System.currentTimeMillis() + rr.optLong("msLeft");
            rerollTotal = rr.isNull("msLeft") ? 0 : Math.max(1, rr.optLong("msLeft"));
        } else {
            rerollCost = 0;
            rerollUntil = 0;
        }
        holdUntil = rr != null && !myColor().equals(rr.optString("color")) && !rr.isNull("msLeft")
                ? System.currentTimeMillis() + rr.optLong("msLeft") : 0;
        boolean highlight = isMyTurn(next) && "move".equals(next.phase);
        onGl(() -> {
            renderer.applyUpdate(next, events, contiguous);
            renderer.setHighlight(highlight);
        });
        // The dice came to me: buzz the phone (online only; pass-and-play is one phone anyway).
        if (!wasMine && isMyTurn(next) && "roll".equals(next.phase)) {
            if (local == null) com.ludo3d.game.audio.Haptics.turn(this, app.prefs);
            else gl.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
        renderChips();
        renderStatus();
        if ("over".equals(next.phase)) {
            // Let the final hop play before the result card.
            LudoApp.MAIN.postDelayed(() -> showResult(false), 1400);
        } else {
            dismissResult();
        }
    }

    /** An online 2 vs 2 match. */
    private boolean teams() {
        return local == null && app.socket.room != null && app.socket.room.optBoolean("teams");
    }

    /** 2 vs 2 team of a colour: 0 = red + yellow, 1 = green + blue (engine.teamOf). */
    private static int teamOf(String color) {
        return Board.colorIndex(color) % 2;
    }

    /** Run on the GL thread, then draw a frame (the view only renders on demand). */
    private void onGl(Runnable r) {
        gl.queueEvent(r);
        gl.requestRender();
    }

    private boolean isMyTurn(GameState s) {
        // Offline, every seat is played on this phone.
        return s != null && !"over".equals(s.phase) && (local != null || s.currentPlayer().color.equals(myColor()));
    }

    /** Tell the 3D board which die each colour throws (each player's own dice skin). */
    private void syncDiceLooks() {
        java.util.Map<String, com.ludo3d.game.gl.DiceArt.Look> looks = new java.util.HashMap<>();
        JSONObject room = local != null ? null : app.socket.room;
        JSONArray seats = room != null ? room.optJSONArray("seats") : null;
        for (int i = 0; seats != null && i < seats.length(); i++) {
            JSONObject s = seats.optJSONObject(i);
            if (s != null) looks.put(s.optString("color"), DiceView.Skin.byId(s.optString("diceSkin", null)).look());
        }
        renderer.setDiceLooks(looks);
    }

    /** The board this match is played on: the host's online, my own offline. */
    private String boardId() {
        if (local != null) return app.prefs.getString(ShopActivity.PREF_BOARD, null);
        JSONObject room = app.socket.room;
        return room != null ? room.optString("board", null) : null;
    }

    /**
     * The colour whose yard sits bottom-left. Online it is my seat; offline the board stays
     * put with the first player's yard there.
     */
    private String myColor() {
        return local != null ? LocalGame.colorsForCount(local.names().length)[0] : app.socket.myColor();
    }

    /** Whose card gets the roll controls: my seat online, the player whose turn it is offline. */
    private boolean isMine(String color) {
        if (local == null) return color.equals(app.socket.myColor());
        return state != null && !"over".equals(state.phase) && state.currentPlayer().color.equals(color);
    }

    /** Apply an offline action's update as if the server had sent it. */
    private void localUpdate(JSONObject u) {
        if (u == null) return;
        localUpdate = u;
        localActedAt = android.os.SystemClock.uptimeMillis();
        onUpdate(u, false);
    }

    /** A fresh offline match with the same players. */
    private void localRematch() {
        local = new LocalGame(local.names());
        resultShownFor = -1;
        lastRolls.clear();
        lastSeq = -1;
        localUpdate(local.snapshot());
    }

    // ================================================================= input

    /**
     * Touch on the board: a short tap rolls (on the die) or moves (on a glowing token); a
     * clear sideways drag turns the board, which eases back when released. A drag never
     * counts as a tap, and a second finger cancels the gesture.
     */
    private void setupTouch() {
        final float[] down = new float[3];
        final boolean[] dragging = {false};
        final boolean[] cancelled = {false};
        final float slop = Ui.dp(this, 16);
        gl.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = ev.getX();
                    down[1] = ev.getY();
                    down[2] = ev.getX();
                    dragging[0] = false;
                    cancelled[0] = false;
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    cancelled[0] = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getX() - down[0], dy = ev.getY() - down[1];
                    if (!renderer.classic && !dragging[0] && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                        dragging[0] = true;
                        renderer.dragging = true;
                    }
                    if (!dragging[0] && Math.hypot(dx, dy) > slop) cancelled[0] = true;
                    if (dragging[0]) {
                        gl.requestRender();
                        renderer.yawOffset = Math.max(-75f, Math.min(75f, renderer.yawOffset + (ev.getX() - down[2]) * 0.25f));
                        down[2] = ev.getX();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    renderer.dragging = false;
                    if (!dragging[0] && !cancelled[0]) tap(ev.getX(), ev.getY());
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    renderer.dragging = false;
                    return true;
                default:
                    return true;
            }
        });
    }

    private void tap(float x, float y) {
        if (!isMyTurn(state) || busy) return;
        if ("roll".equals(state.phase)) {
            // Classic view: roll with the die on my card.
            if (renderer.classic) return;
            float[] d = renderer.diceScreen();
            if (dist(d, x, y) < Ui.dp(this, 70)) roll();
            return;
        }
        if (!"move".equals(state.phase) || !renderer.isIdle()) return;
        List<Integer> movable = state.movableTokens();
        int best = -1;
        float bestD = Ui.dp(this, 52);
        for (int t : movable) {
            float dd = dist(renderer.tokenScreen(state.current, t), x, y);
            if (dd < bestD) {
                bestD = dd;
                best = t;
            }
        }
        if (best < 0) return;
        int player = state.current, token = best;
        onGl(() -> renderer.setSelected(player, token));
        gl.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        move(token);
    }

    /**
     * The token to play when this roll leaves only one real choice (one token, or several on
     * the same square); -1 when there is nothing to move or a real choice to make.
     */
    private int onlyMove() {
        if (state == null || !"move".equals(state.phase)) return -1;
        int[] t = state.currentPlayer().tokens;
        int only = -1;
        for (int k : state.movableTokens()) {
            if (only < 0) only = k;
            else if (t[k] != t[only]) return -1;
        }
        return only;
    }

    /**
     * Offline: only one real choice (one token, or several on the same square) is played by
     * itself once the roll has landed. Online the server does this, so it happens exactly once.
     */
    private void maybeAutoMove() {
        if (local == null || state == null || busy || !isMyTurn(state) || !"move".equals(state.phase) || !renderer.isIdle()) {
            autoSeq = -1;
            return;
        }
        int only = onlyMove();
        if (only < 0) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (autoSeq != state.seq) {
            autoSeq = state.seq;
            autoReadyAt = now;
            return;
        }
        if (now - autoReadyAt < 450) return;
        autoSeq = -1;
        int player = state.current, token = only;
        onGl(() -> renderer.setSelected(player, token));
        move(token);
    }

    private static float dist(float[] p, float x, float y) {
        float dx = p[0] - x, dy = p[1] - y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** True while my last roll can be re-rolled with gems. */
    private boolean canReroll() {
        return rerollCost > 0 && (rerollUntil == 0 || System.currentTimeMillis() < rerollUntil);
    }

    /** Pay gems to roll again (the server charges 5, 15, 45 per match and rolls a new number). */
    private void reroll() {
        if (busy) return;
        busy = true;
        renderStatus();
        app.socket.emit("game:reroll", new JSONObject(), res -> {
            busy = false;
            if (res.optBoolean("ok")) {
                app.wallet.remove("gems");
                try {
                    app.wallet.put("gems", res.optLong("gems"));
                } catch (Exception ignored) {
                    // not reachable
                }
            } else if ("NOT_ENOUGH_GEMS".equals(res.optString("code"))) {
                Sfx.get().play(Sfx.S.ERROR);
                Popup.message(this, "Not enough diamonds", "💎\nA re-roll costs " + rerollCost + " diamonds. Buy diamonds in the shop.",
                        Popup.plain("Close", null),
                        new Popup.Btn("Diamond shop", "gold", () -> startActivity(new android.content.Intent(this, GemShopActivity.class))));
            } else {
                Sfx.get().play(Sfx.S.ERROR);
                LudoApp.toast(res.optString("error", "Can't re-roll now"));
            }
            renderStatus();
        });
    }

    /** The previous player may still re-roll the roll that handed me the turn. */
    private boolean held() {
        return local == null && System.currentTimeMillis() < holdUntil;
    }

    /**
     * My dice may be rolled right now: my turn, roll phase, no hold for a re-roll, and the
     * board has finished showing the last action (so I never roll while the previous roll
     * is still tumbling).
     */
    private boolean canRollNow() {
        return isMyTurn(state) && "roll".equals(state.phase) && !held() && boardSettled();
    }

    /** The board has caught up with the latest update (its animations have played). */
    private boolean boardSettled() {
        return renderer.isIdle() && android.os.SystemClock.uptimeMillis() - updateAt >= 300;
    }

    /** A normal roll (re-rolls with gems go through the ↻ button on my card). */
    private void roll() {
        if (busy || !canRollNow()) return;
        if (local != null) {
            // Let the last move finish before the next player rolls (the renderer only
            // notices a new update on its next frame, hence the short grace period too).
            if (!renderer.isIdle() || android.os.SystemClock.uptimeMillis() - localActedAt < 400) return;
            localUpdate(local.roll());
            return;
        }
        busy = true;
        renderStatus();
        app.socket.emit("game:roll", PlayFlow.obj("seq", state.seq), res -> {
            busy = false;
            if (!res.optBoolean("ok")) {
                Sfx.get().play(Sfx.S.ERROR);
                LudoApp.toast(res.optString("error", "Can't roll now"));
            }
            renderStatus();
        });
    }

    private void move(int token) {
        if (local != null) {
            localUpdate(local.move(token));
            return;
        }
        busy = true;
        app.socket.emit("game:move", PlayFlow.obj("token", token, "seq", state.seq), res -> {
            busy = false;
            if (!res.optBoolean("ok")) {
                onGl(() -> renderer.setSelected(-1, -1));
                Sfx.get().play(Sfx.S.ERROR);
                LudoApp.toast(res.optString("error", "Can't move that token"));
            }
            renderStatus();
        });
    }

    // ================================================================= HUD

    /**
     * Portrait layout: top bar + player panels, the board in the middle (it gets all the
     * remaining height and never moves), bottom status + controls. Chat, emotes and emote
     * effects float above the board.
     */
    private void buildHud() {
        int pad = Ui.dp(this, 12);
        LinearLayout column = Ui.vbox(this);

        // ---- top: leave, match info, connection
        LinearLayout top = Ui.vbox(this);
        top.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 4));
        LinearLayout bar = Ui.hbox(this);
        TextView leave = Ui.text(this, "✕", 18, Ui.INK);
        leave.setGravity(Gravity.CENTER);
        leave.setPadding(0, 0, 0, Fx.bevel(leave, Fx.RED, 20, 3, true));
        leave.setContentDescription("Leave match");
        leave.setOnClickListener(v -> confirmLeave());
        bar.addView(leave, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        LinearLayout info = Ui.vbox(this);
        info.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 8), 0);
        titleView = Ui.bold(Ui.text(this, "", 15, Ui.INK));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(titleView);
        feeView = Ui.text(this, "", 12, Ui.GOLD);
        feeView.setSingleLine(true);
        info.addView(feeView);
        bar.addView(info, Ui.weight(1));
        connView = Ui.text(this, "", 12, Ui.MUTED);
        connView.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5));
        connView.setBackground(Ui.round(0x664c1d95, Ui.dp(this, 999)));
        bar.addView(connView);
        TextView mute = Ui.text(this, Sfx.get().isEnabled() ? "🔊" : "🔇", 18, Ui.INK);
        mute.setGravity(Gravity.CENTER);
        mute.setContentDescription("Sound on or off");
        mute.setPadding(0, 0, 0, Fx.bevel(mute, Fx.PURPLE, 20, 3, true));
        mute.setOnClickListener(v -> {
            Sfx.get().setEnabled(!Sfx.get().isEnabled());
            mute.setText(Sfx.get().isEnabled() ? "🔊" : "🔇");
            Sfx.get().play(Sfx.S.CLICK);
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
        mlp.leftMargin = Ui.dp(this, 8);
        bar.addView(mute, mlp);
        top.addView(bar);
        column.addView(top, Ui.lpMatchWrap());

        // ---- middle: a square board with the player cards hugging its four corners, centred
        // in the space between the top bar and the controls (see fitBoard).
        middle = new FrameLayout(this);
        LinearLayout stack = Ui.vbox(this);
        stack.setGravity(Gravity.CENTER_HORIZONTAL);
        topRow = cornerRow(1, 2);
        bottomRow = cornerRow(0, 3);
        boardArea = new FrameLayout(this);
        boardArea.addView(gl, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stack.addView(topRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        stack.addView(boardArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 300)));
        stack.addView(bottomRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        middle.addView(stack, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        column.addView(middle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        View.OnLayoutChangeListener refit = (v, l, t, r, b, ol, ot, or, ob) -> fitBoard();
        middle.addOnLayoutChangeListener(refit);
        topRow.addOnLayoutChangeListener(refit);
        bottomRow.addOnLayoutChangeListener(refit);

        // ---- bottom: status + timer (doubles as the gem re-roll button), chat / reactions / emotes
        LinearLayout bottom = Ui.vbox(this);
        bottom.setPadding(pad, Ui.dp(this, 4), pad, Ui.dp(this, 10));
        statusBox = Ui.vbox(this);
        statusBox.setBackground(Ui.round(0xcc2e1065, Ui.dp(this, 16)));
        statusBox.setPadding(Ui.dp(this, 14), Ui.dp(this, compact ? 5 : 8), Ui.dp(this, 14), Ui.dp(this, compact ? 5 : 8));
        status = Ui.bold(Ui.text(this, "", 15, Ui.INK));
        status.setGravity(Gravity.CENTER);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        statusBox.addView(status, Ui.lpMatchWrap());
        FrameLayout track = new FrameLayout(this);
        track.setBackground(Ui.round(0x22ffffff, Ui.dp(this, 2)));
        timerBar = new View(this);
        timerBar.setBackground(Ui.round(Ui.GOLD, Ui.dp(this, 2)));
        track.addView(timerBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        timerBar.setPivotX(0);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 4));
        tlp.topMargin = Ui.dp(this, 6);
        statusBox.addView(track, tlp);
        // Tapping the status re-rolls while a gem retry is offered; after "View board" it brings
        // the result card back (rematch / leave).
        statusBox.setOnClickListener(v -> {
            if (state != null && "over".equals(state.phase)) showResult(true);
            else if (canReroll() && !busy) {
                pressPop(v);
                reroll();
            }
        });
        Ui.margins(statusBox, this, 0, 4, 0, 6);
        bottom.addView(statusBox, Ui.lpMatchWrap());

        LinearLayout controls = Ui.hbox(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        chatBtn = roundIcon("💬", "Chat", v -> openChat());
        controls.addView(chatBtn, new LinearLayout.LayoutParams(Ui.dp(this, ctl()), Ui.dp(this, ctl())));
        if (local != null) chatBtn.setVisibility(View.GONE);
        micBtn = roundIcon("🔇", "Microphone", v -> {
            pressPop(v);
            toggleMic();
        });
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(Ui.dp(this, ctl()), Ui.dp(this, ctl()));
        micLp.leftMargin = Ui.dp(this, 6);
        controls.addView(micBtn, micLp);
        micBtn.setVisibility(View.GONE);
        LinearLayout reactions = Ui.hbox(this);
        reactions.setGravity(Gravity.CENTER);
        for (String id : Social.INSTANT_REACTIONS) {
            TextView r = Ui.text(this, Social.emoji(id), 22, Ui.INK);
            r.setGravity(Gravity.CENTER);
            r.setContentDescription("Send " + id);
            r.setOnClickListener(v -> {
                pressPop(v);
                sendEmote(id);
            });
            reactions.addView(r, new LinearLayout.LayoutParams(0, Ui.dp(this, ctl()), 1));
        }
        controls.addView(reactions, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        controls.addView(roundIcon("😀", "Emotes", v -> openEmotes()), new LinearLayout.LayoutParams(Ui.dp(this, ctl()), Ui.dp(this, ctl())));
        bottom.addView(controls, Ui.lpMatchWrap());
        column.addView(bottom, Ui.lpMatchWrap());

        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        effects = new FrameLayout(this);
        effects.setClickable(false);
        root.addView(effects, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildChatPanel();
        buildEmotePanel();
    }

    private int ctl() {
        return compact ? 50 : 58;
    }

    private int avSize() {
        return compact ? 46 : 56;
    }

    /**
     * Size the board as the largest square that fits between the two card rows, and make
     * the rows as wide as the board so the cards sit right at its corners.
     */
    private void fitBoard() {
        int w = middle.getWidth(), h = middle.getHeight();
        if (w == 0 || h == 0) return;
        int size = Math.max(Ui.dp(this, 160), Math.min(w, h - topRow.getHeight() - bottomRow.getHeight()));
        int rowW = Math.min(w, Math.max(size, Ui.dp(this, compact ? 290 : 320)));
        if (size == boardSize && topRow.getLayoutParams().width == rowW) return;
        boardSize = size;
        LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) boardArea.getLayoutParams();
        blp.width = size;
        blp.height = size;
        topRow.getLayoutParams().width = rowW;
        bottomRow.getLayoutParams().width = rowW;
        middle.post(() -> {
            boardArea.requestLayout();
            topRow.requestLayout();
            bottomRow.requestLayout();
        });
    }

    /** A row holding two corner slots, one at each corner of the board. */
    private LinearLayout cornerRow(int left, int right) {
        LinearLayout row = Ui.hbox(this);
        row.setPadding(Ui.dp(this, 4), Ui.dp(this, 3), Ui.dp(this, 4), Ui.dp(this, 3));
        corners[left] = new FrameLayout(this);
        corners[right] = new FrameLayout(this);
        row.addView(corners[left], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(corners[right], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    /**
     * Which screen corner a colour's yard sits in. The board is turned so my yard is bottom-left;
     * the others follow clockwise (red, green, yellow, blue order on the board).
     */
    private int cornerOf(String color) {
        int me = Board.colorIndex(myColor());
        return (Board.colorIndex(color) - me + 4) % 4;
    }

    private TextView roundIcon(String emoji, String label, View.OnClickListener onClick) {
        TextView t = Ui.text(this, emoji, 22, Ui.INK);
        t.setGravity(Gravity.CENTER);
        t.setContentDescription(label);
        int depth = Fx.bevel(t, Fx.PURPLE, 29, 4, true);
        t.setPadding(0, 0, 0, depth);
        t.setOnClickListener(onClick);
        return t;
    }

    @Override
    protected void onVoiceChanged() {
        renderChips();
        renderVoice();
    }

    /** The mic button, and a pulse on whoever is talking. */
    private void renderVoice() {
        if (micBtn == null) return;
        com.ludo3d.game.audio.VoiceChat voice = com.ludo3d.game.audio.VoiceChat.get();
        boolean show = local == null && voice.available();
        micBtn.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        String label = voice.micOn() ? "🎙" : "🔇";
        if (!label.contentEquals(micBtn.getText())) {
            micBtn.setText(label);
            int depth = Fx.bevel(micBtn, voice.micOn() ? Fx.GREEN : Fx.PURPLE, 29, 4, true);
            micBtn.setPadding(0, 0, 0, depth);
        }
        float me = voice.meSpeaking() ? 1.12f : 1f;
        micBtn.animate().scaleX(me).scaleY(me).setDuration(120).start();
        JSONObject room = app.socket.room;
        JSONArray seats = room != null ? room.optJSONArray("seats") : null;
        String mine = app.socket.mySeatId();
        for (int i = 0; seats != null && i < seats.length(); i++) {
            JSONObject s = seats.optJSONObject(i);
            if (s == null) continue;
            String id = s.optString("id");
            View b = root.findViewWithTag("voice:" + id);
            if (b == null) continue;
            boolean talking = id.equals(mine) ? voice.meSpeaking() : voice.speaking(id);
            float k = talking ? 1.3f : 1f;
            b.animate().scaleX(k).scaleY(k).setDuration(120).start();
        }
    }

    /** Small press feedback for buttons. */
    private static void pressPop(View v) {
        v.animate().cancel();
        v.setScaleX(0.92f);
        v.setScaleY(0.92f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
    }

    /** Player cards, each in the screen corner next to that player's yard. */
    private void renderChips() {
        JSONObject room = local != null ? null : app.socket.room;
        if (local != null) {
            titleView.setText("Offline match");
            feeView.setText("Pass the phone — no coins at stake");
        } else if (room != null) {
            JSONObject mode = room.optJSONObject("mode");
            long fee = room.optLong("entryFee");
            titleView.setText((mode != null ? mode.optString("name") : "Ludo") + (room.optBoolean("teams") ? " · 2 vs 2" : ""));
            feeView.setText(fee > 0 ? "Entry " + Ui.coins(fee) + "  ·  Winner " + Ui.coins(Math.round(fee * room.optDouble("payoutMultiplier", 2)))
                    : "Free room — no coins at stake");
        }
        if (pulse != null) pulse.cancel();
        for (FrameLayout c : corners) c.removeAllViews();
        timerPie = null;
        retryBtn = null;
        retryCostView = null;
        retryRing = null;
        if (state == null) return;
        JSONArray seats = room != null ? room.optJSONArray("seats") : null;
        for (int i = 0; i < state.players.size(); i++) {
            GameState.Player p = state.players.get(i);
            int corner = cornerOf(p.color);
            boolean right = corner >= 2;
            boolean top = corner == 1 || corner == 2;
            View card = playerCard(p, i, seatFor(seats, p.color), right, top);
            corners[corner].addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, right ? Gravity.END : Gravity.START));
        }
        // Cards rebuilt mid-throw (a new update, chat, presence): the old die was torn down
        // with its card, so keep the throw going on the new one.
        long left = rollEndAt - System.currentTimeMillis();
        if (rollColor != null && left > 150) {
            View v = root.findViewWithTag("dice:" + rollColor);
            if (v instanceof DiceView) ((DiceView) v).roll(rollValue, left);
        }
        renderStatus();
    }

    /**
     * One corner card, mirrored on the right-hand side: the round avatar (turn timer sweeping
     * over it), the dice box pointing at it and, on my card, the gem re-roll button. The name
     * sits on the side away from the board; my coins and gems go under my card. Tap my dice
     * box to roll, any avatar for that player's profile.
     */
    private View playerCard(GameState.Player p, int idx, JSONObject seat, boolean right, boolean top) {
        boolean current = idx == state.current && !"over".equals(state.phase);
        boolean bot = "ai".equals(p.kind);
        boolean online = bot || seat == null || seat.optBoolean("connected", true);
        boolean mine = isMine(p.color);
        int col = Board.colorArgb(p.color);
        int size = avSize();

        LinearLayout wrap = Ui.vbox(this);
        wrap.setGravity(right ? Gravity.END : Gravity.START);
        wrap.setTag("chip:" + p.color);

        boolean partner = teams() && !p.color.equals(app.socket.myColor()) && teamOf(p.color) == teamOf(app.socket.myColor());
        TextView name = Ui.bold(Ui.text(this, (p.rank == 1 ? "🏆 " : p.rank > 1 ? "#" + p.rank + " " : "") + (partner ? "🤝 " : "") + (seat != null && !bot ? Flags.of(seat) + " " : "") + p.name, compact ? 11 : 12, Ui.INK));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMaxWidth(Ui.dp(this, size * 2.6f));
        name.setShadowLayer(4, 0, 1, 0xcc000000);
        name.setPadding(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        if (local == null) name.setOnClickListener(v -> PlayerProfile.show(this, p.name, p.color, seat, bot, mine, online));

        LinearLayout row = Ui.hbox(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // Round avatar in a ring of the player's colour (gold while it's their turn).
        FrameLayout av = new FrameLayout(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(col);
        ring.setStroke(Ui.dp(this, current ? 3 : 2), current ? Ui.GOLD : Fx.blend(col, Color.WHITE, 0.35f));
        av.setBackground(ring);
        int inner = size - 8;
        AvatarView a = bot ? Ui.avatarColor(this, "🤖", Fx.blend(col, Color.BLACK, 0.25f), inner)
                : seat != null ? Ui.avatar(this, seat, p.name, inner) : Ui.avatarColor(this, p.name, col, inner);
        // A profile frame burns round the ring, so it gets the whole card corner.
        boolean framed = seat != null && !seat.isNull("frame") && !seat.optString("frame").isEmpty();
        int avSize = framed ? size : inner;
        av.addView(a, new FrameLayout.LayoutParams(Ui.dp(this, avSize), Ui.dp(this, avSize), Gravity.CENTER));
        if (current) {
            // Turn timer: a green sweep over the avatar that shrinks as time runs out.
            timerPie = new TimerRing(this, true, 0x9922c55e);
            av.addView(timerPie, new FrameLayout.LayoutParams(Ui.dp(this, inner), Ui.dp(this, inner), Gravity.CENTER));
        }
        View dot = new View(this);
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(online ? Ui.SUCCESS : 0xff8a8fb0);
        dg.setStroke(Ui.dp(this, 2), 0xff2e1065);
        dot.setBackground(dg);
        av.addView(dot, new FrameLayout.LayoutParams(Ui.dp(this, 12), Ui.dp(this, 12), Gravity.BOTTOM | (right ? Gravity.START : Gravity.END)));
        // Level from the public profile (fetched once, then the cards redraw); bots say BOT.
        String pid = seat != null && !seat.isNull("publicId") ? seat.optString("publicId", null) : null;
        JSONObject prof = PlayerProfile.cached(pid);
        JSONObject pstats = prof != null ? prof.optJSONObject("stats") : null;
        if (bot || pstats != null) {
            TextView lvl = PlayerProfile.levelBadge(this, bot ? "BOT" : "Lv " + PlayerProfile.level(pstats), compact ? 8 : 9);
            av.addView(lvl, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | (right ? Gravity.END : Gravity.START)));
        } else if (pid != null) {
            PlayerProfile.load(this, pid, false, this::renderChips);
        }
        // Voice: friends get a speaker badge (tap to mute them); my own shows my mic.
        if (local == null && seat != null && !bot) {
            com.ludo3d.game.audio.VoiceChat voice = com.ludo3d.game.audio.VoiceChat.get();
            boolean friend = !mine && voice.isFriend(seat);
            if (friend || (mine && voice.micOn())) {
                String vpid = seat.optString("publicId");
                TextView vb = Ui.text(this, mine ? "🎙" : voice.isMuted(vpid) ? "🔇" : "🔊", compact ? 10 : 11, Color.WHITE);
                vb.setGravity(Gravity.CENTER);
                vb.setTag("voice:" + seat.optString("id"));
                GradientDrawable vbg = new GradientDrawable();
                vbg.setShape(GradientDrawable.OVAL);
                vbg.setColor(0xee1a0640);
                vbg.setStroke(Ui.dp(this, 2), voice.isMuted(vpid) ? 0xffe53945 : 0xff22c55e);
                vb.setBackground(vbg);
                vb.setContentDescription(mine ? "Your microphone" : (voice.isMuted(vpid) ? "Unmute " : "Mute ") + p.name);
                if (friend) {
                    vb.setOnClickListener(v -> {
                        pressPop(v);
                        voice.toggleMute(vpid);
                        LudoApp.toast(voice.isMuted(vpid) ? "🔇 " + p.name + " muted" : "🔊 You can hear " + p.name);
                    });
                }
                av.addView(vb, new FrameLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22), Gravity.BOTTOM | (right ? Gravity.END : Gravity.START)));
            }
        }
        av.setContentDescription("Profile of " + p.name);
        if (local == null) {
            av.setOnClickListener(v -> {
                pressPop(v);
                PlayerProfile.show(this, p.name, p.color, seat, bot, mine, online);
            });
        }

        // Dice box, pointing at the avatar.
        // Not gated on `busy`: the next turn's update can arrive before the server acks my
        // move, and the arrow must still show. The ticker re-checks it (see renderStatus).
        boolean rollReady = mine && canRollNow();
        DiceView dice = new DiceView(this, !right);
        dice.setTag("dice:" + p.color);
        // Each player's own dice skin (offline matches use the classic one).
        dice.setSkin(DiceView.Skin.byId(seat != null ? seat.optString("diceSkin") : null));
        Integer last = lastRolls.get(p.color);
        dice.set(last != null ? last : 1, current, rollReady);
        if (mine) {
            dice.setContentDescription("Roll the dice");
            dice.setOnClickListener(v -> {
                if (canRollNow()) roll();
            });
        }
        int box = Ui.dp(this, size * 0.9f);

        // My gem re-roll button with its countdown ring and cost.
        LinearLayout retry = null;
        if (mine && local == null) {
            retry = Ui.vbox(this);
            retry.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout rb = new FrameLayout(this);
            int rs = Ui.dp(this, compact ? 30 : 34);
            retryBtn = Ui.bold(Ui.text(this, "↻", compact ? 17 : 19, 0x88ffffff));
            retryBtn.setGravity(Gravity.CENTER);
            retryBtn.setIncludeFontPadding(false);
            retryBtn.setContentDescription("Re-roll with diamonds");
            rb.addView(retryBtn, new FrameLayout.LayoutParams(rs - Ui.dp(this, 6), rs - Ui.dp(this, 6), Gravity.CENTER));
            retryRing = new TimerRing(this, false, Ui.GOLD);
            rb.addView(retryRing, new FrameLayout.LayoutParams(rs, rs, Gravity.CENTER));
            rb.setOnClickListener(v -> {
                if (canReroll() && !busy) {
                    pressPop(v);
                    reroll();
                } else {
                    LudoApp.toast("Not happy with a roll? Re-roll it with diamonds right after rolling");
                }
            });
            retry.addView(rb, new LinearLayout.LayoutParams(rs, rs));
            retryCostView = Ui.bold(Ui.text(this, "", 10, 0xff7fd6ff));
            retryCostView.setGravity(Gravity.CENTER);
            retry.addView(retryCostView, Ui.lpMatchWrap());
        }

        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(Ui.dp(this, size), Ui.dp(this, size));
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams((int) (box * 1.18f), box);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (right) {
            dLp.rightMargin = Ui.dp(this, 2);
            rLp.rightMargin = Ui.dp(this, 6);
            if (retry != null) row.addView(retry, rLp);
            row.addView(dice, dLp);
            row.addView(av, avLp);
        } else {
            dLp.leftMargin = Ui.dp(this, 2);
            rLp.leftMargin = Ui.dp(this, 6);
            row.addView(av, avLp);
            row.addView(dice, dLp);
            if (retry != null) row.addView(retry, rLp);
        }

        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (top) {
            wrap.addView(row, wrapLp);
            wrap.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            wrap.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            wrap.addView(row, wrapLp);
        }

        // Under my card: my coins and gems; others show bot / offline.
        String info = local != null ? "" : mine ? "🪙 " + Ui.fmt(app.balance()) + "   💎 " + Ui.fmt(app.wallet.optLong("gems"))
                : bot ? "" : online ? "" : "● Offline";
        if (!info.isEmpty()) {
            TextView t = Ui.bold(Ui.text(this, info, compact ? 11 : 12, mine ? Ui.GOLD : Ui.MUTED));
            t.setShadowLayer(3, 0, 1, 0xaa000000);
            t.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), 0);
            wrap.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return wrap;
    }

    /** A roll started on the board: tumble the roller's card die to the same value. */
    private void animateRoll(int player, int value, long ms) {
        if (state == null || player < 0 || player >= state.players.size()) return;
        String color = state.players.get(player).color;
        View v = root.findViewWithTag("dice:" + color);
        lastRolls.put(color, value);
        rollColor = color;
        rollValue = value;
        rollEndAt = System.currentTimeMillis() + ms;
        if (v instanceof DiceView) ((DiceView) v).roll(value, ms);
    }

    private static JSONObject seatFor(JSONArray seats, String color) {
        for (int i = 0; seats != null && i < seats.length(); i++) {
            JSONObject s = seats.optJSONObject(i);
            if (s != null && color.equals(s.optString("color"))) return s;
        }
        return null;
    }

    private void renderStatus() {
        if (status == null) return;
        String text;
        boolean online = local != null || "online".equals(app.socket.connection);
        connView.setText(local != null ? "● Offline" : online ? "● Online" : "● Reconnecting");
        connView.setTextColor(local != null ? Ui.MUTED : online ? Ui.SUCCESS : Ui.GOLD);
        long left = -1;
        if (!online) {
            text = "Reconnecting…";
        } else if (state == null) {
            text = "Waiting for the match…";
        } else if ("over".equals(state.phase)) {
            text = "Match over — tap for the result";
        } else {
            GameState.Player cur = state.currentPlayer();
            left = deadline > 0 ? Math.max(0, (deadline - System.currentTimeMillis() + 999) / 1000) : -1;
            if (local != null) {
                text = "roll".equals(state.phase) ? cur.name + "'s turn — tap your dice!"
                        : cur.name + " rolled " + state.dice + " — tap a glowing token";
            } else if (isMyTurn(state)) {
                if ("roll".equals(state.phase)) {
                    text = busy ? "Rolling…" : held() ? "Your turn next — wait a moment…" : "Your turn — tap your dice!";
                } else {
                    text = "You rolled " + state.dice + " — tap a glowing token";
                }
            } else {
                text = cur.name + ("roll".equals(state.phase) ? " is rolling…" : " rolled " + state.dice);
            }
        }
        if (left >= 0) text += "  ⏱ " + left + "s";
        // Warning beep in the last 3 seconds of my own turn — not when the server is about
        // to play my only possible move for me (nothing for me to hurry up and do).
        if (online && isMyTurn(state) && left >= 1 && left <= 3 && onlyMove() < 0) {
            if (left != warnedAt) {
                warnedAt = left;
                Sfx.get().play(Sfx.S.WARNING);
                gl.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
        } else {
            warnedAt = -1;
        }
        if (!text.contentEquals(status.getText())) status.setText(text);
        float frac = deadline > 0 && deadlineTotal > 0 ? Math.max(0f, Math.min(1f, (deadline - System.currentTimeMillis()) / (float) deadlineTotal)) : 0f;
        timerBar.setScaleX(frac);
        // Runs 4x a second: only build a new drawable when the bar changes colour.
        int barColor = frac < 0.25f ? Ui.DANGER : Ui.GOLD;
        if (!Integer.valueOf(barColor).equals(timerBar.getTag())) {
            timerBar.setTag(barColor);
            timerBar.setBackground(Ui.round(barColor, Ui.dp(this, 2)));
        }
        if (timerPie != null) timerPie.setFraction(frac, frac < 0.25f ? 0x99e53945 : 0x9922c55e);
        // Every time it's my turn to roll, my die glows and the arrow points at it.
        View myDice = root.findViewWithTag("dice:" + (local != null && state != null ? state.currentPlayer().color : app.socket.myColor()));
        if (myDice instanceof DiceView) {
            ((DiceView) myDice).setReady(online && canRollNow() && !busy);
        }
        // My last roll can be re-rolled with gems: the ↻ button on my card lights up with a countdown.
        boolean retry = online && state != null && !"over".equals(state.phase) && canReroll() && !busy;
        if (retryBtn != null) {
            if (retry) {
                if (!Boolean.TRUE.equals(retryBtn.getTag())) {
                    retryBtn.setTag(Boolean.TRUE);
                    GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xffffe27a, 0xfff5b400});
                    g.setShape(GradientDrawable.OVAL);
                    g.setStroke(Ui.dp(this, 1), 0xffa66f00);
                    retryBtn.setBackground(g);
                    retryBtn.setTextColor(0xff3b1d00);
                }
                retryCostView.setText("💎" + rerollCost);
                float rf = rerollUntil > 0 && rerollTotal > 0 ? (rerollUntil - System.currentTimeMillis()) / (float) rerollTotal : 1f;
                retryRing.setFraction(rf, Ui.GOLD);
                // Make the offer impossible to miss: the button beats while it lasts.
                View rb = (View) retryBtn.getParent();
                if (!(rb.getTag() instanceof android.animation.Animator)) {
                    android.animation.ObjectAnimator beat = android.animation.ObjectAnimator.ofPropertyValuesHolder(rb,
                            android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f, 1.15f),
                            android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f, 1.15f));
                    beat.setDuration(360);
                    beat.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                    beat.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                    beat.start();
                    rb.setTag(beat);
                }
                if (rerollUntil > 0) {
                    long secs = Math.max(0, (rerollUntil - System.currentTimeMillis() + 999) / 1000);
                    status.setText("Not lucky? Tap ↻ to roll again for 💎" + rerollCost + "  (" + secs + "s)");
                }
            } else {
                if (!Boolean.FALSE.equals(retryBtn.getTag())) {
                    retryBtn.setTag(Boolean.FALSE);
                    GradientDrawable g = new GradientDrawable();
                    g.setShape(GradientDrawable.OVAL);
                    g.setColor(0x33ffffff);
                    retryBtn.setBackground(g);
                    retryBtn.setTextColor(0x88ffffff);
                }
                retryCostView.setText("");
                retryRing.setFraction(0f, Ui.GOLD);
                View rb = (View) retryBtn.getParent();
                if (rb.getTag() instanceof android.animation.Animator) {
                    ((android.animation.Animator) rb.getTag()).cancel();
                    rb.setTag(null);
                    rb.setScaleX(1f);
                    rb.setScaleY(1f);
                }
            }
        }
        int unread = local != null ? 0 : app.socket.unreadChat;
        String chatLabel = unread > 0 ? "💬" + Math.min(unread, 99) : "💬";
        if (!chatLabel.contentEquals(chatBtn.getText())) {
            chatBtn.setText(chatLabel);
            chatBtn.setTextSize(unread > 0 ? 16 : 22);
        }
    }

    // ================================================================= result

    private void showResult(boolean force) {
        if (state == null || !"over".equals(state.phase) || isFinishing()) return;
        if (resultShownFor == state.seq && !force) return;
        boolean firstTime = resultShownFor != state.seq;
        resultShownFor = state.seq;
        dismissResult();
        String me = local != null ? "" : app.socket.myColor();
        int winnerIdx = state.ranking.isEmpty() ? -1 : state.ranking.get(0);
        GameState.Player winner = winnerIdx >= 0 ? state.players.get(winnerIdx) : null;
        boolean teamMatch = teams();
        // 2 vs 2: both partners win (ranked 1st and 2nd).
        boolean iWon = winner != null && (winner.color.equals(me) || (teamMatch && teamOf(winner.color) == teamOf(me)));
        if (firstTime) Sfx.get().play(iWon || local != null ? Sfx.S.VICTORY : Sfx.S.DEFEAT);

        VictoryPopup.Spec spec = new VictoryPopup.Spec();
        // Offline everyone shares the phone: every result is a celebration.
        spec.win = iWon || local != null;
        spec.ranking = new java.util.ArrayList<>();
        JSONArray seats = local != null || app.socket.room == null ? null : app.socket.room.optJSONArray("seats");
        for (int r = 0; r < state.ranking.size(); r++) {
            GameState.Player p = state.players.get(state.ranking.get(r));
            JSONObject seat = seatFor(seats, p.color);
            spec.ranking.add(new VictoryPopup.Row(p.name, p.color, seat, p.color.equals(me), seat != null && "ai".equals(seat.optString("kind"))));
        }
        JSONObject s = local != null ? null : app.socket.settlement;
        if (s != null && s.optLong("entryFee") > 0) {
            if (iWon) spec.coinsWon = s.optLong("payout");
            else spec.payoutNote = (teamMatch ? "Each winner got 🪙 " : "Winner got 🪙 ") + Ui.fmt(s.optLong("payout"));
        }
        GameState.Player mate = teamMatch && state.ranking.size() > 1 ? state.players.get(state.ranking.get(1)) : null;
        String winners = winner == null ? "" : winner.name + (mate != null ? " & " + mate.name : "");
        if (local != null) {
            spec.headline = "VICTORY!";
            spec.sub = winner != null ? "👑 " + winner.name + " wins the match!" : "Match over";
            spec.primary = "PLAY AGAIN";
            spec.onPrimary = this::localRematch;
            spec.onLeave = this::finish;
        } else {
            spec.headline = iWon ? "VICTORY!" : "GOOD GAME";
            spec.sub = iWon ? (teamMatch ? "Your team won the match!" : "You won the match!")
                    : winner != null ? "👑 " + winners + (mate != null ? " win" : " wins") + " this one. Next time!" : "Match over";
            spec.primary = "REMATCH";
            spec.onPrimary = () -> app.socket.emit("game:rematch", new JSONObject(), res -> {
                if (!res.optBoolean("ok")) {
                    LudoApp.toast(res.optString("error", "Rematch failed"));
                    resultShownFor = -1;
                    showResult(true);
                }
            });
            spec.onLeave = () -> app.socket.leave(res -> finish());
        }
        resultDialog = VictoryPopup.show(this, spec);
    }

    private void dismissResult() {
        if (resultDialog != null && resultDialog.isShowing()) resultDialog.dismiss();
        resultDialog = null;
    }

    private void confirmLeave() {
        if (local != null) {
            if (state == null || "over".equals(state.phase)) finish();
            else Popup.message(this, "Quit the match?", "This offline match will end.", Popup.ok("Stay", null), Popup.danger("Quit", this::finish));
            return;
        }
        if (state == null || "over".equals(state.phase)) {
            app.socket.leave(res -> finish());
            return;
        }
        long fee = app.socket.room != null ? app.socket.room.optLong("entryFee") : 0;
        Popup.message(this, "Leave the match?",
                fee > 0 ? "The match has started, so your entry fee is not refunded. A bot will play your seat." : "A bot will play your seat.",
                Popup.ok("Stay", null),
                Popup.danger("Leave", () -> app.socket.leave(res -> finish())));
    }

    // ================================================================= chat & emotes

    private void buildChatPanel() {
        chatPanel = Ui.vbox(this);
        chatPanel.setBackground(Ui.roundStroke(0xff2e1065, Ui.dp(this, 20), Ui.dp(this, 1), Ui.STROKE));
        int p = Ui.dp(this, 12);
        chatPanel.setPadding(p, p, p, p);
        chatPanel.setClickable(true);
        LinearLayout head = Ui.hbox(this);
        chatTitle = Ui.bold(Ui.text(this, "Room chat", 16, Ui.INK));
        head.addView(chatTitle, Ui.weight(1));
        head.addView(Ui.smallButton(this, "Close", "glass", v -> closePanels()));
        chatPanel.addView(head);
        // 2 vs 2: talk to everyone, or privately to my partner.
        LinearLayout channels = Ui.hbox(this);
        channels.setTag("chat:channels");
        chatAllBtn = Ui.smallButton(this, "💬 Everyone", "glass", v -> setTeamChat(false));
        chatTeamBtn = Ui.smallButton(this, "🤝 Team only", "glass", v -> setTeamChat(true));
        channels.addView(chatAllBtn, Ui.weight(1));
        channels.addView(Ui.gap(this, 8));
        channels.addView(chatTeamBtn, Ui.weight(1));
        Ui.margins(channels, this, 0, 8, 0, 0);
        chatPanel.addView(channels);
        chatScroll = new ScrollView(this);
        chatList = Ui.vbox(this);
        chatScroll.addView(chatList);
        chatPanel.addView(chatScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        HorizontalScrollView qs = new HorizontalScrollView(this);
        qs.setHorizontalScrollBarEnabled(false);
        LinearLayout quick = Ui.hbox(this);
        for (String m : Social.QUICK_MESSAGES) {
            quick.addView(Ui.smallButton(this, m, "glass", v -> sendChat(m, true)));
            quick.addView(Ui.gap(this, 6));
        }
        qs.addView(quick);
        Ui.margins(qs, this, 0, 8, 0, 8);
        chatPanel.addView(qs);
        LinearLayout inputRow = Ui.hbox(this);
        EditText input = Ui.input(this, "Say something…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(Social.CHAT_MAX_CHARS * 2)});
        LinearLayout.LayoutParams ilp = Ui.weight(1);
        ilp.rightMargin = Ui.dp(this, 8);
        inputRow.addView(input, ilp);
        Runnable send = () -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;
            sendChat(text, false);
            input.setText("");
        };
        input.setOnEditorActionListener((v, actionId, event) -> {
            send.run();
            return true;
        });
        inputRow.addView(Ui.button(this, "Send", "blue", v -> send.run()));
        chatPanel.addView(inputRow);
        chatPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM);
        lp.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        lp.setMargins(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 8));
        root.addView(chatPanel, lp);
    }

    private void buildEmotePanel() {
        emotePanel = Ui.vbox(this);
        emotePanel.setBackground(Ui.roundStroke(0xff2e1065, Ui.dp(this, 20), Ui.dp(this, 1), Ui.STROKE));
        int p = Ui.dp(this, 12);
        emotePanel.setPadding(p, p, p, p);
        emotePanel.setClickable(true);
        LinearLayout head = Ui.hbox(this);
        head.addView(Ui.bold(Ui.text(this, "Emotes", 16, Ui.INK)), Ui.weight(1));
        head.addView(Ui.smallButton(this, "Close", "glass", v -> closePanels()));
        emotePanel.addView(head);
        LinearLayout row = null;
        int n = 0;
        for (Map.Entry<String, String> e : Social.EMOTES.entrySet()) {
            if (n % 7 == 0) {
                row = Ui.hbox(this);
                emotePanel.addView(row, Ui.lpMatchWrap());
            }
            TextView t = Ui.text(this, e.getValue(), 28, Ui.INK);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            String id = e.getKey();
            t.setOnClickListener(v -> {
                sendEmote(id);
                closePanels();
            });
            row.addView(t, Ui.weight(1));
            n++;
        }
        while (n % 7 != 0) {
            row.addView(new View(this), Ui.weight(1));
            n++;
        }
        emotePanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.setMargins(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 8));
        root.addView(emotePanel, lp);
    }

    private void openChat() {
        Sfx.get().play(Sfx.S.CLICK);
        emotePanel.setVisibility(View.GONE);
        chatPanel.setVisibility(View.VISIBLE);
        app.socket.chatOpen = true;
        app.socket.unreadChat = 0;
        renderChat();
        renderStatus();
    }

    /** Pick who my next chat messages go to (2 vs 2 only). */
    private void setTeamChat(boolean team) {
        teamChat = team;
        renderChat();
    }

    private void openEmotes() {
        Sfx.get().play(Sfx.S.CLICK);
        chatPanel.setVisibility(View.GONE);
        app.socket.chatOpen = false;
        emotePanel.setVisibility(View.VISIBLE);
    }

    private void closePanels() {
        chatPanel.setVisibility(View.GONE);
        emotePanel.setVisibility(View.GONE);
        app.socket.chatOpen = false;
        View f = getCurrentFocus();
        if (f != null) {
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(f.getWindowToken(), 0);
        }
    }

    private void renderChat() {
        if (chatList == null) return;
        boolean teams = teams();
        if (!teams) teamChat = false;
        View channels = chatPanel.findViewWithTag("chat:channels");
        if (channels != null) channels.setVisibility(teams ? View.VISIBLE : View.GONE);
        if (teams) {
            Fx.bevel(chatAllBtn, teamChat ? Fx.PURPLE_DARK : Fx.YELLOW, 12, 3, true);
            chatAllBtn.setTextColor(teamChat ? Ui.INK : 0xff4a2600);
            Fx.bevel(chatTeamBtn, teamChat ? Fx.GREEN : Fx.PURPLE_DARK, 12, 3, true);
            chatTitle.setText(teamChat ? "Team chat 🤝" : "Room chat");
        }
        chatList.removeAllViews();
        String mine = app.socket.mySeatId();
        if (app.socket.chat.isEmpty()) chatList.addView(Ui.muted(this, teams ? "No messages yet. Plan with your partner! 🤝" : "No messages yet. Say hi! 👋"));
        for (JSONObject m : app.socket.chat) {
            boolean me = mine.equals(m.optString("seatId"));
            boolean team = !m.isNull("team") && m.has("team");
            LinearLayout line = Ui.vbox(this);
            line.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
            line.setBackground(team ? Ui.roundStroke(0x3322b46b, Ui.dp(this, 12), Ui.dp(this, 1), 0x8822b46b)
                    : Ui.round(me ? 0x332f7cf0 : 0x14ffffff, Ui.dp(this, 12)));
            line.addView(Ui.bold(Ui.text(this, (team ? "🤝 TEAM · " : "") + m.optString("name") + (me ? " (you)" : ""), 12, Board.colorArgb(m.optString("color")))));
            line.addView(Ui.text(this, m.optString("text"), 15, Ui.INK));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = me ? Gravity.END : Gravity.START;
            lp.topMargin = Ui.dp(this, 6);
            chatList.addView(line, lp);
        }
        if (chatPanel.getVisibility() == View.VISIBLE) app.socket.unreadChat = 0;
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
        renderStatus();
    }

    private void sendChat(String text, boolean quick) {
        JSONObject p = PlayFlow.obj("text", text);
        try {
            p.put("quick", quick);
            p.put("team", teamChat && teams());
        } catch (Exception ignored) {
            // not reachable
        }
        app.socket.emit("chat:send", p, PlayFlow.toastOnError());
    }

    private void sendEmote(String id) {
        long now = System.currentTimeMillis();
        if (now - lastEmoteAt < 1200) return;
        lastEmoteAt = now;
        if (local != null) {
            if (state != null) showEmote(id, state.currentPlayer().color);
            return;
        }
        app.socket.emit("emote:send", PlayFlow.obj("id", id), PlayFlow.toastOnError());
    }

    /** A big emoji rising from the sender's player chip. */
    private void showEmote(String id, String color) {
        View chip = root.findViewWithTag("chip:" + color);
        Sfx.get().play(Sfx.forEmoteEffect(Social.effect(id)));
        TextView t = Ui.text(this, Social.emoji(id), 46, Ui.INK);
        t.measure(0, 0);
        float x, y;
        // Emotes float from the card towards the board: down from the top cards, up from the bottom ones.
        float drift = Ui.dp(this, 120);
        if (chip != null) {
            int[] loc = new int[2];
            int[] rootLoc = new int[2];
            chip.getLocationInWindow(loc);
            root.getLocationInWindow(rootLoc);
            x = loc[0] - rootLoc[0] + chip.getWidth() / 2f - t.getMeasuredWidth() / 2f;
            y = loc[1] - rootLoc[1] + chip.getHeight() / 2f - t.getMeasuredHeight() / 2f;
            if (y > root.getHeight() / 2f) drift = -drift;
        } else {
            x = root.getWidth() / 2f - t.getMeasuredWidth() / 2f;
            y = root.getHeight() / 3f;
        }
        effects.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        t.setX(x);
        t.setY(y);
        t.setScaleX(0.3f);
        t.setScaleY(0.3f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(t, "scaleX", 0.3f, 1.3f, 1f),
                ObjectAnimator.ofFloat(t, "scaleY", 0.3f, 1.3f, 1f),
                ObjectAnimator.ofFloat(t, "translationY", y, y + drift),
                ObjectAnimator.ofFloat(t, "alpha", 1f, 1f, 0f));
        set.setDuration(1800);
        set.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                effects.removeView(t);
            }
        });
        set.start();
    }
}
