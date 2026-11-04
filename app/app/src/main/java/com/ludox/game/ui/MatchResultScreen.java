package com.ludox.game.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The result for a player who is out of a match (or whose match was cancelled), full screen:
 * YOU LOST and the exact reason from the server, then the match, the opponents, what happened
 * to the coins, and Home / Play again. Everything on it comes from the server's match record.
 */
final class MatchResultScreen {
    private MatchResultScreen() {}

    /** `onHome` runs on Return to Home and on Back; `onPlayAgain` is only offered when the server allows it. */
    static void show(BaseActivity a, JSONObject r, Runnable onHome, Runnable onPlayAgain) {
        boolean cancelled = "cancelled".equals(r.optString("outcome"));
        FriendPopups.Shell shell = new FriendPopups.Shell(a, false);
        shell.setOnCancelListener(d -> onHome.run());
        FrameLayout stage = new FrameLayout(a);

        View backdrop = new View(a);
        GradientDrawable spot = new GradientDrawable();
        spot.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        spot.setGradientRadius(a.getResources().getDisplayMetrics().heightPixels * 0.6f);
        spot.setGradientCenter(0.5f, 0.2f);
        spot.setColors(cancelled ? new int[]{0xf22c3160, 0xf811142e, 0xfc05060f} : new int[]{0xf25a1a33, 0xf8220a1c, 0xfc08030c});
        backdrop.setBackground(spot);
        stage.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout col = Ui.vbox(a);
        col.setGravity(Gravity.CENTER);
        col.setPadding(Ui.dp(a, 20), Ui.dp(a, 24), Ui.dp(a, 20), Ui.dp(a, 24));
        scroll.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Phones use the whole width; tablets keep it a comfortable column.
        int width = Math.min(a.getResources().getDisplayMetrics().widthPixels, Ui.dp(a, 480));
        stage.addView(scroll, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));

        TextView icon = Ui.text(a, cancelled ? "🛠" : iconFor(r.optString("reason")), 64, Color.WHITE);
        icon.setGravity(Gravity.CENTER);
        col.addView(icon, Ui.lpMatchWrap());

        Fx.GoldText title = new Fx.GoldText(a, cancelled ? "MATCH CANCELLED" : "YOU LOST", cancelled ? 32 : 44,
                cancelled ? new int[]{0xffffffff, 0xffd8dcf0, 0xff9aa3c7} : new int[]{0xffffffff, 0xffffb3b3, 0xffff5f5f},
                cancelled ? 0xff2a2f55 : 0xff4a0d1c);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        col.addView(title, Ui.lpMatchWrap());

        TextView reason = Ui.bold(Ui.text(a, r.optString("message"), 16, 0xffffe3e3));
        reason.setGravity(Gravity.CENTER);
        reason.setShadowLayer(Ui.dp(a, 3), 0, Ui.dp(a, 1), 0xcc000000);
        reason.setPadding(Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6), 0);
        col.addView(reason, Ui.lpMatchWrap());

        // The coins: what this match cost, or gave back.
        long net = r.optLong("net");
        String coins = net < 0 ? "−🪙 " + Ui.fmt(-net) : net > 0 ? "+🪙 " + Ui.fmt(net) : "🪙 0";
        TextView prize = Ui.bold(Ui.text(a, coins, 26, net < 0 ? 0xffff8a8a : Ui.GOLD));
        prize.setGravity(Gravity.CENTER);
        prize.setBackground(Ui.roundStroke(0xcc1a0a1e, Ui.dp(a, 999), Ui.dp(a, 2), net < 0 ? 0x88ff6b6b : 0x88ffc94a));
        prize.setPadding(Ui.dp(a, 24), Ui.dp(a, 6), Ui.dp(a, 24), Ui.dp(a, 8));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.topMargin = Ui.dp(a, 14);
        pl.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(prize, pl);

        // The match.
        LinearLayout info = Ui.vbox(a);
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xff2c1a4a, 0xff170a2a});
        glass.setCornerRadius(Ui.dp(a, 20));
        glass.setStroke(Ui.dp(a, 2), 0x55ffffff);
        info.setBackground(glass);
        info.setPadding(Ui.dp(a, 16), Ui.dp(a, 10), Ui.dp(a, 16), Ui.dp(a, 12));
        String opponents = names(r.optJSONArray("opponents"));
        if (!opponents.isEmpty()) info.addView(line(a, "Opponents", opponents));
        if (!r.isNull("winnerName")) info.addView(line(a, "Winner", "👑 " + r.optString("winnerName")));
        info.addView(line(a, "Match", statusText(r.optString("status"), !r.isNull("winnerName"))));
        info.addView(line(a, "Entry fee", Ui.fmt(r.optLong("coinsPaid"))));
        if (r.optLong("coinsReceived") > 0) info.addView(line(a, cancelled ? "Refunded" : "Received", Ui.fmt(r.optLong("coinsReceived"))));
        String id = r.optString("matchId");
        info.addView(line(a, "Match ID", id.length() > 8 ? id.substring(0, 8).toUpperCase() : id));
        LinearLayout.LayoutParams il = Ui.lpMatchWrap();
        il.topMargin = Ui.dp(a, 18);
        col.addView(info, il);

        // What next.
        JSONObject again = r.optJSONObject("playAgain");
        if (again != null && onPlayAgain != null) {
            View play = FriendPopups.button(a, "PLAY AGAIN", Fx.GREEN, 0xff1f6d0a);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 58));
            bl.topMargin = Ui.dp(a, 20);
            col.addView(play, bl);
            play.setOnClickListener(v -> shell.close(onPlayAgain));
        }
        View home = FriendPopups.button(a, "RETURN TO HOME", Fx.PURPLE, 0xff3b0d8c);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 54));
        hl.topMargin = Ui.dp(a, again != null && onPlayAgain != null ? 10 : 20);
        col.addView(home, hl);
        home.setOnClickListener(v -> shell.close(onHome));

        shell.setStage(stage);
        shell.show();

        icon.setScaleX(0f);
        icon.setScaleY(0f);
        icon.animate().scaleX(1).scaleY(1).setStartDelay(150).setDuration(420).setInterpolator(new OvershootInterpolator(2f)).start();
        title.setAlpha(0f);
        title.animate().alpha(1).setStartDelay(300).setDuration(320).start();
        info.setAlpha(0f);
        info.setTranslationY(Ui.dp(a, 24));
        info.animate().alpha(1).translationY(0).setStartDelay(420).setDuration(360).start();
    }

    private static String iconFor(String reason) {
        return "left".equals(reason) ? "🚪" : "timeout".equals(reason) ? "📵" : "⛔";
    }

    private static String statusText(String status, boolean hasWinner) {
        switch (status) {
            case "in_progress":
                return "Still being played by the others";
            case "completed":
                return hasWinner ? "Finished" : "Finished, no coins paid";
            default:
                return "Closed, nobody won";
        }
    }

    private static String names(JSONArray a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; a != null && i < a.length(); i++) {
            if (i > 0) b.append(", ");
            b.append(a.optString(i));
        }
        return b.toString();
    }

    private static View line(BaseActivity a, String k, String v) {
        LinearLayout row = Ui.hbox(a);
        row.setPadding(0, Ui.dp(a, 5), 0, Ui.dp(a, 5));
        TextView key = Ui.text(a, k, 13, Ui.MUTED);
        row.addView(key, new LinearLayout.LayoutParams(Ui.dp(a, 92), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView val = Ui.bold(Ui.text(a, v, 14, Ui.INK));
        val.setGravity(Gravity.END);
        row.addView(val, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }
}
