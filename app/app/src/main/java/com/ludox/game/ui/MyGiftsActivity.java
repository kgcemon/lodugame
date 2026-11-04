package com.ludox.game.ui;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludox.game.LudoApp;
import com.ludox.game.game.Social;
import com.ludox.game.net.Api;

import org.json.JSONArray;
import org.json.JSONObject;

/** My Gifts: what players gave me (my collection, a few of them on my profile) and what I sent. */
public class MyGiftsActivity extends BaseActivity {
    private LinearLayout col, list;
    private TextView receivedTab, sentTab;
    private String dir = "received";
    private int page = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        col = pageWithBack("My Gifts");
        LinearLayout tabs = Ui.hbox(this);
        receivedTab = tab("🎁 Received", "received");
        sentTab = tab("📤 Sent", "sent");
        tabs.addView(receivedTab, Ui.weight(1));
        LinearLayout.LayoutParams l = Ui.weight(1);
        l.leftMargin = Ui.dp(this, 8);
        tabs.addView(sentTab, l);
        col.addView(tabs, Ui.lpMatchWrap());
        list = Ui.vbox(this);
        Ui.margins(list, this, 0, 10, 0, 0);
        col.addView(list, Ui.lpMatchWrap());
    }

    private TextView tab(String label, String which) {
        TextView t = Ui.bold(Ui.text(this, label, 15, Ui.INK));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        t.setOnClickListener(v -> {
            dir = which;
            page = 1;
            load();
        });
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        receivedTab.setBackground(Ui.round("received".equals(dir) ? 0xff6d28d9 : 0x33ffffff, Ui.dp(this, 12)));
        sentTab.setBackground(Ui.round("sent".equals(dir) ? 0xff6d28d9 : 0x33ffffff, Ui.dp(this, 12)));
        list.removeAllViews();
        list.addView(Ui.spinner(this));
        String want = dir;
        app.api.get("/player-gifts?dir=" + want + "&page=" + page, new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                if (!want.equals(dir)) return;
                list.removeAllViews();
                JSONArray items = r.optJSONArray("items");
                if (items == null || items.length() == 0) {
                    TextView none = Ui.muted(MyGiftsActivity.this, "sent".equals(want) ? "Gifts you send during a match are listed here." : "Gifts players send you during a match are kept here.");
                    none.setGravity(Gravity.CENTER);
                    none.setPadding(0, Ui.dp(MyGiftsActivity.this, 40), 0, 0);
                    list.addView(none);
                    return;
                }
                if ("received".equals(want)) {
                    TextView hint = Ui.muted(MyGiftsActivity.this, "Show up to " + r.optInt("maxDisplayed", 6) + " on your profile. Tap a gift to see it again.");
                    hint.setPadding(0, 0, 0, Ui.dp(MyGiftsActivity.this, 6));
                    list.addView(hint);
                }
                LinearLayout card = Ui.card(MyGiftsActivity.this);
                for (int i = 0; i < items.length(); i++) card.addView(row(items.optJSONObject(i), want));
                list.addView(card);
                int pages = Math.max(1, (r.optInt("total") + r.optInt("pageSize", 30) - 1) / r.optInt("pageSize", 30));
                if (pages > 1) list.addView(pager(pages));
            }
        });
    }

    private LinearLayout pager(int pages) {
        LinearLayout p = Ui.hbox(this);
        p.setGravity(Gravity.CENTER);
        TextView prev = Ui.smallButton(this, "‹ Newer", "glass", v -> {
            page--;
            load();
        });
        Ui.setEnabled(prev, page > 1);
        TextView next = Ui.smallButton(this, "Older ›", "glass", v -> {
            page++;
            load();
        });
        Ui.setEnabled(next, page < pages);
        p.addView(prev);
        p.addView(Ui.muted(this, "  " + page + " / " + pages + "  "));
        p.addView(next);
        Ui.margins(p, this, 0, 10, 0, 14);
        return p;
    }

    private LinearLayout row(JSONObject g, String which) {
        LinearLayout r = Ui.row(this);
        ImageView pic = GiftShopPanel.thumb(this, g.optString("model"));
        r.addView(pic, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        LinearLayout t = Ui.vbox(this);
        t.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 6), 0);
        t.addView(Ui.bold(Ui.text(this, g.optString("name"), 15, Ui.INK)));
        JSONObject who = g.optJSONObject("player");
        String name = who != null ? who.optString("username") : "A player";
        String when = Ui.timeAgo(g.optString("createdAt"));
        if ("sent".equals(which)) {
            String price = ("diamonds".equals(g.optString("currency")) ? "💎 " : "🪙 ") + Ui.fmt(g.optLong("price"));
            t.addView(Ui.muted(this, "To " + name + " · " + price + " · " + when));
        } else {
            t.addView(Ui.muted(this, "From " + name + " · " + when));
        }
        String rarity = g.optString("rarity");
        LinearLayout tags = Ui.hbox(this);
        tags.addView(Ui.text(this, GiftShopPanel.rarityLabel(rarity), 12, GiftShopPanel.rarityColor(rarity)));
        if (!g.isNull("reaction") && g.has("reaction")) tags.addView(Ui.text(this, "   " + Social.emoji(g.optString("reaction")), 13, Ui.INK));
        if ("received".equals(which)) tags.addView(Ui.text(this, "   In collection", 12, Ui.SUCCESS));
        t.addView(tags);
        r.addView(t, Ui.weight(1));
        if ("received".equals(which)) {
            boolean shown = g.optBoolean("displayed");
            r.addView(Ui.smallButton(this, shown ? "★ On profile" : "☆ Show", shown ? "gold" : "glass", v -> setShown(g, !shown)));
        }
        boolean mine = "received".equals(which);
        r.setOnClickListener(v -> GiftReveal.show(this, g, mine ? name : null, mine && g.isNull("reaction")));
        LinearLayout.LayoutParams lp = Ui.lpMatchWrap();
        lp.bottomMargin = Ui.dp(this, 6);
        r.setLayoutParams(lp);
        return r;
    }

    private void setShown(JSONObject g, boolean on) {
        app.api.post("/player-gifts/" + g.optString("id") + "/display", PlayFlow.obj("displayed", on), new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                load();
            }

            @Override
            public void err(Api.Error e) {
                LudoApp.toast(e.message);
            }
        });
    }
}
