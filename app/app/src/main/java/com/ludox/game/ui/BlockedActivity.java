package com.ludox.game.ui;

import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;

import com.ludox.game.LudoApp;
import com.ludox.game.net.Api;

import org.json.JSONArray;
import org.json.JSONObject;

/** The players I blocked, with a way to unblock each. */
public class BlockedActivity extends BaseActivity {
    private LinearLayout col;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        col = pageWithBack("Blocked players");
        load();
    }

    private void load() {
        col.removeAllViews();
        col.addView(Ui.spinner(this));
        app.api.get("/blocks", new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                col.removeAllViews();
                JSONArray items = r.optJSONArray("items");
                if (items == null || items.length() == 0) {
                    col.addView(empty("Nobody is blocked", "You can block a player from their card or profile."));
                    return;
                }
                for (int i = 0; i < items.length(); i++) col.addView(row(items.optJSONObject(i)));
            }

            @Override
            public void err(Api.Error e) {
                col.removeAllViews();
                col.addView(empty("Couldn't load the list", e.message));
            }
        });
    }

    private LinearLayout row(JSONObject p) {
        String name = p.optString("username");
        LinearLayout c = Ui.card(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.addView(Ui.avatar(this, p, name, 40));
        LinearLayout who = Ui.vbox(this);
        who.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        who.addView(Ui.bold(Ui.text(this, Flags.named(p, name), 15, Ui.INK)));
        who.addView(Ui.muted(this, "ID " + p.optString("publicId")));
        c.addView(who, Ui.weight(1));
        c.addView(Ui.smallButton(this, "Unblock", "glass", v -> app.api.delete("/blocks/" + Uri.encode(p.optString("publicId")), new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                LudoApp.toast(name + " is unblocked");
                load();
            }
        })));
        return c;
    }
}
