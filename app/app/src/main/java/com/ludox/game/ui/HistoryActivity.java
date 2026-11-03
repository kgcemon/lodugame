package com.ludox.game.ui;

import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludox.game.LudoApp;
import com.ludox.game.net.Api;

import org.json.JSONArray;
import org.json.JSONObject;

/** Your matches: result, coins paid / received, and reporting a problem with a match. */
public class HistoryActivity extends BaseActivity {
    private LinearLayout col;
    private int page = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        col = pageWithBack("Match history");
        load();
    }

    private void load() {
        col.removeAllViews();
        col.addView(Ui.spinner(this));
        app.api.get("/matches/history?page=" + page, new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                col.removeAllViews();
                JSONArray items = r.optJSONArray("items");
                if (items == null || items.length() == 0) {
                    col.addView(empty("No matches yet", "Your finished matches will appear here."));
                    return;
                }
                for (int i = 0; i < items.length(); i++) col.addView(card(items.optJSONObject(i)));
                int pages = Math.max(1, (r.optInt("total") + 19) / 20);
                if (pages > 1) {
                    LinearLayout pager = Ui.hbox(HistoryActivity.this);
                    pager.setGravity(Gravity.CENTER);
                    TextView prev = Ui.smallButton(HistoryActivity.this, "‹ Prev", "glass", v -> {
                        page--;
                        load();
                    });
                    Ui.setEnabled(prev, page > 1);
                    TextView next = Ui.smallButton(HistoryActivity.this, "Next ›", "glass", v -> {
                        page++;
                        load();
                    });
                    Ui.setEnabled(next, page < pages);
                    pager.addView(prev);
                    pager.addView(Ui.muted(HistoryActivity.this, "  " + page + " / " + pages + "  "));
                    pager.addView(next);
                    col.addView(pager);
                }
            }
        });
    }

    private LinearLayout card(JSONObject m) {
        LinearLayout c = Ui.card(this);
        LinearLayout top = Ui.hbox(this);
        top.addView(Ui.bold(Ui.text(this, m.optString("modeName"), 16, Ui.INK)), Ui.weight(1));
        String result = m.optString("result");
        int color = "won".equals(result) ? Ui.SUCCESS : "lost".equals(result) || "forfeited".equals(result) ? Ui.DANGER : Ui.MUTED;
        top.addView(Ui.tag(this, result.replace('_', ' ').toUpperCase(), color));
        c.addView(top);
        String rank = m.isNull("rank") ? "" : " · rank #" + m.optInt("rank");
        c.addView(Ui.muted(this, Ui.date(m.optString("date")) + " · " + m.optInt("players") + " players" + rank));
        LinearLayout money = Ui.hbox(this);
        Ui.margins(money, this, 0, 8, 0, 0);
        money.addView(Ui.text(this, "Paid " + Ui.fmt(m.optLong("coinsPaid")), 13, Ui.MUTED), Ui.weight(1));
        money.addView(Ui.text(this, "Received " + Ui.fmt(m.optLong("coinsReceived")), 13, Ui.MUTED), Ui.weight(1));
        long net = m.optLong("net");
        money.addView(Ui.bold(Ui.text(this, (net > 0 ? "+" : "") + Ui.fmt(net), 15, net > 0 ? Ui.SUCCESS : net < 0 ? Ui.DANGER : Ui.MUTED)));
        c.addView(money);
        if (m.optBoolean("disputed")) {
            c.addView(Ui.text(this, "Reported — an administrator will review it.", 12, Ui.GOLD));
        } else {
            TextView report = Ui.smallButton(this, "Report a problem", "ghost", v -> report(m.optString("id")));
            report.setGravity(Gravity.START);
            c.addView(report);
        }
        return c;
    }

    private void report(String matchId) {
        LinearLayout box = Ui.vbox(this);
        box.addView(Ui.muted(this, "Describe what went wrong (5–500 characters)."));
        EditText reason = Ui.input(this, "Reason", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        reason.setMinLines(3);
        Ui.margins(reason, this, 0, 10, 0, 0);
        box.addView(reason);
        dialog("Report this match", box, "Send report", () -> app.api.post("/matches/" + matchId + "/dispute",
                PlayFlow.obj("reason", reason.getText().toString().trim()), new Api.Cb(this) {
                    @Override
                    public void ok(JSONObject r) {
                        LudoApp.toast("Thanks — your report was sent");
                        load();
                    }
                }));
    }
}
