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
 * to turn the board (double-tap to reset).
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

    private LinearLayout panels;
    private TextView status, rollBtn, chatBtn, titleView, feeView, connView;
    private TextView timerView;
    private String timerBase = "";
    private View timerBar;
    private android.animation.ValueAnimator pulse;
    /** Short screens (e.g. 320x568 dp): smaller panels and controls, so the board keeps its room. */
    private boolean compact;
    private FrameLayout effects;
    private LinearLayout chatPanel, emotePanel, chatList;
    private ScrollView chatScroll;
    private Popup resultDialog;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            renderStatus();
            LudoApp.MAIN.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        renderer = new GameRenderer();
        // Feel it when one of your tokens is sent home.
        renderer.listener = () -> runOnUiThread(() -> gl.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS));
        gl = new GLSurfaceView(this);
        gl.setEGLContextClientVersion(2);
        gl.setEGLConfigChooser(new ConfigChooser());
        gl.setRenderer(renderer);
        gl.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
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
        if (app.socket.room == null) {
            finish();
            return;
        }
        String color = app.socket.myColor();
        gl.queueEvent(() -> renderer.setMyColor(color));
        if (app.socket.lastUpdate != null) onUpdate(app.socket.lastUpdate, true);
        renderChips();
        renderChat();
        LudoApp.MAIN.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        gl.onPause();
        LudoApp.MAIN.removeCallbacks(ticker);
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
        JSONArray events = u.optJSONArray("events");
        boolean contiguous = !resume && lastSeq >= 0 && next.seq == lastSeq + 1 && events != null && events.length() > 0;
        if (resume && next.seq == lastSeq) return;
        boolean wasMine = state != null && isMyTurn(state);
        lastSeq = next.seq;
        state = next;
        long nextDeadline = u.isNull("deadline") ? 0 : u.optLong("deadline") - u.optLong("serverTime") + System.currentTimeMillis();
        // A new deadline (not just the same turn re-sent) restarts the countdown bar.
        if (Math.abs(nextDeadline - deadline) > 1500) deadlineTotal = Math.max(1, nextDeadline - System.currentTimeMillis());
        deadline = nextDeadline;
        // A re-roll offer for my own last roll (cost, and a deadline if the turn already passed).
        JSONObject rr = u.optJSONObject("reroll");
        if (rr != null && app.socket.myColor().equals(rr.optString("color")) && !rr.isNull("nextCost")) {
            rerollCost = rr.optInt("nextCost");
            rerollUntil = rr.isNull("msLeft") ? 0 : System.currentTimeMillis() + rr.optLong("msLeft");
        } else {
            rerollCost = 0;
            rerollUntil = 0;
        }
        boolean highlight = isMyTurn(next) && "move".equals(next.phase);
        gl.queueEvent(() -> {
            renderer.applyUpdate(next, events, contiguous);
            renderer.setHighlight(highlight);
        });
        if (!wasMine && isMyTurn(next) && "roll".equals(next.phase)) gl.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        renderChips();
        renderStatus();
        if ("over".equals(next.phase)) {
            // Let the final hop play before the result card.
            LudoApp.MAIN.postDelayed(() -> showResult(false), 1400);
        } else {
            dismissResult();
        }
    }

    private boolean isMyTurn(GameState s) {
        return s != null && !"over".equals(s.phase) && s.currentPlayer().color.equals(app.socket.myColor());
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
                    if (!dragging[0] && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                        dragging[0] = true;
                        renderer.dragging = true;
                    }
                    if (!dragging[0] && Math.hypot(dx, dy) > slop) cancelled[0] = true;
                    if (dragging[0]) {
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
        gl.queueEvent(() -> renderer.setSelected(player, token));
        gl.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
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
                Popup.message(this, "Not enough gems", "💎\nA re-roll costs " + rerollCost + " gems. Buy gems in the shop.",
                        Popup.plain("Close", null),
                        new Popup.Btn("Gem shop", "gold", () -> startActivity(new android.content.Intent(this, GemShopActivity.class))));
            } else {
                Sfx.get().play(Sfx.S.ERROR);
                LudoApp.toast(res.optString("error", "Can't re-roll now"));
            }
            renderStatus();
        });
    }

    private void roll() {
        if (canReroll()) {
            reroll();
            return;
        }
        if (busy) return;
        busy = true;
        renderStatus();
        app.socket.emit("game:roll", new JSONObject(), res -> {
            busy = false;
            if (!res.optBoolean("ok")) {
                Sfx.get().play(Sfx.S.ERROR);
                LudoApp.toast(res.optString("error", "Can't roll now"));
            }
            renderStatus();
        });
    }

    private void move(int token) {
        busy = true;
        app.socket.emit("game:move", PlayFlow.obj("token", token), res -> {
            busy = false;
            if (!res.optBoolean("ok")) {
                gl.queueEvent(() -> renderer.setSelected(-1, -1));
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
        panels = Ui.vbox(this);
        Ui.margins(panels, this, 0, 10, 0, 0);
        top.addView(panels);
        column.addView(top, Ui.lpMatchWrap());

        // ---- middle: the 3D board
        FrameLayout boardArea = new FrameLayout(this);
        boardArea.addView(gl, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        column.addView(boardArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // ---- bottom: status + timer, quick reactions, chat / roll / emotes
        LinearLayout bottom = Ui.vbox(this);
        bottom.setPadding(pad, Ui.dp(this, 4), pad, Ui.dp(this, 10));
        LinearLayout statusBox = Ui.vbox(this);
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
        // After "View board", tapping the status brings the result card back (rematch / leave).
        statusBox.setOnClickListener(v -> {
            if (state != null && "over".equals(state.phase)) showResult(true);
        });
        bottom.addView(statusBox, Ui.lpMatchWrap());

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
            reactions.addView(r, new LinearLayout.LayoutParams(0, Ui.dp(this, compact ? 36 : 44), 1));
        }
        Ui.margins(reactions, this, 0, 4, 0, 4);
        bottom.addView(reactions);

        LinearLayout controls = Ui.hbox(this);
        chatBtn = roundIcon("💬", "Chat", v -> openChat());
        controls.addView(chatBtn, new LinearLayout.LayoutParams(Ui.dp(this, ctl()), Ui.dp(this, ctl())));
        rollBtn = Ui.button(this, "🎲  ROLL", "primary", v -> {
            pressPop(v);
            roll();
        });
        rollBtn.setTextSize(20);
        rollBtn.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, Ui.dp(this, ctl()), 1);
        rlp.leftMargin = Ui.dp(this, 12);
        rlp.rightMargin = Ui.dp(this, 12);
        controls.addView(rollBtn, rlp);
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
        return compact ? 28 : 34;
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

    /** Small press feedback for buttons. */
    private static void pressPop(View v) {
        v.animate().cancel();
        v.setScaleX(0.92f);
        v.setScaleY(0.92f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
    }

    /** Player panels: one row for two players, a 2x2 grid for three or four. */
    private void renderChips() {
        JSONObject room = app.socket.room;
        if (room != null) {
            JSONObject mode = room.optJSONObject("mode");
            long fee = room.optLong("entryFee");
            titleView.setText(mode != null ? mode.optString("name") : "Ludo");
            feeView.setText(fee > 0 ? "Entry " + Ui.coins(fee) + "  ·  Winner " + Ui.coins(Math.round(fee * room.optDouble("payoutMultiplier", 2)))
                    : "Free room — no coins at stake");
        }
        if (pulse != null) pulse.cancel();
        panels.removeAllViews();
        timerView = null;
        if (state == null) return;
        JSONArray seats = room != null ? room.optJSONArray("seats") : null;
        String me = app.socket.myColor();
        int n = state.players.size();
        LinearLayout row = null;
        for (int i = 0; i < n; i++) {
            if (n <= 2 ? i == 0 : i % 2 == 0) {
                row = Ui.hbox(this);
                LinearLayout.LayoutParams rp = Ui.lpMatchWrap();
                if (i > 0) rp.topMargin = Ui.dp(this, 6);
                panels.addView(row, rp);
            }
            GameState.Player p = state.players.get(i);
            JSONObject seat = seatFor(seats, p.color);
            boolean current = i == state.current && !"over".equals(state.phase);
            boolean bot = "ai".equals(p.kind);
            boolean online = bot || seat == null || seat.optBoolean("connected", true);
            int col = Board.colorArgb(p.color);

            LinearLayout card = Ui.hbox(this);
            card.setTag("chip:" + p.color);
            card.setPadding(Ui.dp(this, 8), Ui.dp(this, compact ? 4 : 7), Ui.dp(this, 10), Ui.dp(this, compact ? 4 : 7));
            GradientDrawable bg = Ui.roundStroke(current ? (col & 0x00ffffff) | 0x33000000 : 0xb32e1065, Ui.dp(this, 14),
                    Ui.dp(this, current ? 2 : 1), current ? col : Ui.STROKE);
            card.setBackground(bg);

            FrameLayout av = new FrameLayout(this);
            TextView a = bot ? Ui.avatarColor(this, "🤖", col, avSize())
                    : seat != null ? Ui.avatar(this, p.name, seat.optInt("avatarHue", 210), avSize()) : Ui.avatarColor(this, p.name, col, avSize());
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(0);
            ring.setStroke(Ui.dp(this, 2), col);
            View ringView = new View(this);
            ringView.setBackground(ring);
            av.addView(a, new FrameLayout.LayoutParams(Ui.dp(this, avSize()), Ui.dp(this, avSize()), Gravity.CENTER));
            av.addView(ringView, new FrameLayout.LayoutParams(Ui.dp(this, avSize() + 6), Ui.dp(this, avSize() + 6), Gravity.CENTER));
            View dot = new View(this);
            GradientDrawable dg = new GradientDrawable();
            dg.setShape(GradientDrawable.OVAL);
            dg.setColor(online ? Ui.SUCCESS : 0xff8a8fb0);
            dg.setStroke(Ui.dp(this, 2), 0xff2e1065);
            dot.setBackground(dg);
            av.addView(dot, new FrameLayout.LayoutParams(Ui.dp(this, 12), Ui.dp(this, 12), Gravity.BOTTOM | Gravity.END));
            card.addView(av, new LinearLayout.LayoutParams(Ui.dp(this, avSize() + 6), Ui.dp(this, avSize() + 6)));

            LinearLayout text = Ui.vbox(this);
            text.setPadding(Ui.dp(this, 8), 0, 0, 0);
            TextView name = Ui.bold(Ui.text(this, (p.rank == 1 ? "🏆 " : p.rank > 1 ? "#" + p.rank + " " : "") + p.name, compact ? 13 : 14, Ui.INK));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(name);
            String sub = p.color.equals(me) ? "You" : bot ? "Bot" : online ? "Online" : "Offline";
            TextView subView = Ui.text(this, sub, compact ? 11 : 12, current ? 0xffffd23f : Ui.MUTED);
            subView.setSingleLine(true);
            text.addView(subView);
            if (current) {
                timerView = subView;
                timerBase = sub;
            }
            card.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            // Long names are shortened on the card; tap it to read the whole name.
            card.setOnClickListener(v -> LudoApp.toast(p.name + (bot ? " (bot)" : "")));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (row.getChildCount() > 0) lp.leftMargin = Ui.dp(this, 8);
            row.addView(card, lp);

            if (current) {
                // Gentle breathing highlight on the active player's panel.
                pulse = android.animation.ValueAnimator.ofInt(0x33, 0x66);
                pulse.setDuration(900);
                pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                pulse.addUpdateListener(va -> bg.setColor((col & 0x00ffffff) | ((int) va.getAnimatedValue() << 24)));
                pulse.start();
            }
        }
        // Three players: keep the last card half width like the others.
        if (n == 3 && row != null) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
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
        boolean canRoll = false;
        boolean online = "online".equals(app.socket.connection);
        connView.setText(online ? "● Online" : "● Reconnecting");
        connView.setTextColor(online ? Ui.SUCCESS : Ui.GOLD);
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
            if (isMyTurn(state)) {
                if ("roll".equals(state.phase)) {
                    text = "Your turn — roll the dice!";
                    canRoll = !busy;
                } else {
                    text = "You rolled " + state.dice + " — tap a glowing token";
                }
            } else {
                text = cur.name + ("roll".equals(state.phase) ? " is rolling…" : " rolled " + state.dice);
            }
        }
        status.setText(text);
        if (timerView != null) timerView.setText(left >= 0 ? timerBase + " · ⏱ " + left + "s" : timerBase);
        float frac = deadline > 0 && deadlineTotal > 0 ? Math.max(0f, Math.min(1f, (deadline - System.currentTimeMillis()) / (float) deadlineTotal)) : 0f;
        timerBar.setScaleX(frac);
        timerBar.setBackground(Ui.round(frac < 0.25f ? Ui.DANGER : Ui.GOLD, Ui.dp(this, 2)));
        // My last roll can be re-rolled with gems: the big button becomes "RETRY".
        boolean retry = online && state != null && !"over".equals(state.phase) && canReroll() && !busy;
        if (retry) {
            long secs = rerollUntil > 0 ? Math.max(0, (rerollUntil - System.currentTimeMillis() + 999) / 1000) : -1;
            rollBtn.setText("↻ RETRY  💎" + rerollCost + (secs >= 0 ? "  (" + secs + "s)" : ""));
            if (rerollUntil > 0) status.setText("Not happy with your roll? Retry it with gems");
            Ui.setEnabled(rollBtn, true);
        } else {
            Ui.setEnabled(rollBtn, canRoll);
            rollBtn.setText(canRoll ? "🎲  ROLL" : busy ? "Rolling…" : isMyTurn(state) ? "Pick a token" : "Waiting…");
        }
        int unread = app.socket.unreadChat;
        chatBtn.setText(unread > 0 ? "💬" + Math.min(unread, 99) : "💬");
        chatBtn.setTextSize(unread > 0 ? 16 : 22);
    }

    // ================================================================= result

    private void showResult(boolean force) {
        if (state == null || !"over".equals(state.phase) || isFinishing()) return;
        if (resultShownFor == state.seq && !force) return;
        boolean firstTime = resultShownFor != state.seq;
        resultShownFor = state.seq;
        dismissResult();
        LinearLayout box = Ui.vbox(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        String me = app.socket.myColor();
        int winnerIdx = state.ranking.isEmpty() ? -1 : state.ranking.get(0);
        GameState.Player winner = winnerIdx >= 0 ? state.players.get(winnerIdx) : null;
        boolean iWon = winner != null && winner.color.equals(me);
        if (firstTime) Sfx.get().play(iWon ? Sfx.S.VICTORY : Sfx.S.DEFEAT);
        TextView big = Ui.text(this, iWon ? "🏆" : "🎲", 60, Ui.INK);
        big.setGravity(Gravity.CENTER);
        box.addView(big);
        JSONObject s = app.socket.settlement;
        if (s != null && s.optLong("entryFee") > 0) {
            Fx.GoldText pay = new Fx.GoldText(this, iWon ? "+🪙 " + Ui.fmt(s.optLong("payout")) : "Winner got 🪙 " + Ui.fmt(s.optLong("payout")), iWon ? 26 : 18);
            pay.setGravity(Gravity.CENTER);
            box.addView(pay, Ui.lpMatchWrap());
        }
        // Finishing order on 3D ribbons in each player's colour.
        for (int r = 0; r < state.ranking.size(); r++) {
            GameState.Player p = state.players.get(state.ranking.get(r));
            int col = Board.colorArgb(p.color);
            LinearLayout ribbon = Ui.hbox(this);
            int depth = Fx.bevel(ribbon, new int[]{Fx.blend(col, Color.WHITE, 0.25f), col, Fx.blend(col, Color.BLACK, 0.45f)}, 12, 3, false);
            ribbon.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6) + depth);
            ribbon.addView(Fx.GoldText.label(this, (r == 0 ? "🥇" : r == 1 ? "🥈" : r == 2 ? "🥉" : "#" + (r + 1)) + "  " + p.name
                    + (p.color.equals(me) ? " (you)" : ""), 15, 0x99000000));
            LinearLayout.LayoutParams lp = Ui.lpMatchWrap();
            lp.topMargin = Ui.dp(this, 8);
            box.addView(ribbon, lp);
        }
        resultDialog = Popup.showModal(this, iWon ? "You won!" : winner != null ? winner.name + " wins" : "Match over", box,
                Popup.ok("Rematch", () -> app.socket.emit("game:rematch", new JSONObject(), res -> {
                    if (!res.optBoolean("ok")) {
                        LudoApp.toast(res.optString("error", "Rematch failed"));
                        resultShownFor = -1;
                        showResult(true);
                    }
                })),
                Popup.plain("View board", null),
                Popup.danger("Leave", () -> app.socket.leave(res -> finish())));
    }

    private void dismissResult() {
        if (resultDialog != null && resultDialog.isShowing()) resultDialog.dismiss();
        resultDialog = null;
    }

    private void confirmLeave() {
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
        head.addView(Ui.bold(Ui.text(this, "Room chat", 16, Ui.INK)), Ui.weight(1));
        head.addView(Ui.smallButton(this, "Close", "glass", v -> closePanels()));
        chatPanel.addView(head);
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
        chatList.removeAllViews();
        String mine = app.socket.mySeatId();
        if (app.socket.chat.isEmpty()) chatList.addView(Ui.muted(this, "No messages yet. Say hi! 👋"));
        for (JSONObject m : app.socket.chat) {
            boolean me = mine.equals(m.optString("seatId"));
            LinearLayout line = Ui.vbox(this);
            line.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
            line.setBackground(Ui.round(me ? 0x332f7cf0 : 0x14ffffff, Ui.dp(this, 12)));
            line.addView(Ui.bold(Ui.text(this, m.optString("name") + (me ? " (you)" : ""), 12, Board.colorArgb(m.optString("color")))));
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
        } catch (Exception ignored) {
            // not reachable
        }
        app.socket.emit("chat:send", p, PlayFlow.toastOnError());
    }

    private void sendEmote(String id) {
        long now = System.currentTimeMillis();
        if (now - lastEmoteAt < 1200) return;
        lastEmoteAt = now;
        app.socket.emit("emote:send", PlayFlow.obj("id", id), PlayFlow.toastOnError());
    }

    /** A big emoji rising from the sender's player chip. */
    private void showEmote(String id, String color) {
        View chip = panels.findViewWithTag("chip:" + color);
        Sfx.get().play(Sfx.forEmoteEffect(Social.effect(id)));
        TextView t = Ui.text(this, Social.emoji(id), 46, Ui.INK);
        t.measure(0, 0);
        float x, y;
        if (chip != null) {
            int[] loc = new int[2];
            int[] rootLoc = new int[2];
            chip.getLocationInWindow(loc);
            root.getLocationInWindow(rootLoc);
            x = loc[0] - rootLoc[0] + chip.getWidth() / 2f - t.getMeasuredWidth() / 2f;
            y = loc[1] - rootLoc[1] + chip.getHeight();
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
                ObjectAnimator.ofFloat(t, "translationY", y, y + Ui.dp(this, 120)),
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
