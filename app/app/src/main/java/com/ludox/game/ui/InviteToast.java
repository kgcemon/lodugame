package com.ludox.game.ui;

import android.animation.ObjectAnimator;
import android.graphics.Color;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludox.game.audio.Sfx;

import org.json.JSONObject;

/**
 * A room invitation that slides in from the side as a 3D card with Accept / Decline.
 * Accept joins the room (with the usual entry-fee confirmation); Decline or 15 s of
 * silence slide it away. Only one invite card shows at a time.
 */
final class InviteToast {
    private static final long SHOW_MS = 15_000;

    private InviteToast() {}

    static void show(BaseActivity a, JSONObject payload) {
        String code = payload.optString("code");
        if (code.isEmpty()) return;
        View old = a.root.findViewWithTag("invite-toast");
        if (old != null) a.root.removeView(old);

        Fx.Tile card = new Fx.Tile(a, Fx.PURPLE, 22, 6);
        card.setTag("invite-toast");
        card.setClickable(true);
        LinearLayout body = Ui.vbox(a);
        body.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));

        LinearLayout top = Ui.hbox(a);
        String from = payload.optString("from", "A friend");
        FrameLayout av = new FrameLayout(a);
        av.addView(Ui.avatarColor(a, from, 0xffff7a45, 44), new FrameLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44), Gravity.CENTER));
        TextView mail = Ui.text(a, "💌", 16, Color.WHITE);
        av.addView(mail, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END));
        top.addView(av, new LinearLayout.LayoutParams(Ui.dp(a, 50), Ui.dp(a, 50)));
        LinearLayout text = Ui.vbox(a);
        text.setPadding(Ui.dp(a, 10), 0, 0, 0);
        Fx.GoldText who = Fx.GoldText.label(a, from + " invited you!", 17, 0xff3b0d8c);
        who.setSingleLine(true);
        who.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(who);
        long fee = payload.optLong("entryFee");
        TextView info = Ui.bold(Ui.text(a, payload.optString("modeName", "Room") + " · " + (fee > 0 ? "🪙 " + Ui.fmt(fee) : "free") + " · code " + code, 13, 0xffede9fe));
        info.setPadding(Ui.dp(a, 4), 0, 0, 0);
        text.addView(info);
        top.addView(text, Ui.weight(1));
        body.addView(top);

        LinearLayout buttons = Ui.hbox(a);
        Ui.margins(buttons, a, 0, 12, 0, 0);
        Runnable[] dismiss = new Runnable[1];
        buttons.addView(button(a, "DECLINE", Fx.RED, 0xff8a0d16, v -> dismiss[0].run()), weightLp(a, 0, 6));
        buttons.addView(button(a, "ACCEPT", Fx.GREEN, 0xff1f6d0a, v -> {
            dismiss[0].run();
            new PlayFlow(a).joinCode(code);
        }), weightLp(a, 6, 0));
        body.addView(buttons);

        // Time left before it goes away by itself.
        View track = new View(a);
        track.setBackground(Ui.round(0x33ffffff, Ui.dp(a, 2)));
        View bar = new View(a);
        bar.setBackground(Ui.round(0xffffd23f, Ui.dp(a, 2)));
        FrameLayout timer = new FrameLayout(a);
        timer.addView(track, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        timer.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 4));
        tlp.topMargin = Ui.dp(a, 10);
        body.addView(timer, tlp);
        card.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        lp.setMargins(Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12), 0);
        card.setElevation(Ui.dp(a, 16));
        a.root.addView(card, lp);

        // Slide in from the right with a slight tilt, like a card dealt onto the table.
        float width = a.getResources().getDisplayMetrics().widthPixels;
        card.setTranslationX(width);
        card.setRotation(6);
        card.animate().translationX(0).rotation(0).setDuration(520).setInterpolator(new OvershootInterpolator(1.1f)).start();
        bar.setPivotX(0);
        ObjectAnimator countdown = ObjectAnimator.ofFloat(bar, "scaleX", 1f, 0f);
        countdown.setDuration(SHOW_MS);
        countdown.setInterpolator(new LinearInterpolator());
        countdown.start();
        card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        Sfx s = Sfx.get();
        if (s != null) s.play(Sfx.S.EXTRA);

        boolean[] gone = {false};
        dismiss[0] = () -> {
            if (gone[0]) return;
            gone[0] = true;
            countdown.cancel();
            card.animate().translationX(-width).rotation(-6).alpha(0.4f).setDuration(320).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> a.root.removeView(card)).start();
        };
        card.postDelayed(() -> dismiss[0].run(), SHOW_MS);
    }

    private static Fx.Tile button(BaseActivity a, String label, int[] colors, int outline, View.OnClickListener onClick) {
        Fx.Tile t = new Fx.Tile(a, colors, 14, 4);
        Fx.GoldText l = Fx.GoldText.label(a, label, 15, outline);
        l.setGravity(Gravity.CENTER);
        t.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        t.setOnClickListener(onClick);
        return t;
    }

    private static LinearLayout.LayoutParams weightLp(BaseActivity a, int leftDp, int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 48), 1);
        lp.leftMargin = Ui.dp(a, leftDp);
        lp.rightMargin = Ui.dp(a, rightDp);
        return lp;
    }
}
