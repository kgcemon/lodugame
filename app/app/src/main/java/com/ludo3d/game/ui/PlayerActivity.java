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
        head.addView(Ui.avatar(this, p.optString("username"), p.optInt("avatarHue", 210), 84));
        TextView name = Ui.title(this, p.optString("username"));
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
        if (!p.optString("publicId").equals(app.user != null ? app.user.optString("publicId") : "")) {
            col.addView(Ui.button(this, "＋ Add friend", "blue", v -> app.api.post("/friends/requests", PlayFlow.obj("player", p.optString("publicId")), new Api.Cb(this) {
                @Override
                public void ok(JSONObject r) {
                    LudoApp.toast(r.optBoolean("accepted") ? "You are now friends!" : "Friend request sent");
                }
            })));
        }
    }

    private LinearLayout line(String k, String v) {
        LinearLayout r = Ui.row(this);
        r.addView(Ui.muted(this, k), Ui.weight(1));
        r.addView(Ui.bold(Ui.text(this, v, 15, Ui.INK)));
        return r;
    }
}
