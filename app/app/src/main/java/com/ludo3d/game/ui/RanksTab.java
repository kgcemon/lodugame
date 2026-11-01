package com.ludo3d.game.ui;

import android.content.Intent;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.net.Api;

import org.json.JSONArray;
import org.json.JSONObject;

/** Leaderboard: most wins, best win rate (minimum matches), or this month's season points. */
final class RanksTab {
    private static String sort = "wins";
    private static int page = 1;

    private final MainActivity a;
    private final LudoApp app;
    private final LinearLayout col;

    RanksTab(MainActivity a, FrameLayout parent) {
        this.a = a;
        this.app = LudoApp.get();
        this.col = Ui.scrollPage(a, parent);
    }

    void render() {
        col.addView(Ui.title(a, "Leaderboard"));
        LinearLayout sorts = Ui.hbox(a);
        Ui.margins(sorts, a, 0, 12, 0, 12);
        String[][] opts = {{"wins", "Most wins"}, {"winrate", "Win rate"}, {"season", "This season"}};
        for (String[] o : opts) {
            boolean sel = o[0].equals(sort);
            TextView b = Ui.smallButton(a, o[1], sel ? "primary" : "glass", v -> {
                sort = o[0];
                page = 1;
                a.selectTab(2);
            });
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.rightMargin = Ui.dp(a, 6);
            sorts.addView(b, lp);
        }
        col.addView(sorts);
        LinearLayout list = Ui.card(a);
        list.addView(Ui.spinner(a));
        col.addView(list);

        app.api.get("/leaderboard?sort=" + sort + "&page=" + page, new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                list.removeAllViews();
                JSONArray items = r.optJSONArray("items");
                if ("winrate".equals(sort)) {
                    list.addView(Ui.text(a, "Players with at least " + r.optInt("minMatches") + " finished matches.", 12, Ui.MUTED));
                }
                if (items == null || items.length() == 0) {
                    list.addView(a.empty("No ranked players yet", "Finish a match to get on the board."));
                    return;
                }
                for (int i = 0; i < items.length(); i++) list.addView(row(items.optJSONObject(i)));
                int total = r.optInt("total");
                int size = Math.max(1, r.optInt("pageSize", 20));
                int pages = Math.max(1, (total + size - 1) / size);
                if (pages > 1) {
                    LinearLayout pager = Ui.hbox(a);
                    pager.setGravity(Gravity.CENTER);
                    Ui.margins(pager, a, 0, 10, 0, 0);
                    TextView prev = Ui.smallButton(a, "‹ Prev", "glass", v -> {
                        page--;
                        a.selectTab(2);
                    });
                    Ui.setEnabled(prev, page > 1);
                    TextView next = Ui.smallButton(a, "Next ›", "glass", v -> {
                        page++;
                        a.selectTab(2);
                    });
                    Ui.setEnabled(next, page < pages);
                    TextView label = Ui.muted(a, "  " + page + " / " + pages + "  ");
                    pager.addView(prev);
                    pager.addView(label);
                    pager.addView(next);
                    list.addView(pager);
                }
            }

            @Override
            public void err(Api.Error e) {
                list.removeAllViews();
                list.addView(Ui.muted(a, e.message));
            }
        });
    }

    private LinearLayout row(JSONObject x) {
        LinearLayout row = Ui.row(a);
        int pos = x.optInt("position");
        String medal = pos == 1 ? "🥇" : pos == 2 ? "🥈" : pos == 3 ? "🥉" : "#" + pos;
        TextView p = Ui.bold(Ui.text(a, medal, pos <= 3 ? 20 : 14, Ui.MUTED));
        p.setMinWidth(Ui.dp(a, 40));
        row.addView(p);
        row.addView(Ui.avatar(a, x.optString("username"), x.optInt("avatarHue", 210), 34));
        LinearLayout t = Ui.vbox(a);
        t.setPadding(Ui.dp(a, 10), 0, 0, 0);
        t.addView(Ui.bold(Ui.text(a, x.optString("username") + (x.optBoolean("isYou") ? " (you)" : ""), 15, x.optBoolean("isYou") ? Ui.GOLD : Ui.INK)));
        t.addView(Ui.text(a, x.optInt("matches") + " matches · " + x.optDouble("winRate") + "% wins", 12, Ui.MUTED));
        row.addView(t, Ui.weight(1));
        String value = "winrate".equals(sort) ? x.optDouble("winRate") + "%" : "season".equals(sort) ? x.optInt("seasonPoints") + " pts" : x.optInt("wins") + " wins";
        row.addView(Ui.bold(Ui.text(a, value, 14, Ui.INK)));
        row.setOnClickListener(v -> a.startActivity(new Intent(a, PlayerActivity.class).putExtra("publicId", x.optString("publicId"))));
        return row;
    }
}
