package com.ludox.game.ui;

import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludox.game.LudoApp;
import com.ludox.game.net.Api;
import com.ludox.game.net.GameSocket;

import org.json.JSONObject;

/**
 * Entering a match: quick play, creating a room, or joining by code. Paid entries are
 * confirmed first (fee, winner payout, balance after) exactly like the website; the
 * server charges the official fee when the seat is taken.
 */
final class PlayFlow {
    private final BaseActivity a;
    private final LudoApp app;

    PlayFlow(BaseActivity a) {
        this.a = a;
        this.app = LudoApp.get();
    }

    void quick(JSONObject mode) {
        confirm("Join " + mode.optString("name"), mode.optLong("entryFee"),
                "You'll be matched with other players choosing " + mode.optString("name") + ". The match starts when the room fills.",
                "join", () -> emitRoom("room:quick", obj("modeId", mode.optString("id"))));
    }

    /** Create a private room; `teams` makes it a 2 vs 2 room (4 players). */
    void create(JSONObject mode, int maxPlayers, boolean teams) {
        JSONObject p = obj("modeId", mode.optString("id"));
        try {
            p.put("maxPlayers", maxPlayers);
            p.put("teams", teams);
        } catch (Exception ignored) {
            // not reachable
        }
        confirm("Create " + (teams ? "2 vs 2 " : "") + roomName(mode.optString("name")), mode.optLong("entryFee"), null, "create", () -> emitRoom("room:create", p));
    }

    /** Look up a room code, show what it costs, then join. */
    void joinCode(String raw) {
        String code = raw.trim().toUpperCase();
        if (code.length() < 4) {
            LudoApp.toast("Enter the room code");
            return;
        }
        app.api.get("/rooms/" + android.net.Uri.encode(code), new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                JSONObject room = r.optJSONObject("room");
                if (room == null) return;
                if (!room.optBoolean("joinable")) {
                    a.alert("Can't join", "This room is full or the match has already started.");
                    return;
                }
                JSONObject mode = room.optJSONObject("mode");
                String name = mode != null ? mode.optString("name") : "room";
                String info = room.optInt("players") + "/" + room.optInt("maxPlayers") + " players"
                        + (room.isNull("hostName") ? "" : " · host " + room.optString("hostName"));
                confirm("Join " + name, room.optLong("entryFee"), info, "join", () -> emitRoom("room:join", obj("code", code)));
            }
        });
    }

    private void confirm(String title, long fee, String note, String verb, Runnable go) {
        if (fee > app.balance()) {
            insufficient();
            return;
        }
        LinearLayout box = Ui.vbox(a);
        if (fee > 0) {
            box.addView(line("Entry fee", Ui.coins(fee)));
            box.addView(line("Winner receives", Ui.coins(Math.round(fee * app.payoutMultiplier()))));
            box.addView(line("Your balance after paying", Ui.fmt(app.balance() - fee)));
            TextView hint = Ui.muted(a, "The fee is refunded if you leave before the match starts. It is not refunded after the start.");
            hint.setPadding(0, Ui.dp(a, 10), 0, 0);
            box.addView(hint);
        } else {
            box.addView(Ui.muted(a, "This room is free — no coins are at stake."));
        }
        if (note != null) {
            TextView n = Ui.muted(a, note);
            n.setPadding(0, Ui.dp(a, 8), 0, 0);
            box.addView(n);
        }
        String label = fee > 0 ? "Pay " + Ui.fmt(fee) + " & " + verb : verb.equals("create") ? "Create room" : "Join";
        a.dialog(title, box, label, go);
    }

    private LinearLayout line(String k, String v) {
        LinearLayout r = Ui.row(a);
        r.addView(Ui.muted(a, k), Ui.weight(1));
        r.addView(Ui.bold(Ui.text(a, v, 15, Ui.INK)));
        return r;
    }

    void insufficient() {
        Popup.message(a, "Insufficient Coins!", "🪙\nPlay more matches or collect your free daily bonus.",
                Popup.plain("Close", null),
                new Popup.Btn("Open wallet", "gold", () -> a.startActivity(new android.content.Intent(a, WalletActivity.class))));
    }

    private void emitRoom(String event, JSONObject payload) {
        whenConnected(() -> app.socket.emit(event, payload, res -> {
            if (res.optBoolean("ok")) {
                a.openRoom();
            } else if ("INSUFFICIENT_COINS".equals(res.optString("code"))) {
                insufficient();
            } else {
                a.alert("Couldn't join", res.optString("error", "Something went wrong"));
            }
        }));
    }

    /** Run once the game socket is connected (waits up to ~8 s after sign-in or a network change). */
    void whenConnected(Runnable r) {
        if (app.socket.isConnected()) {
            r.run();
            return;
        }
        app.socket.connect();
        final long until = System.currentTimeMillis() + 8000;
        LudoApp.MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (app.socket.isConnected()) r.run();
                else if (System.currentTimeMillis() < until) LudoApp.MAIN.postDelayed(this, 250);
                else a.alert("Not connected", "Cannot reach the game server. Check your connection and try again.");
            }
        }, 250);
    }

    /** "Classic Match" -> "Classic Match room", but "Friendly Room" stays as is. */
    static String roomName(String modeName) {
        return modeName.toLowerCase().endsWith("room") ? modeName : modeName + " room";
    }

    static JSONObject obj(Object... kv) {
        try {
            JSONObject o = new JSONObject();
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static boolean ok(JSONObject res) {
        return res != null && res.optBoolean("ok");
    }

    static GameSocket.AckCb toastOnError() {
        return res -> {
            if (!ok(res)) LudoApp.toast(res.optString("error", "Something went wrong"));
        };
    }
}
