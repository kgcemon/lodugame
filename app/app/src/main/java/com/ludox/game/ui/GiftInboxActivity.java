package com.ludox.game.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludox.game.net.Api;

import org.json.JSONArray;
import org.json.JSONObject;

/** Every gift this player was sent, newest first, claimed or not. Opening one is the full-screen moment. */
public class GiftInboxActivity extends BaseActivity {
    private LinearLayout col;
    private int shown = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        col = pageWithBack("Gift Inbox");
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        if (col.getChildCount() == 0) col.addView(Ui.spinner(this));
        app.api.get("/gifts?page=" + shown, new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                col.removeAllViews();
                app.unclaimedGifts = r.optInt("unclaimed");
                JSONArray items = r.optJSONArray("items");
                if (items == null || items.length() == 0) {
                    col.addView(empty("No gifts yet", "Gifts from LudoX land here, and they wait for you."));
                    return;
                }
                LinearLayout card = Ui.card(GiftInboxActivity.this);
                for (int i = 0; i < items.length(); i++) card.addView(row(items.optJSONObject(i)));
                col.addView(card);
                int pages = Math.max(1, (r.optInt("total") + r.optInt("pageSize", 20) - 1) / r.optInt("pageSize", 20));
                if (pages > 1) {
                    LinearLayout pager = Ui.hbox(GiftInboxActivity.this);
                    pager.setGravity(Gravity.CENTER);
                    TextView prev = Ui.smallButton(GiftInboxActivity.this, "‹ Newer", "glass", v -> {
                        shown--;
                        load();
                    });
                    Ui.setEnabled(prev, shown > 1);
                    TextView next = Ui.smallButton(GiftInboxActivity.this, "Older ›", "glass", v -> {
                        shown++;
                        load();
                    });
                    Ui.setEnabled(next, shown < pages);
                    pager.addView(prev);
                    pager.addView(Ui.muted(GiftInboxActivity.this, "  " + shown + " / " + pages + "  "));
                    pager.addView(next);
                    Ui.margins(pager, GiftInboxActivity.this, 0, 10, 0, 0);
                    col.addView(pager);
                }
            }
        });
    }

    private LinearLayout row(JSONObject g) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String status = g.optString("status");
        JSONObject c = g.optJSONObject("content");
        JSONObject box = g.optJSONObject("box");
        TextView icon = Ui.text(this, icon(c), 28, Color.WHITE);
        icon.setPadding(0, 0, Ui.dp(this, 10), 0);
        row.addView(icon);
        LinearLayout t = Ui.vbox(this);
        boolean waiting = "pending".equals(status) || "opened".equals(status);
        t.addView(Ui.bold(Ui.text(this, c != null ? title(c) : box != null ? box.optString("label") : "A gift", 15, waiting ? Ui.INK : Ui.MUTED)));
        String msg = g.optString("message");
        if (!msg.isEmpty()) t.addView(Ui.text(this, "“" + msg + "”", 12, Ui.MUTED));
        JSONObject got = g.optJSONObject("received");
        if (got != null && got.optBoolean("duplicate")) {
            long n = got.optLong("amount");
            t.addView(Ui.text(this, n > 0 ? "Already owned: you got " + Ui.fmt(n) + " " + got.optString("kind") + " instead" : "Already owned", 12, Ui.GOLD));
        }
        String when = "From LudoX · " + Ui.timeAgo(g.optString("createdAt"));
        if (waiting && !g.isNull("expiresAt")) when += " · expires " + Ui.date(g.optString("expiresAt"));
        t.addView(Ui.text(this, when, 12, Ui.MUTED));
        row.addView(t, Ui.weight(1));
        switch (status) {
            case "pending":
            case "opened":
                row.addView(Ui.smallButton(this, "pending".equals(status) ? "Open" : "Claim", "primary", v -> GiftOpening.show(this, g, this::load)));
                break;
            case "claimed": {
                LinearLayout right = Ui.vbox(this);
                right.setGravity(Gravity.CENTER_HORIZONTAL);
                right.addView(Ui.tag(this, "CLAIMED ✓", Ui.SUCCESS));
                TextView replay = Ui.smallButton(this, "Replay", "glass", v -> GiftOpening.show(this, g, this::load));
                Ui.margins(replay, this, 0, 4, 0, 0);
                right.addView(replay);
                row.addView(right);
                break;
            }
            case "expired":
                row.addView(Ui.tag(this, "EXPIRED", Ui.MUTED));
                break;
            default:
                row.addView(Ui.tag(this, "CANCELLED", Ui.DANGER));
                break;
        }
        return row;
    }

    private static String icon(JSONObject c) {
        if (c == null) return "🎁";
        switch (c.optString("kind")) {
            case "coins": return "🪙";
            case "diamonds": return "💎";
            case "dice": return "🎲";
            case "board": return "👑";
            default: return "🖼️";
        }
    }

    private static String title(JSONObject c) {
        String kind = c.optString("kind");
        if ("coins".equals(kind)) return Ui.fmt(c.optLong("quantity")) + " coins";
        if ("diamonds".equals(kind)) return Ui.fmt(c.optLong("quantity")) + " diamonds";
        return c.optString("name") + ("dice".equals(kind) ? " dice" : "board".equals(kind) ? " board" : " frame");
    }

    @Override
    public void onSocketEvent(String event, Object data) {
        super.onSocketEvent(event, data);
        if ("gift:new".equals(event) || "gift:update".equals(event)) load();
    }
}
