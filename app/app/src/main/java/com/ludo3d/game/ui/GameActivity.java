package com.ludo3d.game.ui;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import androidx.appcompat.app.AlertDialog;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.text.InputType;
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

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.game.Board;
import com.ludo3d.game.game.GameState;
import com.ludo3d.game.game.Social;
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

    private LinearLayout chips;
    private TextView status, rollBtn, chatBtn;
    private FrameLayout effects;
    private LinearLayout chatPanel, emotePanel, chatList;
    private ScrollView chatScroll;
    private AlertDialog resultDialog;

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
        gl = new GLSurfaceView(this);
        gl.setEGLContextClientVersion(2);
        gl.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        gl.setRenderer(renderer);
        gl.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(gl, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setupTouch();
        buildHud();
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
                new AlertDialog.Builder(this).setTitle("Match closed").setMessage(String.valueOf(data))
                        .setPositiveButton("OK", (d, w) -> finish()).setOnCancelListener(d -> finish()).show();
                break;
            case "room:left":
                finish();
                break;
            case "match:settled":
                if (state != null && "over".equals(state.phase)) showResult(true);
                break;
            case "chat:message":
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
        deadline = u.isNull("deadline") ? 0 : u.optLong("deadline") - u.optLong("serverTime") + System.currentTimeMillis();
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

    private void setupTouch() {
        final float[] down = new float[2];
        final boolean[] dragging = {false};
        final long[] lastTap = {0};
        gl.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = ev.getX();
                    down[1] = ev.getY();
                    dragging[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getX() - down[0];
                    if (!dragging[0] && Math.abs(dx) > Ui.dp(this, 12)) dragging[0] = true;
                    if (dragging[0]) {
                        renderer.yawOffset += dx * 0.25f;
                        down[0] = ev.getX();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging[0]) {
                        long now = System.currentTimeMillis();
                        if (now - lastTap[0] < 300) {
                            renderer.yawOffset = 0;
                            lastTap[0] = 0;
                        } else {
                            lastTap[0] = now;
                            tap(ev.getX(), ev.getY());
                        }
                    }
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
        float bestD = Ui.dp(this, 56);
        for (int t : movable) {
            float dd = dist(renderer.tokenScreen(state.current, t), x, y);
            if (dd < bestD) {
                bestD = dd;
                best = t;
            }
        }
        if (best >= 0) move(best);
    }

    private static float dist(float[] p, float x, float y) {
        float dx = p[0] - x, dy = p[1] - y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private void roll() {
        if (busy) return;
        busy = true;
        renderStatus();
        app.socket.emit("game:roll", new JSONObject(), res -> {
            busy = false;
            if (!res.optBoolean("ok")) LudoApp.toast(res.optString("error", "Can't roll now"));
            renderStatus();
        });
    }

    private void move(int token) {
        busy = true;
        app.socket.emit("game:move", PlayFlow.obj("token", token), res -> {
            busy = false;
            if (!res.optBoolean("ok")) LudoApp.toast(res.optString("error", "Can't move that token"));
            renderStatus();
        });
    }

    // ================================================================= HUD

    private void buildHud() {
        LinearLayout top = Ui.vbox(this);
        top.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), 0);
        LinearLayout bar = Ui.hbox(this);
        TextView leave = Ui.smallButton(this, "✕ Leave", "glass", v -> confirmLeave());
        bar.addView(leave);
        TextView title = Ui.text(this, "", 13, Ui.MUTED);
        title.setGravity(Gravity.END);
        title.setTag("title");
        bar.addView(title, Ui.weight(1));
        top.addView(bar);
        chips = Ui.hbox(this);
        Ui.margins(chips, this, 0, 8, 0, 0);
        top.addView(chips);
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        effects = new FrameLayout(this);
        effects.setClickable(false);
        root.addView(effects, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bottom = Ui.vbox(this);
        bottom.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 14));
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        status = Ui.bold(Ui.text(this, "", 15, Ui.INK));
        status.setGravity(Gravity.CENTER);
        status.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        status.setBackground(Ui.round(0x99101438, Ui.dp(this, 999)));
        // After "View board", tapping the status brings the result card back (rematch / leave).
        status.setOnClickListener(v -> {
            if (state != null && "over".equals(state.phase)) showResult(true);
        });
        bottom.addView(status);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout reactions = Ui.hbox(this);
        for (String id : Social.INSTANT_REACTIONS) {
            TextView r = Ui.text(this, Social.emoji(id), 24, Ui.INK);
            r.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
            r.setOnClickListener(v -> sendEmote(id));
            reactions.addView(r);
        }
        hs.addView(reactions);
        Ui.margins(hs, this, 0, 8, 0, 4);
        LinearLayout.LayoutParams hlp = (LinearLayout.LayoutParams) hs.getLayoutParams();
        hlp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        bottom.addView(hs);

        LinearLayout controls = Ui.hbox(this);
        chatBtn = Ui.button(this, "💬", "glass", v -> openChat());
        controls.addView(chatBtn);
        controls.addView(Ui.gap(this, 10));
        rollBtn = Ui.button(this, "🎲  ROLL", "primary", v -> roll());
        rollBtn.setTextSize(18);
        controls.addView(rollBtn, Ui.weight(1));
        controls.addView(Ui.gap(this, 10));
        controls.addView(Ui.button(this, "😀", "glass", v -> openEmotes()));
        bottom.addView(controls, Ui.lpMatchWrap());
        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        buildChatPanel();
        buildEmotePanel();
    }

    private void renderChips() {
        JSONObject room = app.socket.room;
        TextView title = root.findViewWithTag("title");
        if (room != null && title != null) {
            JSONObject mode = room.optJSONObject("mode");
            long fee = room.optLong("entryFee");
            title.setText((mode != null ? mode.optString("name") : "") + (fee > 0 ? " · entry " + Ui.coins(fee) : " · free"));
        }
        chips.removeAllViews();
        if (state == null) return;
        JSONArray seats = room != null ? room.optJSONArray("seats") : null;
        for (int i = 0; i < state.players.size(); i++) {
            GameState.Player p = state.players.get(i);
            JSONObject seat = seatFor(seats, p.color);
            boolean current = i == state.current && !"over".equals(state.phase);
            LinearLayout chip = Ui.hbox(this);
            chip.setTag("chip:" + p.color);
            chip.setPadding(Ui.dp(this, 6), Ui.dp(this, 5), Ui.dp(this, 8), Ui.dp(this, 5));
            int col = Board.colorArgb(p.color);
            chip.setBackground(Ui.roundStroke(current ? (col & 0x00ffffff) | 0x55000000 : 0x99101438, Ui.dp(this, 999),
                    Ui.dp(this, current ? 2 : 1), current ? col : Ui.STROKE));
            boolean bot = "ai".equals(p.kind);
            chip.addView(Ui.avatarColor(this, bot ? "🤖" : p.name, col, 24));
            String name = p.name.length() > 9 ? p.name.substring(0, 8) + "…" : p.name;
            if (p.rank > 0) name = (p.rank == 1 ? "🏆 " : "#" + p.rank + " ") + name;
            if (seat != null && !seat.optBoolean("connected", true) && !bot) name += " ⚠";
            TextView n = Ui.text(this, name, 12, Ui.INK);
            n.setPadding(Ui.dp(this, 5), 0, 0, 0);
            n.setSingleLine(true);
            chip.addView(n);
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.rightMargin = Ui.dp(this, 4);
            chips.addView(chip, lp);
        }
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
        if (!"online".equals(app.socket.connection)) {
            text = "Reconnecting…";
        } else if (state == null) {
            text = "Waiting for the match…";
        } else if ("over".equals(state.phase)) {
            text = "Match over";
        } else {
            GameState.Player cur = state.currentPlayer();
            long left = deadline > 0 ? Math.max(0, (deadline - System.currentTimeMillis() + 999) / 1000) : -1;
            String timer = left >= 0 ? "  ⏱ " + left + "s" : "";
            if (isMyTurn(state)) {
                if ("roll".equals(state.phase)) {
                    text = "Your turn — roll the dice!" + timer;
                    canRoll = !busy;
                } else {
                    text = "You rolled " + state.dice + " — tap a glowing token" + timer;
                }
            } else {
                text = cur.name + ("roll".equals(state.phase) ? " is rolling…" : " rolled " + state.dice) + timer;
            }
        }
        status.setText(text);
        Ui.setEnabled(rollBtn, canRoll);
        int unread = app.socket.unreadChat;
        chatBtn.setText(unread > 0 ? "💬 " + Math.min(unread, 99) : "💬");
    }

    // ================================================================= result

    private void showResult(boolean force) {
        if (state == null || !"over".equals(state.phase) || isFinishing()) return;
        if (resultShownFor == state.seq && !force) return;
        resultShownFor = state.seq;
        dismissResult();
        LinearLayout box = Ui.vbox(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        String me = app.socket.myColor();
        int winnerIdx = state.ranking.isEmpty() ? -1 : state.ranking.get(0);
        GameState.Player winner = winnerIdx >= 0 ? state.players.get(winnerIdx) : null;
        boolean iWon = winner != null && winner.color.equals(me);
        TextView big = Ui.text(this, iWon ? "🏆" : "🎲", 56, Ui.INK);
        big.setGravity(Gravity.CENTER);
        box.addView(big);
        TextView t = Ui.title(this, iWon ? "You won!" : winner != null ? winner.name + " wins" : "Match over");
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        JSONObject s = app.socket.settlement;
        if (s != null && s.optLong("entryFee") > 0) {
            TextView pay = Ui.bold(Ui.text(this, iWon ? "+" + Ui.coins(s.optLong("payout")) : "Winner received " + Ui.coins(s.optLong("payout")), 18, Ui.GOLD));
            pay.setGravity(Gravity.CENTER);
            pay.setPadding(0, Ui.dp(this, 8), 0, 0);
            box.addView(pay);
        }
        for (int r = 0; r < state.ranking.size(); r++) {
            GameState.Player p = state.players.get(state.ranking.get(r));
            TextView line = Ui.text(this, "#" + (r + 1) + "  " + p.name, 14, p.color.equals(me) ? Ui.GOLD : Ui.MUTED);
            line.setGravity(Gravity.CENTER);
            box.addView(line);
        }
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), 0);
        wrap.addView(box);
        resultDialog = new AlertDialog.Builder(this)
                .setView(wrap)
                .setCancelable(false)
                .setPositiveButton("Rematch", (d, w) -> app.socket.emit("game:rematch", new JSONObject(), res -> {
                    if (!res.optBoolean("ok")) {
                        LudoApp.toast(res.optString("error", "Rematch failed"));
                        resultShownFor = -1;
                        showResult(true);
                    }
                }))
                .setNegativeButton("Leave", (d, w) -> app.socket.leave(res -> finish()))
                .setNeutralButton("View board", null)
                .show();
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
        new AlertDialog.Builder(this)
                .setTitle("Leave the match?")
                .setMessage(fee > 0 ? "The match has started, so your entry fee is not refunded. A bot will play your seat." : "A bot will play your seat.")
                .setPositiveButton("Leave", (d, w) -> app.socket.leave(res -> finish()))
                .setNegativeButton("Stay", null)
                .show();
    }

    // ================================================================= chat & emotes

    private void buildChatPanel() {
        chatPanel = Ui.vbox(this);
        chatPanel.setBackground(Ui.roundStroke(0xff121640, Ui.dp(this, 20), Ui.dp(this, 1), Ui.STROKE));
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
        emotePanel.setBackground(Ui.roundStroke(0xff121640, Ui.dp(this, 20), Ui.dp(this, 1), Ui.STROKE));
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
        emotePanel.setVisibility(View.GONE);
        chatPanel.setVisibility(View.VISIBLE);
        app.socket.chatOpen = true;
        app.socket.unreadChat = 0;
        renderChat();
        renderStatus();
    }

    private void openEmotes() {
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
        View chip = chips.findViewWithTag("chip:" + color);
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
