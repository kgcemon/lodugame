package com.ludox.game.ui;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludox.game.net.Api;

import org.json.JSONArray;
import org.json.JSONObject;

/** Recent notifications; room invites can be joined from here. Opening the list marks all read. */
public class NotificationsActivity extends BaseActivity {
    private LinearLayout col;
    private PlayFlow play;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        play = new PlayFlow(this);
        col = pageWithBack("Notifications");
        load();
    }

    private void load() {
        col.removeAllViews();
        col.addView(Ui.spinner(this));
        app.api.get("/notifications", new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                col.removeAllViews();
                JSONArray items = r.optJSONArray("items");
                if (items == null || items.length() == 0) {
                    col.addView(empty("No notifications", "Friend requests, invites and match results show up here."));
                } else {
                    LinearLayout c = Ui.card(NotificationsActivity.this);
                    for (int i = 0; i < items.length(); i++) c.addView(row(items.optJSONObject(i)));
                    col.addView(c);
                }
                if (r.optInt("unread") > 0) markRead();
                app.prefs.edit().putInt("unread_notifications", 0).apply();
            }
        });
    }

    private LinearLayout row(JSONObject n) {
        LinearLayout row = Ui.row(this);
        JSONObject p = n.optJSONObject("payload");
        if (p == null) p = new JSONObject();
        String type = n.optString("type");
        LinearLayout t = Ui.vbox(this);
        String text = Notifications.describe(type, p);
        boolean unread = n.isNull("readAt");
        t.addView(Ui.text(this, (unread ? "● " : "") + (text != null ? text : type), 14, unread ? Ui.INK : Ui.MUTED));
        t.addView(Ui.text(this, Ui.timeAgo(n.optString("createdAt")), 12, Ui.MUTED));
        row.addView(t, Ui.weight(1));
        if ("room_invite".equals(type)) {
            String code = p.optString("code");
            TextView join = Ui.smallButton(this, "Join", "primary", v -> play.joinCode(code));
            row.addView(join);
        }
        if (p.has("giftId")) {
            String gift = p.optString("giftId");
            row.addView(Ui.smallButton(this, "Open", "primary", v -> GiftOpening.open(this, gift, null)));
        }
        return row;
    }

    private void markRead() {
        app.api.post("/notifications/read", null, new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {}

            @Override
            public void err(Api.Error e) {}
        });
    }

    @Override
    public void onSocketEvent(String event, Object data) {
        super.onSocketEvent(event, data);
        if ("notify".equals(event)) load();
    }
}
