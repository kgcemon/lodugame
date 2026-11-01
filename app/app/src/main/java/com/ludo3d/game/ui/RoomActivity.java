package com.ludo3d.game.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.game.Board;
import com.ludo3d.game.net.Api;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The waiting room: seats, ready states, host controls (start, add bot in free rooms,
 * remove players), the room code to share and inviting friends. Quick-match rooms start
 * by themselves when full.
 */
public class RoomActivity extends BaseActivity {
    private LinearLayout col;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        col = pageWithBack("Room");
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (app.socket.room == null) finish();
        else render();
    }

    @Override
    public void onSocketEvent(String event, Object data) {
        super.onSocketEvent(event, data);
        switch (event) {
            case "room:state":
                render();
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

    private void alertAndClose(String reason) {
        Popup p = Popup.message(this, "Room closed", reason, Popup.ok("OK", this::finish));
        p.setOnCancelListener(d -> finish());
    }

    private void render() {
        JSONObject room = app.socket.room;
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
        col.addView(Ui.heading(this, "Players " + seats.length() + "/" + maxPlayers));
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
                    : Ui.avatar(this, s.optString("name"), s.optInt("avatarHue", 210), 38));
            LinearLayout t = Ui.vbox(this);
            t.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 6), 0);
            t.addView(Ui.bold(Ui.text(this, s.optString("name") + (i == you ? " (you)" : ""), 15, Ui.INK)));
            LinearLayout tags = Ui.hbox(this);
            tags.addView(colorDot(s.optString("color")));
            if (s.optBoolean("isHost")) tags.addView(tagGap(Ui.tag(this, "👑 HOST", Ui.GOLD)));
            if (bot) tags.addView(tagGap(Ui.tag(this, "BOT", Ui.MUTED)));
            if (!s.optBoolean("connected")) tags.addView(tagGap(Ui.tag(this, s.optBoolean("ready") ? "OFFLINE" : "JOINING", Ui.DANGER)));
            t.addView(tags);
            row.addView(t, Ui.weight(1));
            row.addView(Ui.text(this, s.optBoolean("ready") ? "✓ Ready" : "Not ready", 13, s.optBoolean("ready") ? Ui.SUCCESS : Ui.MUTED));
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

        if (isPublic) {
            col.addView(Ui.muted(this, "The match starts automatically when the room is full (or after a short wait with at least 2 players)."));
        } else if (isHost) {
            TextView start = Ui.button(this, "▶ Start match", "primary", v -> app.socket.emit("room:start", new JSONObject(), PlayFlow.toastOnError()));
            Ui.setEnabled(start, seats.length() >= 2 && everyoneReady);
            col.addView(start);
            TextView hint = Ui.muted(this, seats.length() < 2 ? "Invite a friend to begin." : everyoneReady ? "Everyone is ready!" : "Waiting for players to get ready…");
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
                    row.addView(Ui.avatar(RoomActivity.this, f.optString("username"), f.optInt("avatarHue", 210), 32));
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
        i.putExtra(Intent.EXTRA_TEXT, "Join my " + modeName + " room in Ludo 3D! Room code: " + code);
        startActivity(Intent.createChooser(i, "Share room code"));
    }

    // Back only closes this screen and keeps your seat; leaving (and any refund) is the Leave button.
    private void leave() {
        app.socket.leave(res -> finish());
    }
}
