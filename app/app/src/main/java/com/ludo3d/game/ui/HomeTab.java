package com.ludo3d.game.ui;

import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.net.Api;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Home, game style: the LudoX logo with side buttons (free daily bonus, ranks, rules,
 * friends), big 3D tiles for two- and four-player matches, friends, join and online play.
 */
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
        col.setPadding(Ui.dp(a, 14), Ui.dp(a, 6), Ui.dp(a, 14), Ui.dp(a, 18));
        hero();
        roomBanner();
        verifyNotice();

        // Two big tiles: 1-vs-1 and four-player online matches.
        LinearLayout big = Ui.hbox(a);
        big.addView(modeTile(Fx.GREEN, "2", "TWO PLAYER", 0xff1f6d0a, new Fx.Pawns(a, 0xffe53945, 0xff2f7cf0), v -> pickMode(2)),
                tileLp(1, 172, 0, 6));
        big.addView(modeTile(Fx.RED, "4", "FOUR PLAYER", 0xff8a0d16, new Fx.Pawns(a, 0xff22b46b, 0xfff6bf26, 0xffe53945, 0xff2f7cf0), v -> pickMode(4)),
                tileLp(1, 172, 6, 0));
        col.addView(big, Ui.lpMatchWrap());

        // Three smaller tiles.
        LinearLayout small = Ui.hbox(a);
        Ui.margins(small, a, 0, 14, 0, 0);
        View board = new Fx.Friends(a);
        small.addView(artTile(Fx.ORANGE, "PLAY WITH\nFRIENDS", 0xff9c3410, board, v -> createRoom()), tileLp(1, 150, 0, 5));
        small.addView(artTile(Fx.BLUE, "JOIN\nROOM", 0xff0b5a9e, new Fx.Die(a), v -> joinRoom()), tileLp(1, 150, 5, 5));
        small.addView(artTile(Fx.YELLOW, "PLAY\nONLINE", 0xffa66f00, emoji("🌍", 44), v -> pickMode(0)), tileLp(1, 150, 5, 0));
        col.addView(small);

        diceShopBanner();
        collectionBanner();
        offlineBanner();
        banner();
        onlineFriends();
    }

    /** A wide 3D banner: collect today's free coins, or open your match history. */
    private void banner() {
        JSONObject d = app.wallet.optJSONObject("dailyBonus");
        boolean bonus = d != null && d.optLong("amount") > 0 && !d.optBoolean("claimedToday");
        Fx.Tile t = new Fx.Tile(a, bonus ? Fx.YELLOW : Fx.PURPLE, 18, 6);
        LinearLayout row = Ui.hbox(a);
        row.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0);
        row.addView(emoji(bonus ? "🎁" : "📜", 32));
        LinearLayout txt = Ui.vbox(a);
        txt.setPadding(Ui.dp(a, 12), 0, 0, 0);
        txt.addView(Fx.GoldText.label(a, bonus ? "COLLECT " + Ui.fmt(d.optLong("amount")) + " FREE COINS" : "MATCH HISTORY", 17,
                bonus ? 0xffa66f00 : 0xff3b0d8c));
        TextView sub = Ui.bold(Ui.text(a, bonus ? "Your daily bonus is ready" : "Results, coins won and reports", 12, bonus ? 0xff5c3a00 : 0xffe9e2ff));
        sub.setPadding(Ui.dp(a, 4), 0, 0, 0);
        txt.addView(sub);
        row.addView(txt, Ui.weight(1));
        row.addView(Fx.GoldText.label(a, "▶", 20, bonus ? 0xffa66f00 : 0xff3b0d8c));
        t.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        t.setOnClickListener(v -> {
            if (bonus) claimBonus(d, true);
            else a.startActivity(new android.content.Intent(a, HistoryActivity.class));
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 78));
        lp.topMargin = Ui.dp(a, 14);
        col.addView(t, lp);
    }

    /** A wide 3D banner into the shop (dice skins and profile frames bought with diamonds). */
    private void diceShopBanner() {
        Fx.Tile t = new Fx.Tile(a, Fx.BLUE, 18, 6);
        LinearLayout row = Ui.hbox(a);
        row.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0);
        row.addView(new Fx.Die(a), new LinearLayout.LayoutParams(Ui.dp(a, 38), Ui.dp(a, 38)));
        LinearLayout txt = Ui.vbox(a);
        txt.setPadding(Ui.dp(a, 12), 0, 0, 0);
        txt.addView(Fx.GoldText.label(a, "SHOP", 17, 0xff0b5a9e));
        TextView sub = Ui.bold(Ui.text(a, "Dice, frames & 👑 royal boards", 12, 0xffe0f0ff));
        sub.setPadding(Ui.dp(a, 4), 0, 0, 0);
        txt.addView(sub);
        row.addView(txt, Ui.weight(1));
        row.addView(Fx.GoldText.label(a, "▶", 20, 0xff0b5a9e));
        t.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        t.setOnClickListener(v -> a.startActivity(new android.content.Intent(a, ShopActivity.class)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 78));
        lp.topMargin = Ui.dp(a, 14);
        col.addView(t, lp);
    }

    /** A wide 3D banner into My Collection: every board, die and frame I own, by collection. */
    private void collectionBanner() {
        Fx.Tile t = new Fx.Tile(a, Fx.YELLOW, 18, 6);
        LinearLayout row = Ui.hbox(a);
        row.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0);
        row.addView(emoji("📚", 32));
        LinearLayout txt = Ui.vbox(a);
        txt.setPadding(Ui.dp(a, 12), 0, 0, 0);
        txt.addView(Fx.GoldText.label(a, "MY COLLECTION", 17, 0xffa66f00));
        TextView sub = Ui.bold(Ui.text(a, "Boards, dice & frames — all in one place", 12, 0xff5a3200));
        sub.setPadding(Ui.dp(a, 4), 0, 0, 0);
        txt.addView(sub);
        row.addView(txt, Ui.weight(1));
        row.addView(Fx.GoldText.label(a, "▶", 20, 0xffa66f00));
        t.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        t.setOnClickListener(v -> a.startActivity(new android.content.Intent(a, CollectionActivity.class)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 78));
        lp.topMargin = Ui.dp(a, 14);
        col.addView(t, lp);
    }

    /** A wide 3D banner for offline pass-and-play with 2 to 4 players on this phone. */
    private void offlineBanner() {
        Fx.Tile t = new Fx.Tile(a, Fx.GREEN, 18, 6);
        LinearLayout row = Ui.hbox(a);
        row.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0);
        row.addView(emoji("📱", 32));
        LinearLayout txt = Ui.vbox(a);
        txt.setPadding(Ui.dp(a, 12), 0, 0, 0);
        txt.addView(Fx.GoldText.label(a, "PLAY OFFLINE", 17, 0xff1f6d0a));
        TextView sub = Ui.bold(Ui.text(a, "2, 3 or 4 players on one phone", 12, 0xffeaffe0));
        sub.setPadding(Ui.dp(a, 4), 0, 0, 0);
        txt.addView(sub);
        row.addView(txt, Ui.weight(1));
        row.addView(Fx.GoldText.label(a, "▶", 20, 0xff1f6d0a));
        t.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        t.setOnClickListener(v -> OfflineSetup.show(a));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 78));
        lp.topMargin = Ui.dp(a, 14);
        col.addView(t, lp);
    }

    // ------------------------------------------------------------------ hero: logo + side buttons

    private void hero() {
        FrameLayout hero = new FrameLayout(a);
        LinearLayout logo = Ui.vbox(a);
        logo.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView crown = Ui.text(a, "👑", 34, Ui.INK);
        crown.setGravity(Gravity.CENTER);
        logo.addView(crown);
        LinearLayout title = Ui.hbox(a);
        title.setGravity(Gravity.CENTER);
        title.addView(new Fx.GoldText(a, "LUDO", 54));
        // The X in gold, as big as LUDO (like the app icon).
        Fx.GoldText x = new Fx.GoldText(a, "X", 58);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER_VERTICAL;
        title.addView(x, tlp);
        logo.addView(title);
        FrameLayout stage = new FrameLayout(a);
        stage.addView(new Fx.MiniBoard(a), new FrameLayout.LayoutParams(Ui.dp(a, 190), Ui.dp(a, 70), Gravity.CENTER));
        stage.addView(new Fx.Die(a), new FrameLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44), Gravity.CENTER));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(a, 200), Ui.dp(a, 72));
        slp.topMargin = -Ui.dp(a, 14);
        logo.addView(stage, slp);
        hero.addView(logo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        LinearLayout left = Ui.vbox(a);
        left.addView(freeBonus());
        left.addView(spinButton());
        hero.addView(left, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.TOP));
        LinearLayout right = Ui.vbox(a);
        right.addView(sideButton("❓", "RULES", v -> rules()));
        right.addView(sideButton("👥", "FRIENDS", v -> a.selectTab(1)));
        hero.addView(right, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP));
        col.addView(hero, Ui.lpMatchWrap());
    }

    private View sideButton(String emoji, String label, View.OnClickListener onClick) {
        LinearLayout c = Ui.vbox(a);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(Fx.badge(a, emoji, Fx.PURPLE, 50, onClick), new LinearLayout.LayoutParams(Ui.dp(a, 50), Ui.dp(a, 54)));
        c.addView(Fx.GoldText.label(a, label, 10, 0xff3b0d8c));
        Ui.margins(c, a, 0, 4, 0, 6);
        return c;
    }

    /** The daily bonus as a bouncing "FREE" bag; greyed out once collected today. */
    private View freeBonus() {
        JSONObject d = app.wallet.optJSONObject("dailyBonus");
        boolean available = d != null && d.optLong("amount") > 0 && !d.optBoolean("claimedToday");
        LinearLayout c = Ui.vbox(a);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        Fx.Tile bag = Fx.badge(a, "💰", available ? Fx.YELLOW : Fx.PURPLE_DARK, 50, v -> claimBonus(d, available));
        c.addView(bag, new LinearLayout.LayoutParams(Ui.dp(a, 50), Ui.dp(a, 54)));
        c.addView(available ? new Fx.GoldText(a, "FREE", 13) : Fx.GoldText.label(a, "DONE ✓", 10, 0xff3b0d8c));
        if (available) {
            android.animation.ObjectAnimator bounce = android.animation.ObjectAnimator.ofFloat(bag, "translationY", 0, -Ui.dp(a, 6), 0);
            bounce.setDuration(900);
            bounce.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            bounce.setStartDelay(300);
            bounce.start();
        }
        Ui.margins(c, a, 0, 4, 0, 6);
        return c;
    }

    /** The daily spin wheel: a spinning, bouncing gold badge while today's spin is unused. */
    private View spinButton() {
        boolean available = a.spinState != null && a.spinState.optBoolean("available");
        LinearLayout c = Ui.vbox(a);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        Fx.Tile wheel = Fx.badge(a, "🎡", available ? Fx.YELLOW : Fx.PURPLE_DARK, 50, v -> a.openSpin());
        c.addView(wheel, new LinearLayout.LayoutParams(Ui.dp(a, 50), Ui.dp(a, 54)));
        c.addView(available ? new Fx.GoldText(a, "SPIN", 13) : Fx.GoldText.label(a, "DONE ✓", 10, 0xff3b0d8c));
        if (available) {
            android.animation.ObjectAnimator wiggle = android.animation.ObjectAnimator.ofFloat(wheel, "rotation", 0, -12, 12, -6, 0);
            wiggle.setDuration(1000);
            wiggle.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            wiggle.setStartDelay(500);
            wiggle.start();
        }
        Ui.margins(c, a, 0, 4, 0, 6);
        return c;
    }

    private void claimBonus(JSONObject d, boolean available) {
        if (!available) {
            LudoApp.toast("Daily bonus collected — come back tomorrow!");
            return;
        }
        app.api.post("/wallet/daily-bonus", null, new Api.Cb(a) {
            @Override
            public void ok(JSONObject res) {
                app.mergeWallet(res.optJSONObject("wallet"));
                com.ludo3d.game.audio.Sfx.get().play(com.ludo3d.game.audio.Sfx.S.HOME);
                LudoApp.toast("+" + Ui.fmt(d.optLong("amount")) + " coins collected!");
                a.refreshHeader();
                a.selectTab(0);
            }
        });
    }

    private void rules() {
        a.alert("How to play",
                "• Roll a 6 to bring a token out of your yard.\n"
                        + "• Move clockwise round the board and up your coloured lane to the centre.\n"
                        + "• Land on an opponent to send them home (not on star or start squares).\n"
                        + "• A 6, a capture or reaching home gives you another roll. Three 6s in a row lose the turn.\n"
                        + "• Get all four tokens home first to win. In paid matches the winner takes the payout.");
    }

    // ------------------------------------------------------------------ tiles

    private static LinearLayout.LayoutParams tileLp(float weight, int heightDp, int leftDp, int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 0, weight);
        lp.height = (int) (heightDp * android.content.res.Resources.getSystem().getDisplayMetrics().density);
        lp.leftMargin = (int) (leftDp * android.content.res.Resources.getSystem().getDisplayMetrics().density);
        lp.rightMargin = (int) (rightDp * android.content.res.Resources.getSystem().getDisplayMetrics().density);
        return lp;
    }

    /** Big tile: pawn artwork with a huge gold number, label at the bottom. */
    private View modeTile(int[] colors, String number, String label, int outline, View pawns, View.OnClickListener onClick) {
        Fx.Tile t = new Fx.Tile(a, colors, 20, 7);
        FrameLayout art = new FrameLayout(a);
        art.addView(pawns, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 96), Gravity.TOP));
        Fx.GoldText n = new Fx.GoldText(a, number, 64);
        art.addView(n, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP));
        Fx.GoldText l = Fx.GoldText.label(a, label, 17, outline);
        l.setGravity(Gravity.CENTER);
        art.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        art.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 10));
        t.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        t.setOnClickListener(onClick);
        return t;
    }

    private View artTile(int[] colors, String label, int outline, View art, View.OnClickListener onClick) {
        Fx.Tile t = new Fx.Tile(a, colors, 18, 6);
        LinearLayout c = Ui.vbox(a);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(Ui.dp(a, 4), Ui.dp(a, 10), Ui.dp(a, 4), Ui.dp(a, 8));
        c.addView(art, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        Fx.GoldText l = Fx.GoldText.label(a, label, 13, outline);
        l.setGravity(Gravity.CENTER);
        c.addView(l, Ui.lpMatchWrap());
        t.addView(c, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        t.setOnClickListener(onClick);
        return t;
    }

    private TextView emoji(String e, float sp) {
        TextView t = Ui.text(a, e, sp, Ui.INK);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /**
     * Choose a match: players = 2 (1-vs-1 modes), 4 (four-player modes) or 0 (every online mode).
     * Each mode is a 3D tile showing its fee and the winner's payout.
     */
    private void pickMode(int players) {
        JSONArray modes = app.modes();
        LinearLayout list = Ui.vbox(a);
        list.setPadding(0, Ui.dp(a, 4), 0, 0);
        final Popup[] dlg = new Popup[1];
        int[][] palette = {Fx.GREEN, Fx.BLUE, Fx.ORANGE, Fx.RED, Fx.YELLOW};
        int n = 0;
        for (int i = 0; i < modes.length(); i++) {
            JSONObject m = modes.optJSONObject(i);
            if (m == null || m.optBoolean("privateOnly") || !m.optBoolean("enabled", true)) continue;
            int max = m.optInt("maxPlayers", 4);
            if (players == 2 && max != 2) continue;
            if (players == 4 && max < 3) continue;
            Fx.Tile t = new Fx.Tile(a, palette[n++ % palette.length], 16, 5);
            LinearLayout row = Ui.hbox(a);
            row.setPadding(Ui.dp(a, 14), 0, Ui.dp(a, 14), 0);
            LinearLayout txt = Ui.vbox(a);
            txt.addView(Fx.GoldText.label(a, m.optString("name"), 17, 0x99000000));
            TextView sub = Ui.bold(Ui.text(a, m.optString("matchType") + " · winner gets " + Ui.fmt(Math.round(m.optLong("entryFee") * app.payoutMultiplier())), 12, 0xeeffffff));
            sub.setPadding(Ui.dp(a, 4), 0, 0, 0);
            txt.addView(sub);
            row.addView(txt, Ui.weight(1));
            row.addView(new Fx.GoldText(a, "🪙" + Ui.fmt(m.optLong("entryFee")), 18));
            t.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            t.setOnClickListener(v -> {
                if (dlg[0] != null) dlg[0].dismiss();
                a.play.quick(m);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 74));
            lp.bottomMargin = Ui.dp(a, 10);
            list.addView(t, lp);
        }
        if (n == 0) list.addView(Ui.muted(a, "No matches of this kind are open right now."));
        String title = players == 2 ? "Two player matches" : players == 4 ? "Four player matches" : "Play online";
        dlg[0] = Popup.show(a, title, list, Popup.plain("Close", null));
    }

    private void joinRoom() {
        LinearLayout box = Ui.vbox(a);
        box.addView(Ui.muted(a, "Enter the room code your friend shared."));
        EditText code = Ui.input(a, "Room code", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        code.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(8)});
        Ui.margins(code, a, 0, 12, 0, 0);
        box.addView(code);
        a.dialog("Join a room", box, "Join", () -> a.play.joinCode(code.getText().toString()));
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



    private void createRoom() {
        JSONArray modes = app.modes();
        LinearLayout box = Ui.vbox(a);
        box.addView(Ui.label(a, "Match type"));
        final JSONObject[] chosen = {null};
        final int[] players = {2};
        // 2 vs 2: only for four players.
        final boolean[] teams = {false};
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
            opt.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 14));
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
            b.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 12));
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
        TextView teamsLabel = Ui.label(a, "Play as");
        box.addView(teamsLabel);
        LinearLayout teamRow = Ui.hbox(a);
        TextView solo = Ui.text(a, "Everyone for themselves", 13, Ui.INK);
        TextView duo = Ui.text(a, "🤝 2 vs 2 teams", 13, Ui.INK);
        TextView[] teamOpts = {solo, duo};
        for (int i = 0; i < 2; i++) {
            final boolean t = i == 1;
            TextView o = teamOpts[i];
            o.setGravity(Gravity.CENTER);
            o.setPadding(Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6), Ui.dp(a, 12));
            o.setOnClickListener(v -> {
                teams[0] = t;
                refresh[0].run();
            });
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.rightMargin = Ui.dp(a, 6);
            teamRow.addView(o, lp);
        }
        box.addView(teamRow);
        TextView teamsHint = Ui.muted(a, "Seats 1 + 3 play against 2 + 4. Partners can't capture each other and get a private team chat.");
        teamsHint.setTextSize(12);
        box.addView(teamsHint);
        refresh[0] = () -> {
            int max = chosen[0] != null ? chosen[0].optInt("maxPlayers", 4) : 4;
            if (players[0] > max) players[0] = max;
            for (int i = 0; i < modeList.getChildCount(); i++) {
                TextView o = (TextView) modeList.getChildAt(i);
                boolean sel = o.getTag() == chosen[0];
                Fx.bevel(o, sel ? Fx.YELLOW : Fx.PURPLE_DARK, 14, 4, true);
                o.setTextColor(sel ? 0xff4a2600 : Ui.INK);
            }
            for (int i = 0; i < 3; i++) {
                boolean allowed = i + 2 <= max;
                boolean sel = i + 2 == players[0];
                counts[i].setEnabled(allowed);
                counts[i].setAlpha(allowed ? 1f : 0.3f);
                Fx.bevel(counts[i], sel ? Fx.YELLOW : Fx.PURPLE_DARK, 14, 4, true);
                counts[i].setTextColor(sel ? 0xff4a2600 : Ui.INK);
            }
            boolean four = players[0] == 4;
            if (!four) teams[0] = false;
            int vis = four ? View.VISIBLE : View.GONE;
            teamsLabel.setVisibility(vis);
            teamRow.setVisibility(vis);
            teamsHint.setVisibility(four && teams[0] ? View.VISIBLE : View.GONE);
            for (int i = 0; i < 2; i++) {
                boolean sel = (i == 1) == teams[0];
                Fx.bevel(teamOpts[i], sel ? Fx.YELLOW : Fx.PURPLE_DARK, 14, 4, true);
                teamOpts[i].setTextColor(sel ? 0xff4a2600 : Ui.INK);
            }
        };
        refresh[0].run();
        a.dialog("Create a room", box, "Continue", () -> {
            if (chosen[0] != null) a.play.create(chosen[0], players[0], teams[0]);
        });
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
