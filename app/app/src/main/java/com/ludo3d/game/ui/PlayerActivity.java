package com.ludo3d.game.ui;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.net.Api;

import org.json.JSONObject;

/** A player's public profile and stats, with an "Add friend" button. */
public class PlayerActivity extends BaseActivity {
    private LinearLayout col;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        col = pageWithBack("Player");
        col.addView(Ui.spinner(this));
        load();
    }

    private void load() {
        String id = getIntent().getStringExtra("publicId");
        app.api.get("/players/" + android.net.Uri.encode(id == null ? "" : id), new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                render(r.optJSONObject("player"));
            }

            @Override
            public void err(Api.Error e) {
                col.removeAllViews();
                col.addView(empty("Player not found", e.message));
            }
        });
    }

    private void render(JSONObject p) {
        col.removeAllViews();
        if (p == null) return;
        LinearLayout head = Ui.vbox(this);
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        head.addView(Ui.avatar(this, p, p.optString("username"), 84));
        TextView name = Ui.title(this, Flags.named(p, p.optString("username")));
        name.setPadding(0, Ui.dp(this, 10), 0, 0);
        head.addView(name);
        head.addView(Ui.muted(this, "ID " + p.optString("publicId") + " · " + (p.optBoolean("online") ? "🟢 online" : "offline")));
        head.addView(Ui.muted(this, "Joined " + Ui.date(p.optString("joinedAt"))));
        Ui.margins(head, this, 0, 4, 0, 16);
        col.addView(head);

        JSONObject s = p.optJSONObject("stats");
        if (s != null) {
            LinearLayout c = Ui.card(this);
            c.addView(line("Rank", "#" + s.optInt("rank")));
            c.addView(line("Matches", Ui.fmt(s.optLong("matches"))));
            c.addView(line("Wins", Ui.fmt(s.optLong("wins"))));
            c.addView(line("Losses", Ui.fmt(s.optLong("losses"))));
            c.addView(line("Win rate", s.optDouble("winRate") + "%"));
            c.addView(line("Season points", Ui.fmt(s.optLong("seasonPoints"))));
            col.addView(c);
        }
        JSONObject f = p.optJSONObject("friendship");
        if (f != null) col.addView(friendButton(p, f));
    }

    /** Add friend; once sent it becomes Cancel; Accept if they asked first; nothing once friends. */
    private android.view.View friendButton(JSONObject p, JSONObject f) {
        String pending = f.isNull("pending") ? "" : f.optString("pending");
        if (f.optBoolean("isFriend")) return Ui.tag(this, "✓ FRIENDS", Ui.SUCCESS);
        if ("outgoing".equals(pending)) {
            return Ui.button(this, "✖ Cancel friend request", "danger", v -> respond(f.optString("requestId"), "cancel", "Friend request cancelled", null));
        }
        if ("incoming".equals(pending)) {
            return Ui.button(this, "✓ Accept friend request", "ok", v -> respond(f.optString("requestId"), "accept", null, p));
        }
        if (f.optInt("sendsLeft", 1) <= 0) return Ui.muted(this, "You can send a friend request only twice.");
        return Ui.button(this, "＋ Add friend", "blue", v -> app.api.post("/friends/requests", PlayFlow.obj("player", p.optString("publicId")), new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                if (r.optBoolean("accepted")) FriendPopups.celebrate(PlayerActivity.this, p);
                else LudoApp.toast("Friend request sent 💌");
                load();
            }
        }));
    }

    private void respond(String id, String action, String done, JSONObject celebrateWith) {
        app.api.post("/friends/requests/" + id + "/" + action, null, new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                if (done != null) LudoApp.toast(done);
                if (celebrateWith != null) FriendPopups.celebrate(PlayerActivity.this, celebrateWith);
                load();
            }
        });
    }

    private LinearLayout line(String k, String v) {
        LinearLayout r = Ui.row(this);
        r.addView(Ui.muted(this, k), Ui.weight(1));
        r.addView(Ui.bold(Ui.text(this, v, 15, Ui.INK)));
        return r;
    }
}
