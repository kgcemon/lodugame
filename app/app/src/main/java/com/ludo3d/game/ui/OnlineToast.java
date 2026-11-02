package com.ludo3d.game.ui;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.audio.Sfx;
import com.ludo3d.game.net.Api;

import org.json.JSONObject;

/**
 * "Your friend is online": a card that drops in from the top. A friend you play with most
 * gets the gold best-friend card with a crown and the number of matches you played
 * together; any friend's card has INVITE (from your room, or a new free room) and LATER.
 */
final class OnlineToast {
    private static final long SHOW_MS = 7_000, BEST_SHOW_MS = 12_000;

    private OnlineToast() {}

    static void show(BaseActivity a, JSONObject data) {
        JSONObject p = data.optJSONObject("player");
        if (p == null || a.isFinishing()) return;
        boolean best = data.optBoolean("best");
        int played = data.optInt("played");
        View old = a.root.findViewWithTag("online-toast");
        if (old != null) a.root.removeView(old);

        Fx.Tile card = new Fx.Tile(a, best ? Fx.YELLOW : Fx.PURPLE, 22, 6);
        card.setTag("online-toast");
        card.setClickable(true);
        LinearLayout body = Ui.vbox(a);
        body.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));

        LinearLayout top = Ui.hbox(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        // The friend's picture with a pulsing green "online" dot (and a crown for the best friend).
        FrameLayout av = new FrameLayout(a);
        av.setClipChildren(false);
        av.addView(Ui.avatar(a, p, p.optString("username"), 48), new FrameLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48), Gravity.CENTER));
        View dot = new View(a);
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(0xff22c55e);
        dg.setStroke(Ui.dp(a, 2), Color.WHITE);
        dot.setBackground(dg);
        av.addView(dot, new FrameLayout.LayoutParams(Ui.dp(a, 14), Ui.dp(a, 14), Gravity.BOTTOM | Gravity.END));
        if (best) {
            TextView crown = Ui.text(a, "👑", 20, Color.WHITE);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            clp.topMargin = -Ui.dp(a, 18);
            av.addView(crown, clp);
            ObjectAnimator bob = ObjectAnimator.ofFloat(crown, "rotation", -10, 10);
            bob.setDuration(700);
            bob.setRepeatMode(ValueAnimator.REVERSE);
            bob.setRepeatCount(ValueAnimator.INFINITE);
            bob.start();
        }
        top.addView(av, new LinearLayout.LayoutParams(Ui.dp(a, 54), Ui.dp(a, 54)));

        LinearLayout text = Ui.vbox(a);
        text.setPadding(Ui.dp(a, 10), 0, 0, 0);
        int outline = best ? 0xffa66f00 : 0xff3b0d8c;
        if (best) {
            Fx.GoldText tag = Fx.GoldText.label(a, "⭐ BEST FRIEND ONLINE", 12, 0xff8a5a00);
            text.addView(tag);
        }
        Fx.GoldText who = Fx.GoldText.label(a, Flags.named(p, p.optString("username")) + (best ? "" : " is online!"), best ? 18 : 16, outline);
        who.setSingleLine(true);
        who.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(who);
        String sub = best ? "You played " + played + " matches together. Play again?"
                : played > 0 ? "🟢 Online now · " + played + (played == 1 ? " match" : " matches") + " together" : "🟢 Online now · invite them to play";
        TextView info = Ui.bold(Ui.text(a, sub, 12, best ? 0xff5c3a00 : 0xffede9fe));
        info.setPadding(Ui.dp(a, 4), 0, 0, 0);
        text.addView(info);
        top.addView(text, Ui.weight(1));
        body.addView(top);

        LinearLayout buttons = Ui.hbox(a);
        Ui.margins(buttons, a, 0, 10, 0, 0);
        Runnable[] dismiss = new Runnable[1];
        buttons.addView(button(a, "LATER", Fx.PURPLE_DARK, 0xff26104f, v -> dismiss[0].run()), weightLp(a, 0, 6, 1));
        buttons.addView(button(a, best ? "🎲 PLAY TOGETHER" : "🎲 INVITE", Fx.GREEN, 0xff1f6d0a, v -> {
            dismiss[0].run();
            invite(a, p);
        }), weightLp(a, 6, 0, 1.4f));
        body.addView(buttons);
        card.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        lp.setMargins(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), 0);
        card.setElevation(Ui.dp(a, 18));
        a.root.addView(card, lp);

        // Drops in from the top with a bounce; the online dot pulses; best friends shimmer.
        card.setTranslationY(-Ui.dp(a, 220));
        card.animate().translationY(0).setDuration(600).setInterpolator(new OvershootInterpolator(1.3f)).start();
        ObjectAnimator pulse = ObjectAnimator.ofPropertyValuesHolder(dot,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.5f, 1f), PropertyValuesHolder.ofFloat("scaleY", 1f, 1.5f, 1f));
        pulse.setDuration(900);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.start();
        if (best) {
            ObjectAnimator glow = ObjectAnimator.ofPropertyValuesHolder(card,
                    PropertyValuesHolder.ofFloat("scaleX", 1f, 1.025f, 1f), PropertyValuesHolder.ofFloat("scaleY", 1f, 1.025f, 1f));
            glow.setDuration(1200);
            glow.setRepeatCount(ValueAnimator.INFINITE);
            glow.setStartDelay(600);
            glow.start();
        }
        Sfx s = Sfx.get();
        if (s != null) s.play(best ? Sfx.S.MATCH_FOUND : Sfx.S.MESSAGE, 0.8f, best ? 1.1f : 1.25f);

        boolean[] gone = {false};
        dismiss[0] = () -> {
            if (gone[0]) return;
            gone[0] = true;
            pulse.cancel();
            card.animate().translationY(-card.getHeight() - Ui.dp(a, 40)).alpha(0.3f).setDuration(280).setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> a.root.removeView(card)).start();
        };
        card.postDelayed(() -> dismiss[0].run(), best ? BEST_SHOW_MS : SHOW_MS);
    }

    /** Invite from my open room, or make a free two-player room and invite into it. */
    private static void invite(BaseActivity a, JSONObject p) {
        String pid = p.optString("publicId");
        Runnable send = () -> a.app.api.post("/friends/invite", PlayFlow.obj("publicId", pid), new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                LudoApp.toast("Invite sent to " + p.optString("username") + " 💌");
            }
        });
        if (a.app.socket.room != null) {
            send.run();
            return;
        }
        a.app.socket.emit("room:create", PlayFlow.obj("modeId", "friendly", "maxPlayers", 2), res -> {
            if (!res.optBoolean("ok")) {
                LudoApp.toast(res.optString("error", "Couldn't create a room"));
                return;
            }
            send.run();
            a.startActivity(new Intent(a, RoomActivity.class));
        });
    }

    private static Fx.Tile button(BaseActivity a, String label, int[] colors, int outline, View.OnClickListener onClick) {
        Fx.Tile t = new Fx.Tile(a, colors, 14, 4);
        Fx.GoldText l = Fx.GoldText.label(a, label, 14, outline);
        l.setGravity(Gravity.CENTER);
        l.setSingleLine(true);
        t.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        t.setOnClickListener(onClick);
        return t;
    }

    private static LinearLayout.LayoutParams weightLp(BaseActivity a, int leftDp, int rightDp, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 44), weight);
        lp.leftMargin = Ui.dp(a, leftDp);
        lp.rightMargin = Ui.dp(a, rightDp);
        return lp;
    }
}
