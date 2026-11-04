package com.ludox.game.ui;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.BounceInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ludox.game.LudoApp;
import com.ludox.game.audio.Sfx;
import com.ludox.game.game.Board;

import org.json.JSONObject;

import java.util.List;

/**
 * The end of a match, full screen. A win is a celebration: turning gold rays, a trophy
 * that drops in, the champion crowned, VICTORY in gold, the coins won counting up and
 * confetti. A loss is calm and silver ("GOOD GAME") and still crowns the winner. Below,
 * the finishing order, then Rematch / View board / Leave. Back (or View board) closes it
 * to look at the final board.
 */
final class VictoryPopup {
    private VictoryPopup() {}

    /** One player in the finishing order. */
    static final class Row {
        final String name, color;
        /** Their seat (picture, frame, flag); null offline. */
        final JSONObject seat;
        final boolean me, bot;

        Row(String name, String color, JSONObject seat, boolean me, boolean bot) {
            this.name = name;
            this.color = color;
            this.seat = seat;
            this.me = me;
            this.bot = bot;
        }
    }

    /** What the screen says and does. */
    static final class Spec {
        boolean win;
        String headline, sub;
        /** Coins I won (counted up), or 0. */
        long coinsWon;
        /** The ledger entry for those coins, once the server confirmed them: they fly into the wallet. */
        JSONObject reward;
        /** A quieter line about the payout when I didn't win, or null. */
        String payoutNote;
        /** The match's prize pool, as the server settled it; 0 for a free match. */
        long pool;
        List<Row> ranking;
        String primary;
        Runnable onPrimary, onLeave;
    }

    static Dialog show(BaseActivity a, Spec s) {
        FriendPopups.Shell shell = new FriendPopups.Shell(a, false);
        FrameLayout stage = new FrameLayout(a);
        stage.setClipChildren(false);
        // A deep spotlight behind it all, so the game underneath doesn't show through.
        View backdrop = new View(a);
        GradientDrawable spot = new GradientDrawable();
        spot.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        spot.setGradientRadius(a.getResources().getDisplayMetrics().heightPixels * 0.6f);
        spot.setGradientCenter(0.5f, 0.22f);
        spot.setColors(s.win ? new int[]{0xf24a2490, 0xf8160838, 0xfc06020f} : new int[]{0xf22c3160, 0xf811142e, 0xfc05060f});
        backdrop.setBackground(spot);
        stage.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FriendPopups.Rays rays = new FriendPopups.Rays(a, s.win ? 0x99ffd54a : 0x558f9bff);
        int rs = Ui.dp(a, 460);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(rs, rs, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        rl.topMargin = -Ui.dp(a, 90);
        stage.addView(rays, rl);
        FriendPopups.Floaters sparkles = null;
        if (s.win) {
            sparkles = new FriendPopups.Floaters(a, new String[]{"✨", "⭐", "💛"}, false);
            stage.addView(sparkles, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.setClipChildren(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout col = Ui.vbox(a);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        col.setPadding(Ui.dp(a, 20), Ui.dp(a, 24), Ui.dp(a, 20), Ui.dp(a, 24));
        scroll.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The champion: crowned picture (a trophy behind it when I won).
        Row champ = s.ranking.isEmpty() ? null : s.ranking.get(0);
        FrameLayout hero = new FrameLayout(a);
        hero.setClipChildren(false);
        TextView trophy = Ui.text(a, s.win ? "🏆" : "", 86, Color.WHITE);
        trophy.setGravity(Gravity.CENTER);
        hero.addView(trophy, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        View face = null;
        if (champ != null) {
            int sz = s.win ? 74 : 96;
            face = ring(a, avatar(a, champ, sz - 12), sz, s.win ? 0xffffc94a : 0xffd8dcf0);
            FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(Ui.dp(a, sz), Ui.dp(a, sz), s.win ? Gravity.BOTTOM | Gravity.END : Gravity.CENTER);
            if (s.win) {
                fl.rightMargin = Ui.dp(a, 4);
                fl.bottomMargin = Ui.dp(a, 2);
            }
            hero.addView(face, fl);
        }
        // The crown sits on the champion's head.
        TextView crown = Ui.text(a, "👑", s.win ? 30 : 40, Color.WHITE);
        crown.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams cl;
        if (s.win) {
            cl = new FrameLayout.LayoutParams(Ui.dp(a, 74), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.BOTTOM);
            cl.rightMargin = Ui.dp(a, 4);
            cl.bottomMargin = Ui.dp(a, 60);
        } else {
            cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        }
        hero.addView(crown, cl);
        col.addView(hero, new LinearLayout.LayoutParams(Ui.dp(a, s.win ? 210 : 140), Ui.dp(a, s.win ? 170 : 150)));

        Fx.GoldText title = s.win
                ? new Fx.GoldText(a, s.headline, 48)
                : new Fx.GoldText(a, s.headline, 40, new int[]{0xffffffff, 0xffd8dcf0, 0xff9aa3c7}, 0xff2a2f55);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        col.addView(title, Ui.lpMatchWrap());
        TextView sub = Ui.bold(Ui.text(a, s.sub, 16, s.win ? 0xfffff3c4 : 0xffd6dbf5));
        sub.setGravity(Gravity.CENTER);
        sub.setShadowLayer(Ui.dp(a, 3), 0, Ui.dp(a, 1), 0xcc000000);
        col.addView(sub, Ui.lpMatchWrap());

        // The prize: a gold pill counting up to what I won.
        TextView prize = null;
        if (s.coinsWon > 0) {
            prize = new Fx.GoldText(a, "+🪙 0", 30);
            prize.setGravity(Gravity.CENTER);
            GradientDrawable pill = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xcc3b1d00, 0xcc1f0f00});
            pill.setCornerRadius(Ui.dp(a, 999));
            pill.setStroke(Ui.dp(a, 2), 0xffffc94a);
            prize.setBackground(pill);
            prize.setPadding(Ui.dp(a, 26), Ui.dp(a, 6), Ui.dp(a, 26), Ui.dp(a, 8));
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pl.topMargin = Ui.dp(a, 14);
            col.addView(prize, pl);
        } else if (s.payoutNote != null) {
            TextView note = Ui.text(a, s.payoutNote, 14, Ui.GOLD);
            note.setGravity(Gravity.CENTER);
            note.setPadding(0, Ui.dp(a, 8), 0, 0);
            col.addView(note, Ui.lpMatchWrap());
        }

        if (s.pool > 0) {
            TextView poolNote = Ui.bold(Ui.text(a, "Final prize pool: 🪙 " + Ui.fmt(s.pool), 14, 0xfffff3c4));
            poolNote.setGravity(Gravity.CENTER);
            poolNote.setPadding(0, Ui.dp(a, 8), 0, 0);
            col.addView(poolNote, Ui.lpMatchWrap());
        }

        // Finishing order.
        LinearLayout board = Ui.vbox(a);
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xff2c1566, 0xff170a3a});
        glass.setCornerRadius(Ui.dp(a, 20));
        glass.setStroke(Ui.dp(a, 2), s.win ? 0x88ffc94a : 0x55ffffff);
        board.setBackground(glass);
        board.setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8));
        for (int i = 0; i < s.ranking.size(); i++) board.addView(row(a, s.ranking.get(i), i), Ui.lpMatchWrap());
        LinearLayout.LayoutParams bl = Ui.lpMatchWrap();
        bl.topMargin = Ui.dp(a, 18);
        col.addView(board, bl);

        // What next.
        View primary = FriendPopups.button(a, s.primary, s.win ? Fx.YELLOW : Fx.GREEN, s.win ? 0xffa66f00 : 0xff1f6d0a);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 58));
        pl.topMargin = Ui.dp(a, 20);
        col.addView(primary, pl);
        LinearLayout more = Ui.hbox(a);
        View view = FriendPopups.button(a, "VIEW BOARD", Fx.PURPLE, 0xff3b0d8c);
        View leave = FriendPopups.button(a, "LEAVE", Fx.RED, 0xff8a0d16);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(0, Ui.dp(a, 50), 1);
        vl.rightMargin = Ui.dp(a, 6);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, Ui.dp(a, 50), 1);
        ll.leftMargin = Ui.dp(a, 6);
        more.addView(view, vl);
        more.addView(leave, ll);
        LinearLayout.LayoutParams ml = Ui.lpMatchWrap();
        ml.topMargin = Ui.dp(a, 10);
        col.addView(more, ml);

        stage.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // The wallet the coins fly into, showing what the server says I have.
        TextView walletPill = null;
        if (s.reward != null) {
            walletPill = Ui.bold(Ui.text(a, "🪙 " + Ui.fmt(RewardFx.wasPlayed(s.reward) ? a.app.balance() : s.reward.optLong("before")), 15, Color.WHITE));
            walletPill.setGravity(Gravity.CENTER);
            walletPill.setBackground(Ui.roundStroke(0xcc1f0f00, Ui.dp(a, 999), Ui.dp(a, 2), 0xffffc94a));
            walletPill.setPadding(Ui.dp(a, 14), Ui.dp(a, 6), Ui.dp(a, 14), Ui.dp(a, 6));
            FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            wl.setMargins(0, Ui.dp(a, 10), Ui.dp(a, 12), 0);
            stage.addView(walletPill, wl);
        }
        FriendPopups.Floaters confetti = null;
        if (s.win) {
            confetti = new FriendPopups.Floaters(a, new String[]{"🎉", "✨", "💛", "⭐", "🪙", "💎"}, true);
            stage.addView(confetti, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        primary.setOnClickListener(v -> shell.close(s.onPrimary));
        view.setOnClickListener(v -> shell.close(null));
        leave.setOnClickListener(v -> shell.close(s.onLeave));
        shell.setStage(stage);
        shell.show();

        // Entrance: rays bloom, the trophy drops, the crown lands, the title pops, coins count.
        rays.setScaleX(0.2f);
        rays.setScaleY(0.2f);
        rays.animate().scaleX(1).scaleY(1).setDuration(800).setInterpolator(new DecelerateInterpolator()).start();
        FriendPopups.spin(rays, s.win ? 14000 : 30000);
        if (s.win) {
            trophy.setTranslationY(-Ui.dp(a, 320));
            trophy.animate().translationY(0).setStartDelay(250).setDuration(800).setInterpolator(new BounceInterpolator()).start();
        }
        if (face != null && s.win && Effects.level() != Effects.REDUCED) FriendPopups.breathe(hero);
        if (face != null) {
            face.setScaleX(0f);
            face.setScaleY(0f);
            face.animate().scaleX(1).scaleY(1).setStartDelay(s.win ? 700 : 250).setDuration(450).setInterpolator(new OvershootInterpolator(2.2f)).start();
        }
        crown.setTranslationY(-Ui.dp(a, 160));
        crown.setAlpha(0f);
        crown.animate().translationY(0).alpha(1).setStartDelay(s.win ? 950 : 550).setDuration(600).setInterpolator(new BounceInterpolator()).start();
        title.setScaleX(0f);
        title.setScaleY(0f);
        title.animate().scaleX(1).scaleY(1).setStartDelay(s.win ? 550 : 350).setDuration(520).setInterpolator(new OvershootInterpolator(2.5f)).start();
        board.setAlpha(0f);
        board.setTranslationY(Ui.dp(a, 30));
        board.animate().alpha(1).translationY(0).setStartDelay(s.win ? 900 : 500).setDuration(420).start();
        if (s.win) FriendPopups.breathe(primary);
        if (prize != null) {
            TextView pz = prize;
            ValueAnimator count = ValueAnimator.ofFloat(0f, 1f);
            count.setStartDelay(900);
            count.setDuration(1100);
            count.setInterpolator(new DecelerateInterpolator());
            count.addUpdateListener(an -> pz.setText("+🪙 " + Ui.fmt(Math.round(s.coinsWon * (float) an.getAnimatedValue()))));
            count.start();
        }
        if (s.reward != null && walletPill != null && !RewardFx.wasPlayed(s.reward)) {
            TextView target = walletPill;
            View from = prize;
            LudoApp.MAIN.postDelayed(() -> {
                if (!shell.isShowing()) return;
                RewardFx.Play play = new RewardFx.Play();
                play.reward = s.reward;
                play.anchor = target;
                play.counter = target;
                play.prefix = "🪙 ";
                play.from = from;
                // The prize pill already says the amount.
                play.announce = false;
                RewardFx.play(stage, play);
            }, 2100);
        }
        if (confetti != null) {
            FriendPopups.Floaters c = confetti;
            LudoApp.MAIN.postDelayed(c::burst, 700);
            LudoApp.MAIN.postDelayed(() -> FriendPopups.sound(Sfx.S.E_PARTY, 0.8f, 1f), 700);
        }
        return shell;
    }

    /** One line of the finishing order: medal, picture, name, colour. */
    private static View row(BaseActivity a, Row r, int place) {
        LinearLayout row = Ui.hbox(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6), Ui.dp(a, 8));
        if (place == 0 || r.me) {
            GradientDrawable hi = new GradientDrawable();
            hi.setCornerRadius(Ui.dp(a, 14));
            hi.setColor(place == 0 ? 0x33ffc94a : 0x1fffffff);
            row.setBackground(hi);
        }
        TextView medal = Ui.text(a, place == 0 ? "🥇" : place == 1 ? "🥈" : place == 2 ? "🥉" : "4", place < 3 ? 24 : 16, Ui.MUTED);
        medal.setGravity(Gravity.CENTER);
        row.addView(medal, new LinearLayout.LayoutParams(Ui.dp(a, 34), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(avatar(a, r, 38), new LinearLayout.LayoutParams(Ui.dp(a, 38), Ui.dp(a, 38)));
        TextView name = Ui.bold(Ui.text(a, (r.seat != null && !r.bot ? Flags.of(r.seat) + " " : "") + r.name, 15, place == 0 ? 0xffffe08a : Ui.INK));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 6), 0);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (r.me) row.addView(Ui.tag(a, "YOU", Ui.GOLD));
        View dot = new View(a);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Board.colorArgb(r.color));
        g.setStroke(Ui.dp(a, 2), 0xccffffff);
        dot.setBackground(g);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(a, 14), Ui.dp(a, 14));
        dl.leftMargin = Ui.dp(a, 8);
        row.addView(dot, dl);
        return row;
    }

    private static View avatar(BaseActivity a, Row r, int sizeDp) {
        if (r.bot) return Ui.avatarColor(a, "🤖", Board.colorArgb(r.color), sizeDp);
        if (r.seat != null) return Ui.avatar(a, r.seat, r.name, sizeDp);
        return Ui.avatarColor(a, r.name, Board.colorArgb(r.color), sizeDp);
    }

    /** A picture in a metal ring. */
    private static View ring(BaseActivity a, View inner, int sizeDp, int color) {
        FrameLayout f = new FrameLayout(a);
        View ring = new View(a);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0);
        g.setStroke(Ui.dp(a, 4), color);
        ring.setBackground(g);
        int in = Ui.dp(a, sizeDp - 12);
        f.addView(inner, new FrameLayout.LayoutParams(in, in, Gravity.CENTER));
        f.addView(ring, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        f.setElevation(Ui.dp(a, 8));
        return f;
    }
}
