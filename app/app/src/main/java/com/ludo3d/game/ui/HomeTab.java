package com.ludo3d.game.ui;

import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.net.Api;

import org.json.JSONArray;
import org.json.JSONObject;

/** Home: current room, daily bonus, paid matches, private rooms, stats and online friends. */
final class HomeTab {
    private final MainActivity a;
    private final LudoApp app;
    private final LinearLayout col;

    HomeTab(MainActivity a, FrameLayout parent) {
        this.a = a;
        this.app = LudoApp.get();
        this.col = Ui.scrollPage(a, parent);
    }

    void render() {
        roomBanner();
        verifyNotice();
        dailyBonus();

        col.addView(Ui.heading(a, "Online matches (coins)"));
        JSONArray modes = app.modes();
        for (int i = 0; i < modes.length(); i++) {
            JSONObject m = modes.optJSONObject(i);
            if (m == null || m.optBoolean("privateOnly") || !m.optBoolean("enabled", true)) continue;
            col.addView(modeCard(m));
        }

        col.addView(Ui.heading(a, "Play with friends"));
        LinearLayout friends = Ui.card(a);
        friends.addView(Ui.muted(a, "Create a private room and invite friends, or join one with its code."));
        TextView create = Ui.button(a, "＋ Create room", "blue", v -> createRoom());
        Ui.margins(create, a, 0, 12, 0, 0);
        friends.addView(create);
        LinearLayout join = Ui.hbox(a);
        Ui.margins(join, a, 0, 12, 0, 0);
        EditText code = Ui.input(a, "Room code", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        code.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(8)});
        LinearLayout.LayoutParams clp = Ui.weight(1);
        clp.rightMargin = Ui.dp(a, 8);
        join.addView(code, clp);
        join.addView(Ui.button(a, "Join", "glass", v -> a.play.joinCode(code.getText().toString())));
        friends.addView(join);
        col.addView(friends);

        stats();
        onlineFriends();
    }

    private void roomBanner() {
        JSONObject room = app.socket.room;
        if (room == null) return;
        LinearLayout c = Ui.card(a);
        c.setBackground(Ui.roundStroke(0x332f7cf0, Ui.dp(a, 18), Ui.dp(a, 1), 0x882f7cf0));
        JSONObject mode = room.optJSONObject("mode");
        boolean playing = "playing".equals(room.optString("status"));
        c.addView(Ui.bold(Ui.text(a, playing ? "Your match is in progress" : "You're in a room", 16, Ui.INK)));
        c.addView(Ui.muted(a, (mode != null ? mode.optString("name") : "Room") + " · code " + room.optString("code")));
        TextView back = Ui.button(a, playing ? "Return to match" : "Return to room", "blue", v -> {
            if (playing && app.socket.lastUpdate != null) a.openGame();
            else a.openRoom();
        });
        Ui.margins(back, a, 0, 10, 0, 0);
        c.addView(back);
        col.addView(c);
    }

    private void verifyNotice() {
        if (app.user.optBoolean("emailVerified") || !app.requireEmailVerification) return;
        LinearLayout c = Ui.card(a);
        c.addView(Ui.bold(Ui.text(a, "Verify your email", 15, Ui.GOLD)));
        c.addView(Ui.muted(a, "Paid matches need a verified email. Open the link we sent to " + app.user.optString("email") + "."));
        TextView b = Ui.smallButton(a, "Resend email", "glass", v -> app.api.post("/auth/resend-verification", null, new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                LudoApp.toast("Verification email sent");
            }
        }));
        Ui.margins(b, a, 0, 10, 0, 0);
        c.addView(b);
        col.addView(c);
    }

    private void dailyBonus() {
        JSONObject d = app.wallet.optJSONObject("dailyBonus");
        if (d == null || d.optLong("amount") <= 0) return;
        LinearLayout c = Ui.card(a);
        LinearLayout r = Ui.hbox(a);
        LinearLayout t = Ui.vbox(a);
        t.addView(Ui.bold(Ui.text(a, "🎁 Daily bonus", 16, Ui.INK)));
        t.addView(Ui.muted(a, d.optBoolean("claimedToday") ? "Collected today — come back tomorrow!" : "Collect " + Ui.fmt(d.optLong("amount")) + " free coins"));
        r.addView(t, Ui.weight(1));
        if (!d.optBoolean("claimedToday")) {
            r.addView(Ui.smallButton(a, "Collect", "primary", v -> {
                Ui.setEnabled(v, false);
                app.api.post("/wallet/daily-bonus", null, new Api.Cb(a) {
                    @Override
                    public void ok(JSONObject res) {
                        app.mergeWallet(res.optJSONObject("wallet"));
                        LudoApp.toast("+" + Ui.fmt(d.optLong("amount")) + " coins collected!");
                        a.refreshHeader();
                        a.selectTab(0);
                    }

                    @Override
                    public void done() {
                        Ui.setEnabled(v, true);
                    }
                });
            }));
        }
        c.addView(r);
        col.addView(c);
    }

    private LinearLayout modeCard(JSONObject m) {
        LinearLayout c = Ui.card(a);
        LinearLayout top = Ui.hbox(a);
        LinearLayout t = Ui.vbox(a);
        t.addView(Ui.bold(Ui.text(a, m.optString("name"), 17, Ui.INK)));
        t.addView(Ui.text(a, m.optString("matchType"), 12, Ui.GOLD));
        top.addView(t, Ui.weight(1));
        TextView fee = Ui.bold(Ui.text(a, Ui.coins(m.optLong("entryFee")), 15, Ui.GOLD));
        top.addView(fee);
        c.addView(top);
        TextView desc = Ui.muted(a, m.optString("description"));
        desc.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
        c.addView(desc);
        LinearLayout bottom = Ui.hbox(a);
        bottom.addView(Ui.muted(a, "Winner gets " + Ui.coins(Math.round(m.optLong("entryFee") * app.payoutMultiplier()))), Ui.weight(1));
        bottom.addView(Ui.smallButton(a, "▶ Play", "primary", v -> a.play.quick(m)));
        c.addView(bottom);
        return c;
    }

    private void createRoom() {
        JSONArray modes = app.modes();
        LinearLayout box = Ui.vbox(a);
        box.addView(Ui.label(a, "Match type"));
        final JSONObject[] chosen = {null};
        final int[] players = {2};
        LinearLayout modeList = Ui.vbox(a);
        LinearLayout countRow = Ui.hbox(a);
        TextView[] counts = new TextView[3];
        Runnable[] refresh = new Runnable[1];
        for (int i = 0; i < modes.length(); i++) {
            JSONObject m = modes.optJSONObject(i);
            if (m == null || !m.optBoolean("enabled", true)) continue;
            if (chosen[0] == null) chosen[0] = m;
            long fee = m.optLong("entryFee");
            TextView opt = Ui.text(a, m.optString("name") + "  ·  " + (fee > 0 ? Ui.coins(fee) : "free"), 15, Ui.INK);
            opt.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10));
            opt.setTag(m);
            opt.setOnClickListener(v -> {
                chosen[0] = (JSONObject) v.getTag();
                refresh[0].run();
            });
            Ui.margins(opt, a, 0, 0, 0, 6);
            modeList.addView(opt);
        }
        box.addView(modeList);
        box.addView(Ui.label(a, "Players"));
        for (int n = 2; n <= 4; n++) {
            final int count = n;
            TextView b = Ui.text(a, String.valueOf(n), 16, Ui.INK);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10));
            b.setOnClickListener(v -> {
                players[0] = count;
                refresh[0].run();
            });
            counts[n - 2] = b;
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.rightMargin = Ui.dp(a, 6);
            countRow.addView(b, lp);
        }
        box.addView(countRow);
        refresh[0] = () -> {
            int max = chosen[0] != null ? chosen[0].optInt("maxPlayers", 4) : 4;
            if (players[0] > max) players[0] = max;
            for (int i = 0; i < modeList.getChildCount(); i++) {
                TextView o = (TextView) modeList.getChildAt(i);
                boolean sel = o.getTag() == chosen[0];
                o.setBackground(Ui.roundStroke(sel ? 0x332f7cf0 : 0x10ffffff, Ui.dp(a, 12), Ui.dp(a, 1), sel ? Ui.PRIMARY : Ui.STROKE));
            }
            for (int i = 0; i < 3; i++) {
                boolean allowed = i + 2 <= max;
                boolean sel = i + 2 == players[0];
                counts[i].setEnabled(allowed);
                counts[i].setAlpha(allowed ? 1f : 0.3f);
                counts[i].setBackground(Ui.roundStroke(sel ? 0x33ffb547 : 0x10ffffff, Ui.dp(a, 12), Ui.dp(a, 1), sel ? Ui.GOLD : Ui.STROKE));
            }
        };
        refresh[0].run();
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(box);
        a.dialog("Create a room", sv, "Continue", () -> {
            if (chosen[0] != null) a.play.create(chosen[0], players[0]);
        });
    }

    private void stats() {
        LinearLayout c = Ui.card(a);
        c.addView(Ui.bold(Ui.text(a, "Your stats", 16, Ui.INK)));
        col.addView(Ui.heading(a, "Overview"));
        col.addView(c);
        app.api.get("/me/stats", new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                JSONObject s = r.optJSONObject("stats");
                if (s == null) return;
                LinearLayout grid = Ui.hbox(a);
                Ui.margins(grid, a, 0, 10, 0, 0);
                grid.addView(stat(Ui.fmt(s.optLong("matches")), "Matches"), Ui.weight(1));
                grid.addView(stat(Ui.fmt(s.optLong("wins")), "Wins"), Ui.weight(1));
                grid.addView(stat(s.optDouble("winRate") + "%", "Win rate"), Ui.weight(1));
                grid.addView(stat("#" + s.optInt("rank"), "Rank"), Ui.weight(1));
                c.addView(grid);
                JSONArray recent = s.optJSONArray("recent");
                if (recent != null && recent.length() > 0) {
                    StringBuilder sb = new StringBuilder("Recent: ");
                    for (int i = 0; i < recent.length(); i++) sb.append("W".equals(recent.optString(i)) ? "🟢" : "🔴");
                    TextView rt = Ui.muted(a, sb);
                    rt.setPadding(0, Ui.dp(a, 10), 0, 0);
                    c.addView(rt);
                }
                TextView more = Ui.smallButton(a, "Match history →", "ghost", a.open(HistoryActivity.class));
                c.addView(more);
            }

            @Override
            public void err(Api.Error e) {
                c.addView(Ui.muted(a, e.message));
            }
        });
    }

    private LinearLayout stat(String value, String label) {
        LinearLayout l = Ui.vbox(a);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.addView(Ui.bold(Ui.text(a, value, 18, Ui.INK)));
        l.addView(Ui.text(a, label, 12, Ui.MUTED));
        return l;
    }

    private void onlineFriends() {
        app.api.get("/friends", new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                JSONArray fs = r.optJSONArray("friends");
                LinearLayout c = null;
                for (int i = 0; fs != null && i < fs.length(); i++) {
                    JSONObject f = fs.optJSONObject(i);
                    if (f == null || !f.optBoolean("online")) continue;
                    if (c == null) {
                        col.addView(Ui.heading(a, "Friends online"));
                        c = Ui.card(a);
                        col.addView(c);
                    }
                    c.addView(FriendsTab.friendRow(a, f));
                }
            }

            @Override
            public void err(Api.Error e) {
                // Optional section.
            }
        });
    }
}
