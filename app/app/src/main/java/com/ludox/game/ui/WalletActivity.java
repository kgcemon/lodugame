package com.ludox.game.ui;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludox.game.LudoApp;
import com.ludox.game.net.Api;

import org.json.JSONArray;
import org.json.JSONObject;

/** Balance, daily bonus and the full coin ledger (filterable, paged). */
public class WalletActivity extends BaseActivity {
    private static final String[][] TYPES = {
            {"", "All"}, {"welcome_bonus", "Welcome"}, {"daily_bonus", "Daily"}, {"match_entry", "Entry fees"},
            {"match_reward", "Rewards"}, {"match_refund", "Refunds"}, {"shop_purchase", "Shop"}, {"player_gift", "Gifts sent"}, {"admin_adjustment", "Adjustments"},
    };

    private LinearLayout col;
    private String type = "";
    private int page = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        col = pageWithBack("Wallet");
        load();
    }

    private void load() {
        col.removeAllViews();
        col.addView(Ui.spinner(this));
        app.api.get("/wallet", new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                app.mergeWallet(r.optJSONObject("wallet"));
                render();
            }
        });
    }

    private void render() {
        col.removeAllViews();
        JSONObject w = app.wallet;
        LinearLayout bal = Ui.card(this);
        bal.setGravity(Gravity.CENTER_HORIZONTAL);
        bal.addView(Ui.muted(this, "Balance"));
        bal.addView(Ui.bold(Ui.text(this, Ui.coins(w.optLong("balance")), 34, Ui.GOLD)));
        LinearLayout totals = Ui.hbox(this);
        Ui.margins(totals, this, 0, 10, 0, 0);
        TextView earned = Ui.muted(this, "Earned " + Ui.fmt(w.optLong("totalEarned")));
        earned.setGravity(Gravity.CENTER);
        TextView spent = Ui.muted(this, "Spent " + Ui.fmt(w.optLong("totalSpent")));
        spent.setGravity(Gravity.CENTER);
        totals.addView(earned, Ui.weight(1));
        totals.addView(spent, Ui.weight(1));
        bal.addView(totals);
        JSONObject d = w.optJSONObject("dailyBonus");
        if (d != null && d.optLong("amount") > 0) {
            TextView b = Ui.button(this, d.optBoolean("claimedToday") ? "Daily bonus collected ✓" : "🎁 Collect " + Ui.fmt(d.optLong("amount")) + " free coins",
                    "primary", v -> claim());
            Ui.setEnabled(b, !d.optBoolean("claimedToday"));
            Ui.margins(b, this, 0, 14, 0, 0);
            bal.addView(b);
        }
        col.addView(bal);
        col.addView(Ui.muted(this, "Coins are for play only: they can't be bought or withdrawn."));

        col.addView(Ui.heading(this, "Transactions"));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = Ui.hbox(this);
        for (String[] t : TYPES) {
            TextView c = Ui.smallButton(this, t[1], t[0].equals(type) ? "primary" : "glass", v -> {
                type = t[0];
                page = 1;
                render();
            });
            chips.addView(c);
            chips.addView(Ui.gap(this, 6));
        }
        hs.addView(chips);
        col.addView(hs);
        LinearLayout list = Ui.card(this);
        Ui.margins(list, this, 0, 10, 0, 0);
        list.addView(Ui.spinner(this));
        col.addView(list);
        app.api.get("/wallet/transactions?page=" + page + (type.isEmpty() ? "" : "&type=" + type), new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                list.removeAllViews();
                JSONArray items = r.optJSONArray("items");
                if (items == null || items.length() == 0) {
                    list.addView(empty("No transactions", null));
                    return;
                }
                for (int i = 0; i < items.length(); i++) {
                    JSONObject t = items.optJSONObject(i);
                    LinearLayout row = Ui.row(WalletActivity.this);
                    LinearLayout left = Ui.vbox(WalletActivity.this);
                    String note = t.isNull("note") ? label(t.optString("type")) : t.optString("note");
                    left.addView(Ui.text(WalletActivity.this, note, 14, Ui.INK));
                    left.addView(Ui.text(WalletActivity.this, Ui.date(t.optString("created_at")) + " · balance " + Ui.fmt(t.optLong("balance_after")), 12, Ui.MUTED));
                    row.addView(left, Ui.weight(1));
                    long amt = t.optLong("amount");
                    row.addView(Ui.bold(Ui.text(WalletActivity.this, (amt > 0 ? "+" : "") + Ui.fmt(amt), 15, amt > 0 ? Ui.SUCCESS : Ui.DANGER)));
                    list.addView(row);
                }
                int total = r.optInt("total");
                int pages = Math.max(1, (total + 19) / 20);
                if (pages > 1) {
                    LinearLayout pager = Ui.hbox(WalletActivity.this);
                    pager.setGravity(Gravity.CENTER);
                    TextView prev = Ui.smallButton(WalletActivity.this, "‹ Prev", "glass", v -> {
                        page--;
                        render();
                    });
                    Ui.setEnabled(prev, page > 1);
                    TextView next = Ui.smallButton(WalletActivity.this, "Next ›", "glass", v -> {
                        page++;
                        render();
                    });
                    Ui.setEnabled(next, page < pages);
                    pager.addView(prev);
                    pager.addView(Ui.muted(WalletActivity.this, "  " + page + " / " + pages + "  "));
                    pager.addView(next);
                    list.addView(pager);
                }
            }
        });
    }

    private static String label(String type) {
        for (String[] t : TYPES) if (t[0].equals(type)) return t[1];
        return type;
    }

    private void claim() {
        app.api.post("/wallet/daily-bonus", null, new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                app.mergeWallet(r.optJSONObject("wallet"));
                render();
                RewardFx.Play p = new RewardFx.Play();
                p.reward = r.optJSONObject("reward");
                RewardFx.play(root, p);
            }
        });
    }

    @Override
    public void onSocketEvent(String event, Object data) {
        super.onSocketEvent(event, data);
        if ("wallet".equals(event)) render();
    }
}
