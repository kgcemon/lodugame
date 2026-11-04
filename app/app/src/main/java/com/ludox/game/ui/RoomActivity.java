package com.ludox.game.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludox.game.LudoApp;
import com.ludox.game.game.Board;
import com.ludox.game.net.Api;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The waiting room: seats, ready states, host controls (start, add bot in free rooms,
 * remove players), the room code to share and inviting friends. Quick-match rooms start
 * by themselves when full.
 */
public class RoomActivity extends BaseActivity {
    private LinearLayout col;
    /** Quick-play rooms show the full-screen "searching for players" screen instead. */
    private Matchmaking matchmaking;
    /** MATCH FOUND, over whatever this screen was showing, until the board takes over. */
    private MatchIntroScreen intro;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        JSONObject room = app.socket.room;
        if (app.socket.activeIntro() != null) {
            showIntro();
            return;
        }
        if (room != null && room.optBoolean("isPublic")) {
            matchmaking = new Matchmaking(this);
            matchmaking.build(room);
            // Back while searching cancels the search (and refunds the fee).
            getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    // Once the match is confirmed there is nothing to cancel.
                    if (intro == null) leave();
                }
            });
            return;
        }
        col = pageWithBack("Room");
        render();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (matchmaking != null) matchmaking.stop();
        if (intro != null) intro.stop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (intro != null) intro.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (intro != null) intro.onResume();
        if (app.socket.room == null) finish();
        else refresh();
    }

    private void refresh() {
        JSONObject room = app.socket.room;
        if (room != null && intro == null && app.socket.activeIntro() != null) showIntro();
        if (intro != null) {
            // The server moved on before the animation did (it has ended, or the opponent is gone).
            if (room == null || room.isNull("intro")) intro.finish();
            return;
        }
        if (matchmaking != null) {
            if (app.socket.room != null) matchmaking.update(app.socket.room);
        } else {
            render();
        }
    }

    /** The match is confirmed: both players see who they're playing and the pool fill, then the board. */
    private void showIntro() {
        JSONObject room = app.socket.room;
        JSONObject it = app.socket.activeIntro();
        if (room == null || it == null || intro != null) return;
        if (matchmaking != null) matchmaking.stop();
        intro = new MatchIntroScreen(this, it, room.optInt("you", -1), app.socket.introEndsAt, this::introDone);
        // The room runs on behind the status and navigation bars.
        root.setBackgroundColor(0xff160736);
        intro.show(root);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Nothing to cancel any more; the board is seconds away.
            }
        });
    }

    private void introDone() {
        if (isFinishing()) return;
        if (app.socket.room == null) {
            finish();
            return;
        }
        // Time's up on this phone, even if the server's word that it ended is a moment away.
        startActivity(new Intent(this, GameActivity.class));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public void onSocketEvent(String event, Object data) {
        super.onSocketEvent(event, data);
        switch (event) {
            case "room:state":
                refresh();
                break;
            case "room:closed":
                alertAndClose(String.valueOf(data));
                break;
            case "room:left":
                finish();
                break;
            default:
                break;
        }
    }

    @Override
    protected void onVoiceChanged() {
        if (matchmaking == null && intro == null && col != null) render();
    }

    private void alertAndClose(String reason) {
        Popup p = Popup.message(this, "Room closed", reason, Popup.ok("OK", this::finish));
        p.setOnCancelListener(d -> finish());
    }

    private void render() {
        JSONObject room = app.socket.room;
        if (col == null) return;
        col.removeAllViews();
        if (room == null) return;
        JSONArray seats = room.optJSONArray("seats");
        if (seats == null) seats = new JSONArray();
        int you = room.optInt("you", -1);
        JSONObject me = you >= 0 ? seats.optJSONObject(you) : null;
        boolean isHost = me != null && me.optBoolean("isHost");
        boolean isPublic = room.optBoolean("isPublic");
        long fee = room.optLong("entryFee");
        JSONObject mode = room.optJSONObject("mode");
        String modeName = mode != null ? mode.optString("name") : "Ludo";

        col.addView(Ui.title(this, isPublic ? modeName + " — finding players" : PlayFlow.roomName(modeName)));
        col.addView(Ui.text(this, fee > 0 ? "Entry fee " + Ui.coins(fee) + " · winner receives " + Ui.coins(Math.round(fee * room.optDouble("payoutMultiplier", 2)))
                : "Free room — no coins at stake", 14, Ui.GOLD));

        if (!isPublic) {
            LinearLayout codeCard = Ui.card(this);
            Ui.margins(codeCard, this, 0, 14, 0, 0);
            codeCard.setGravity(Gravity.CENTER_HORIZONTAL);
            codeCard.addView(Ui.muted(this, "Room code"));
            TextView code = Ui.bold(Ui.text(this, room.optString("code"), 32, Ui.INK));
            code.setLetterSpacing(0.25f);
            codeCard.addView(code);
            TextView share = Ui.smallButton(this, "Share code", "glass", v -> share(room.optString("code"), modeName));
            Ui.margins(share, this, 0, 8, 0, 0);
            codeCard.addView(share);
            col.addView(codeCard);
        }

        int maxPlayers = room.optInt("maxPlayers", 4);
        boolean teams = room.optBoolean("teams");
        int myTeam = me != null ? me.optInt("team", -1) : -1;
        col.addView(Ui.heading(this, "Players " + seats.length() + "/" + maxPlayers + (teams ? "  ·  🤝 2 vs 2" : "")));
        if (teams) col.addView(Ui.muted(this, "Team A (seats 1 + 3) plays Team B (seats 2 + 4). Partners share a private team chat in the match."));
        LinearLayout list = Ui.card(this);
        boolean everyoneReady = true;
        for (int i = 0; i < maxPlayers; i++) {
            JSONObject s = i < seats.length() ? seats.optJSONObject(i) : null;
            LinearLayout row = Ui.row(this);
            if (s == null) {
                row.addView(Ui.avatarColor(this, "", 0x22ffffff, 38));
                TextView w = Ui.muted(this, isPublic ? "Searching for a player…" : "Waiting for a player…");
                w.setPadding(Ui.dp(this, 12), 0, 0, 0);
                row.addView(w, Ui.weight(1));
                if (isHost && !isPublic && fee == 0) {
                    row.addView(Ui.smallButton(this, "🤖 Add bot", "glass", v -> app.socket.emit("room:addBot", new JSONObject(), PlayFlow.toastOnError())));
                }
                list.addView(row);
                continue;
            }
            if (!s.optBoolean("ready")) everyoneReady = false;
            boolean bot = "ai".equals(s.optString("kind"));
            row.addView(bot ? Ui.avatarColor(this, "🤖", Board.colorArgb(s.optString("color")), 38)
                    : Ui.avatar(this, s, s.optString("name"), 38));
            LinearLayout t = Ui.vbox(this);
            t.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 6), 0);
            t.addView(Ui.bold(Ui.text(this, ("ai".equals(s.optString("kind")) ? "" : Flags.of(s) + " ") + s.optString("name") + (i == you ? " (you)" : ""), 15, Ui.INK)));
            LinearLayout tags = Ui.hbox(this);
            tags.addView(colorDot(s.optString("color")));
            if (teams && !s.isNull("team")) {
                int team = s.optInt("team");
                String label = (team == 0 ? "TEAM A" : "TEAM B") + (i != you && team == myTeam ? " · PARTNER" : "");
                tags.addView(tagGap(Ui.tag(this, label, team == 0 ? 0xffff8a80 : 0xff80d8ff)));
            }
            if (s.optBoolean("isHost")) tags.addView(tagGap(Ui.tag(this, "👑 HOST", Ui.GOLD)));
            if (bot) tags.addView(tagGap(Ui.tag(this, "BOT", Ui.MUTED)));
            if (!s.optBoolean("connected")) tags.addView(tagGap(Ui.tag(this, s.optBoolean("ready") ? "OFFLINE" : "JOINING", Ui.DANGER)));
            t.addView(tags);
            row.addView(t, Ui.weight(1));
            row.addView(Ui.text(this, s.optBoolean("ready") ? "✓ Ready" : "Not ready", 13, s.optBoolean("ready") ? Ui.SUCCESS : Ui.MUTED));
            // A friend: tap the speaker to mute / unmute their voice.
            com.ludox.game.audio.VoiceChat voice = com.ludox.game.audio.VoiceChat.get();
            if (i != you && voice.isFriend(s)) {
                String vpid = s.optString("publicId");
                TextView sp = Ui.text(this, voice.isMuted(vpid) ? "  🔇" : voice.speaking(s.optString("id")) ? "  🗣" : "  🔊", 18, Ui.INK);
                sp.setContentDescription((voice.isMuted(vpid) ? "Unmute " : "Mute ") + s.optString("name"));
                sp.setOnClickListener(v -> voice.toggleMute(vpid));
                row.addView(sp);
            }
            if (isHost && i != you && !isPublic) {
                final int seat = i;
                TextView x = Ui.text(this, "  ✕", 18, Ui.MUTED);
                x.setOnClickListener(v -> Popup.message(this, "Remove " + s.optString("name") + "?",
                        fee > 0 ? "Their entry fee is refunded." : "They can rejoin with the room code.",
                        Popup.plain("Cancel", null),
                        Popup.danger("Remove", () -> app.socket.emit("room:kick", PlayFlow.obj("seat", seat), PlayFlow.toastOnError()))));
                row.addView(x);
            }
            list.addView(row);
        }
        col.addView(list);

        // Voice with friends in this room: one big mic switch.
        com.ludox.game.audio.VoiceChat voice = com.ludox.game.audio.VoiceChat.get();
        if (voice.available()) {
            LinearLayout vc = Ui.card(this);
            Ui.margins(vc, this, 0, 12, 0, 0);
            LinearLayout vr = Ui.row(this);
            LinearLayout vt = Ui.vbox(this);
            vt.addView(Ui.bold(Ui.text(this, "🎧 Voice chat with friends", 15, Ui.INK)));
            vt.addView(Ui.muted(this, voice.micOn() ? "Your mic is on. Only your friends here hear you." : "Turn on your mic to talk. Only friends hear you."));
            vr.addView(vt, Ui.weight(1));
            vr.addView(Ui.smallButton(this, voice.micOn() ? "🎙 On" : "🔇 Off", voice.micOn() ? "ok" : "glass", v -> toggleMic()));
            vc.addView(vr);
            col.addView(vc);
        }

        // The board everyone plays on: the host's paid one, else that of whoever sat down first.
        com.ludox.game.gl.BoardThemes.Theme board = com.ludox.game.gl.BoardThemes.get(room.optString("board", null));
        LinearLayout bc = Ui.card(this);
        Ui.margins(bc, this, 0, 12, 0, 14);
        LinearLayout br = Ui.row(this);
        br.addView(new BoardPreview(this, board.id, false), new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));
        LinearLayout bt = Ui.vbox(this);
        bt.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 6), 0);
        bt.addView(Ui.bold(Ui.text(this, (board.royal() ? "👑 " : "🎲 ") + board.name + " board", 15, board.royal() ? Ui.GOLD : Ui.INK)));
        String by = room.isNull("boardBy") ? null : room.optString("boardBy", null);
        bt.addView(Ui.muted(this, by == null ? "The free board. A paid board shows here when the host or anyone who joins has one."
                : me != null && by.equals(me.optString("name")) ? "Your board — everyone here plays on it, free for them."
                : by + "'s board — everyone here plays on it."));
        br.addView(bt, Ui.weight(1));
        if (isHost) {
            br.addView(Ui.smallButton(this, board.royal() ? "Change" : "👑 Royal", "glass",
                    v -> startActivity(new android.content.Intent(this, ShopActivity.class).putExtra("tab", "boards"))));
        }
        bc.addView(br);
        col.addView(bc);

        if (isPublic) {
            col.addView(Ui.muted(this, "The match starts automatically when the room is full (or after a short wait with at least 2 players)."));
        } else if (isHost) {
            TextView start = Ui.button(this, "▶ Start match", "primary", v -> app.socket.emit("room:start", new JSONObject(), PlayFlow.toastOnError()));
            int need = teams ? 4 : 2;
            Ui.setEnabled(start, seats.length() >= need && everyoneReady);
            col.addView(start);
            TextView hint = Ui.muted(this, seats.length() < need ? (teams ? "2 vs 2 needs 4 players — invite friends." : "Invite a friend to begin.")
                    : everyoneReady ? "Everyone is ready!" : "Waiting for players to get ready…");
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, Ui.dp(this, 8), 0, 0);
            col.addView(hint);
        } else if (me != null) {
            boolean ready = me.optBoolean("ready");
            col.addView(Ui.button(this, ready ? "Not ready" : "✓ I'm ready", ready ? "glass" : "primary",
                    v -> app.socket.emit("room:ready", PlayFlow.obj("ready", !ready), PlayFlow.toastOnError())));
        }

        if (!isPublic) inviteFriends(room.optString("code"));

        TextView leave = Ui.button(this, fee > 0 ? "Leave & refund" : "Leave room", "ghost", v -> leave());
        Ui.margins(leave, this, 0, 16, 0, 0);
        col.addView(leave);
    }

    private android.view.View colorDot(String color) {
        android.view.View v = new android.view.View(this);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(Board.colorArgb(color));
        v.setBackground(g);
        int s = Ui.dp(this, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.rightMargin = Ui.dp(this, 4);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView tagGap(TextView t) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 4);
        t.setLayoutParams(lp);
        return t;
    }

    private void inviteFriends(String code) {
        col.addView(Ui.heading(this, "Invite friends"));
        LinearLayout c = Ui.card(this);
        c.addView(Ui.spinner(this));
        col.addView(c);
        app.api.get("/friends", new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                c.removeAllViews();
                JSONArray fs = r.optJSONArray("friends");
                if (fs == null || fs.length() == 0) {
                    c.addView(Ui.muted(RoomActivity.this, "Add friends to invite them here, or share the room code."));
                    return;
                }
                for (int i = 0; i < fs.length(); i++) {
                    JSONObject f = fs.optJSONObject(i);
                    LinearLayout row = Ui.row(RoomActivity.this);
                    row.addView(Ui.avatar(RoomActivity.this, f, f.optString("username"), 32));
                    TextView n = Ui.text(RoomActivity.this, f.optString("username") + (f.optBoolean("online") ? "  🟢" : ""), 15, Ui.INK);
                    n.setPadding(Ui.dp(RoomActivity.this, 10), 0, 0, 0);
                    row.addView(n, Ui.weight(1));
                    row.addView(Ui.smallButton(RoomActivity.this, "Invite", "glass", v -> {
                        Ui.setEnabled(v, false);
                        app.api.post("/friends/invite", PlayFlow.obj("publicId", f.optString("publicId")), new Api.Cb(RoomActivity.this) {
                            @Override
                            public void ok(JSONObject res) {
                                LudoApp.toast("Invite sent to " + f.optString("username"));
                            }

                            @Override
                            public void err(Api.Error e) {
                                Ui.setEnabled(v, true);
                                LudoApp.toast(e.message);
                            }
                        });
                    }));
                    c.addView(row);
                }
            }

            @Override
            public void err(Api.Error e) {
                c.removeAllViews();
                c.addView(Ui.muted(RoomActivity.this, e.message));
            }
        });
    }

    private void share(String code, String modeName) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, "Join my " + modeName + " room in LudoX! Room code: " + code);
        startActivity(Intent.createChooser(i, "Share room code"));
    }

    // Back only closes this screen and keeps your seat; leaving (and any refund) is the Leave button.
    void leave() {
        app.socket.leave(res -> finish());
    }
}
