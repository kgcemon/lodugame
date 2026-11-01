package com.ludo3d.game.ui;

import android.content.Intent;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.net.Api;

import org.json.JSONArray;
import org.json.JSONObject;

/** Friends: add by username / player ID, search, requests, and the friend list (with their rooms). */
final class FriendsTab {
    private final MainActivity a;
    private final LudoApp app;
    private final LinearLayout col;

    FriendsTab(MainActivity a, FrameLayout parent) {
        this.a = a;
        this.app = LudoApp.get();
        this.col = Ui.scrollPage(a, parent);
    }

    void render() {
        LinearLayout add = Ui.card(a);
        add.addView(Ui.bold(Ui.text(a, "Add a friend", 16, Ui.INK)));
        add.addView(Ui.muted(a, "Search by username, or enter their 8-character player ID. Your ID: " + app.user.optString("publicId")));
        EditText q = Ui.input(a, "Username or player ID", InputType.TYPE_CLASS_TEXT);
        Ui.margins(q, a, 0, 10, 0, 8);
        add.addView(q);
        LinearLayout btns = Ui.hbox(a);
        LinearLayout results = Ui.vbox(a);
        LinearLayout.LayoutParams l1 = Ui.weight(1);
        l1.rightMargin = Ui.dp(a, 8);
        btns.addView(Ui.button(a, "Search", "glass", v -> search(q.getText().toString().trim(), results)), l1);
        btns.addView(Ui.button(a, "Send request", "blue", v -> sendRequest(q.getText().toString().trim())), Ui.weight(1));
        add.addView(btns);
        add.addView(results);
        col.addView(add);

        LinearLayout reqBox = Ui.vbox(a);
        col.addView(reqBox);
        col.addView(Ui.heading(a, "Your friends"));
        LinearLayout list = Ui.card(a);
        list.addView(Ui.spinner(a));
        col.addView(list);

        app.api.get("/friends/requests", new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                requests(reqBox, r.optJSONArray("incoming"), true);
                requests(reqBox, r.optJSONArray("outgoing"), false);
            }
        });
        app.api.get("/friends", new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                list.removeAllViews();
                JSONArray fs = r.optJSONArray("friends");
                if (fs == null || fs.length() == 0) {
                    list.addView(a.empty("No friends yet", "Add players by username or ID to play together."));
                    return;
                }
                for (int i = 0; i < fs.length(); i++) {
                    JSONObject f = fs.optJSONObject(i);
                    LinearLayout row = friendRow(a, f);
                    row.setOnLongClickListener(v -> {
                        confirmRemove(f);
                        return true;
                    });
                    list.addView(row);
                }
                list.addView(Ui.text(a, "Long-press a friend to remove them.", 12, Ui.MUTED));
            }

            @Override
            public void err(Api.Error e) {
                list.removeAllViews();
                list.addView(Ui.muted(a, e.message));
            }
        });
    }

    /** A friend with online state; when they're in a joinable room, a Join button. */
    static LinearLayout friendRow(MainActivity a, JSONObject f) {
        LinearLayout row = Ui.row(a);
        row.addView(Ui.avatar(a, f.optString("username"), f.optInt("avatarHue", 210), 40));
        LinearLayout t = Ui.vbox(a);
        t.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 8), 0);
        t.addView(Ui.bold(Ui.text(a, f.optString("username"), 15, Ui.INK)));
        JSONObject room = f.optJSONObject("room");
        String status = !f.optBoolean("online") ? "Offline"
                : room != null ? "In " + room.optString("modeName") + " (" + room.optInt("players") + "/" + room.optInt("maxPlayers") + ")"
                : "Online";
        t.addView(Ui.text(a, (f.optBoolean("online") ? "🟢 " : "⚪ ") + status, 12, Ui.MUTED));
        row.addView(t, Ui.weight(1));
        if (room != null && room.optBoolean("joinable") && !room.optString("code").equals(LudoApp.get().socket.room != null ? LudoApp.get().socket.room.optString("code") : "")) {
            row.addView(Ui.smallButton(a, "Join", "primary", v -> a.play.joinCode(room.optString("code"))));
        }
        row.setOnClickListener(v -> a.startActivity(new Intent(a, PlayerActivity.class).putExtra("publicId", f.optString("publicId"))));
        return row;
    }

    private void requests(LinearLayout box, JSONArray items, boolean incoming) {
        if (items == null || items.length() == 0) return;
        box.addView(Ui.heading(a, incoming ? "Friend requests" : "Sent requests"));
        LinearLayout c = Ui.card(a);
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            JSONObject p = r.optJSONObject("player");
            if (p == null) continue;
            LinearLayout row = Ui.row(a);
            row.addView(Ui.avatar(a, p.optString("username"), p.optInt("avatarHue", 210), 36));
            TextView name = Ui.bold(Ui.text(a, p.optString("username"), 15, Ui.INK));
            name.setPadding(Ui.dp(a, 12), 0, 0, 0);
            row.addView(name, Ui.weight(1));
            String id = r.optString("id");
            if (incoming) {
                row.addView(Ui.smallButton(a, "Accept", "primary", v -> act(id, "accept", p.optString("username") + " is now your friend")));
                row.addView(Ui.gap(a, 6));
                row.addView(Ui.smallButton(a, "Reject", "glass", v -> act(id, "reject", null)));
            } else {
                row.addView(Ui.smallButton(a, "Cancel", "glass", v -> act(id, "cancel", null)));
            }
            c.addView(row);
        }
        box.addView(c);
    }

    private void act(String id, String action, String okText) {
        app.api.post("/friends/requests/" + id + "/" + action, null, new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                if (okText != null) LudoApp.toast(okText);
                a.selectTab(1);
            }
        });
    }

    private void sendRequest(String player) {
        if (player.length() < 3) {
            LudoApp.toast("Enter a username or player ID");
            return;
        }
        app.api.post("/friends/requests", PlayFlow.obj("player", player), new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                LudoApp.toast(r.optBoolean("accepted") ? "You are now friends!" : "Friend request sent");
                a.selectTab(1);
            }
        });
    }

    private void search(String q, LinearLayout results) {
        results.removeAllViews();
        if (q.length() < 2) {
            LudoApp.toast("Type at least 2 characters");
            return;
        }
        app.api.get("/players/search?q=" + android.net.Uri.encode(q), new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                JSONArray ps = r.optJSONArray("players");
                if (ps == null || ps.length() == 0) {
                    results.addView(Ui.muted(a, "No players found."));
                    return;
                }
                for (int i = 0; i < ps.length(); i++) {
                    JSONObject p = ps.optJSONObject(i);
                    LinearLayout row = Ui.row(a);
                    row.addView(Ui.avatar(a, p.optString("username"), p.optInt("avatarHue", 210), 34));
                    LinearLayout t = Ui.vbox(a);
                    t.setPadding(Ui.dp(a, 10), 0, 0, 0);
                    t.addView(Ui.bold(Ui.text(a, p.optString("username"), 15, Ui.INK)));
                    t.addView(Ui.text(a, "ID " + p.optString("publicId") + (p.optBoolean("online") ? " · 🟢 online" : ""), 12, Ui.MUTED));
                    row.addView(t, Ui.weight(1));
                    if (p.optBoolean("isFriend")) row.addView(Ui.tag(a, "FRIEND", Ui.SUCCESS));
                    else if ("outgoing".equals(p.optString("pending"))) row.addView(Ui.tag(a, "SENT", Ui.MUTED));
                    else row.addView(Ui.smallButton(a, "incoming".equals(p.optString("pending")) ? "Accept" : "Add", "primary",
                                v -> sendRequest(p.optString("publicId"))));
                    results.addView(row);
                }
            }
        });
    }

    private void confirmRemove(JSONObject f) {
        Popup.message(a, "Remove friend?", "Remove " + f.optString("username") + " from your friends?",
                Popup.plain("Cancel", null),
                Popup.danger("Remove", () -> app.api.delete("/friends/" + f.optString("publicId"), new Api.Cb(a) {
                    @Override
                    public void ok(JSONObject r) {
                        LudoApp.toast(f.optString("username") + " removed");
                        a.selectTab(1);
                    }
                })));
    }
}
