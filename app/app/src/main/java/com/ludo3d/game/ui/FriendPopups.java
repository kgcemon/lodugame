package com.ludo3d.game.ui;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.audio.Sfx;
import com.ludo3d.game.net.Api;

import org.json.JSONObject;

import java.util.Random;

/**
 * Live friend requests, wherever the player is (even mid-match):
 * a request pops up with Accept / Decline; accepting crowns both players with a royal
 * "FRIENDS!" celebration (the sender sees it too); a declined sender gets a gentle sad
 * one with a breaking heart.
 */
final class FriendPopups {
    private FriendPopups() {}

    /** The request popup on screen, by request id (closed if the sender cancels). */
    private static Dialog open;
    private static String openId;

    // ------------------------------------------------------------------ the request

    static void request(BaseActivity a, JSONObject data) {
        JSONObject p = data.optJSONObject("player");
        String id = data.optString("id");
        if (p == null || id.isEmpty() || a.isFinishing()) return;
        if (open != null && open.isShowing()) open.dismiss();

        Shell s = new Shell(a, true);
        LinearLayout card = cardBox(a, Fx.PANEL);
        // Hearts drift up behind the avatar.
        FrameLayout top = new FrameLayout(a);
        Floaters hearts = new Floaters(a, new String[]{"💜", "💛", "💖", "✨"}, false);
        top.addView(hearts, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 150)));
        View av = avatar(a, p, 96);
        top.addView(av, new FrameLayout.LayoutParams(Ui.dp(a, 96), Ui.dp(a, 96), Gravity.CENTER));
        TextView wave = Ui.text(a, "👋", 34, Color.WHITE);
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        wlp.leftMargin = Ui.dp(a, 70);
        wlp.topMargin = -Ui.dp(a, 34);
        top.addView(wave, wlp);
        card.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 150)));

        Fx.GoldText name = new Fx.GoldText(a, Flags.named(p, p.optString("username")), 24);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        card.addView(name, Ui.lpMatchWrap());
        TextView sub = Ui.bold(Ui.text(a, "wants to be your friend!", 15, 0xffe9e2ff));
        sub.setGravity(Gravity.CENTER);
        card.addView(sub, Ui.lpMatchWrap());

        LinearLayout row = Ui.hbox(a);
        View no = button(a, "DECLINE", Fx.RED, 0xff8a0d16);
        View yes = button(a, "ACCEPT", Fx.GREEN, 0xff1f6d0a);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, Ui.dp(a, 56), 1);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, Ui.dp(a, 56), 1.3f);
        l2.leftMargin = Ui.dp(a, 10);
        row.addView(no, l1);
        row.addView(yes, l2);
        LinearLayout.LayoutParams rlp = Ui.lpMatchWrap();
        rlp.topMargin = Ui.dp(a, 18);
        card.addView(row, rlp);
        s.setCard(card);

        final boolean[] busy = {false};
        yes.setOnClickListener(v -> {
            if (busy[0]) return;
            busy[0] = true;
            a.app.api.post("/friends/requests/" + id + "/accept", null, new Api.Cb(a) {
                @Override
                public void ok(JSONObject r) {
                    // Talk at once, without waiting for the server's "friends changed" nudge.
                    if (a.app.socket.room != null) com.ludo3d.game.audio.VoiceChat.get().refreshFriends();
                    s.close(() -> celebrate(a, p));
                }

                @Override
                public void err(Api.Error e) {
                    busy[0] = false;
                    LudoApp.toast(e.message);
                    s.close(null);
                }
            });
        });
        no.setOnClickListener(v -> {
            if (busy[0]) return;
            busy[0] = true;
            a.app.api.post("/friends/requests/" + id + "/reject", null, new Api.Cb(a) {
                @Override
                public void ok(JSONObject r) {
                    s.close(null);
                }

                @Override
                public void err(Api.Error e) {
                    LudoApp.toast(e.message);
                    s.close(null);
                }
            });
        });
        s.setOnDismissListener(d -> {
            if (open == s) {
                open = null;
                openId = null;
            }
        });
        open = s;
        openId = id;
        s.show();
        // The avatar pops, the hand waves.
        av.setScaleX(0f);
        av.setScaleY(0f);
        av.animate().scaleX(1).scaleY(1).setStartDelay(180).setDuration(500).setInterpolator(new OvershootInterpolator(2.2f)).start();
        ObjectAnimator waving = ObjectAnimator.ofFloat(wave, "rotation", 0, 25, -10, 25, 0);
        waving.setDuration(900);
        waving.setRepeatCount(ValueAnimator.INFINITE);
        waving.setStartDelay(400);
        waving.start();
        breathe(yes);
        sound(Sfx.S.MESSAGE, 1f, 1.1f);
    }

    /** The sender withdrew the request: close its popup if it is showing. */
    static void cancelled(JSONObject data) {
        if (open != null && open.isShowing() && data.optString("id").equals(openId)) {
            ((Shell) open).close(null);
            LudoApp.toast("The friend request was withdrawn");
        }
    }

    // ------------------------------------------------------------------ accepted: royal

    /** Both players crowned side by side: "FRIENDS!" with gold rays and confetti. */
    static void celebrate(BaseActivity a, JSONObject them) {
        if (a.isFinishing()) return;
        Shell s = new Shell(a, false);
        FrameLayout stage = new FrameLayout(a);
        stage.setClipChildren(false);

        Rays rays = new Rays(a, 0x99ffd54a);
        int rs = Ui.dp(a, 380);
        stage.addView(rays, new FrameLayout.LayoutParams(rs, rs, Gravity.CENTER));

        LinearLayout col = Ui.vbox(a);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        TextView crown = Ui.text(a, "👑", 64, Color.WHITE);
        crown.setGravity(Gravity.CENTER);
        col.addView(crown, Ui.lpMatchWrap());

        FrameLayout pairBox = new FrameLayout(a);
        pairBox.setClipChildren(false);
        int szDp = 92, sz = Ui.dp(a, szDp);
        View me = avatar(a, a.app.user, szDp);
        View other = avatar(a, them, szDp);
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(sz, sz, Gravity.CENTER);
        ml.rightMargin = Ui.dp(a, 84);
        FrameLayout.LayoutParams ol = new FrameLayout.LayoutParams(sz, sz, Gravity.CENTER);
        ol.leftMargin = Ui.dp(a, 84);
        pairBox.addView(me, ml);
        pairBox.addView(other, ol);
        TextView heart = Ui.text(a, "🤝", 42, Color.WHITE);
        heart.setGravity(Gravity.CENTER);
        pairBox.addView(heart, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        col.addView(pairBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 120)));

        Fx.GoldText title = new Fx.GoldText(a, "FRIENDS!", 46);
        title.setGravity(Gravity.CENTER);
        col.addView(title, Ui.lpMatchWrap());
        TextView sub = Ui.bold(Ui.text(a, "You and " + Flags.named(them, them.optString("username")) + " are now friends.\nInvite each other to rooms and play together!", 14, 0xfffff3c4));
        sub.setGravity(Gravity.CENTER);
        sub.setShadowLayer(Ui.dp(a, 3), 0, Ui.dp(a, 1), 0xcc000000);
        Ui.margins(sub, a, 24, 6, 24, 0);
        col.addView(sub, Ui.lpMatchWrap());
        View ok = button(a, "YAY!", Fx.YELLOW, 0xffa66f00);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(a, 200), Ui.dp(a, 56));
        blp.topMargin = Ui.dp(a, 22);
        col.addView(ok, blp);
        stage.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        Floaters confetti = new Floaters(a, new String[]{"✨", "🎉", "💛", "⭐", "💖"}, true);
        stage.addView(confetti, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ok.setOnClickListener(v -> s.close(null));
        s.setStage(stage);
        s.show();

        // Rays bloom and turn; the friends slide in from the sides and meet; the crown drops.
        rays.setScaleX(0.2f);
        rays.setScaleY(0.2f);
        rays.animate().scaleX(1).scaleY(1).setDuration(700).setInterpolator(new DecelerateInterpolator()).start();
        spin(rays, 16000);
        me.setTranslationX(-Ui.dp(a, 260));
        other.setTranslationX(Ui.dp(a, 260));
        me.animate().translationX(0).setDuration(650).setInterpolator(new OvershootInterpolator(1.4f)).start();
        other.animate().translationX(0).setDuration(650).setInterpolator(new OvershootInterpolator(1.4f)).start();
        heart.setScaleX(0f);
        heart.setScaleY(0f);
        heart.animate().scaleX(1.2f).scaleY(1.2f).setStartDelay(600).setDuration(420).setInterpolator(new OvershootInterpolator(3f)).start();
        crown.setTranslationY(-Ui.dp(a, 300));
        crown.animate().translationY(0).setStartDelay(450).setDuration(700).setInterpolator(new android.view.animation.BounceInterpolator()).start();
        title.setScaleX(0f);
        title.setScaleY(0f);
        title.animate().scaleX(1).scaleY(1).setStartDelay(800).setDuration(500).setInterpolator(new OvershootInterpolator(2.5f)).start();
        LudoApp.MAIN.postDelayed(confetti::burst, 650);
        sound(Sfx.S.VICTORY, 0.9f, 1.1f);
        LudoApp.MAIN.postDelayed(() -> sound(Sfx.S.E_PARTY, 0.8f, 1f), 650);
    }

    // ------------------------------------------------------------------ declined: sad

    /** The sender's request was declined: a breaking heart and a few slow tears. */
    static void declined(BaseActivity a, JSONObject them) {
        if (a.isFinishing()) return;
        Shell s = new Shell(a, true);
        LinearLayout card = cardBox(a, new int[]{0xff4a4f7a, 0xff2c2f52, 0xff1a1c36});
        FrameLayout top = new FrameLayout(a);
        Floaters rain = new Floaters(a, new String[]{"💧"}, false);
        rain.falling = true;
        top.addView(rain, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 150)));
        View av = avatar(a, them, 86);
        av.setAlpha(0.85f);
        top.addView(av, new FrameLayout.LayoutParams(Ui.dp(a, 86), Ui.dp(a, 86), Gravity.CENTER));
        TextView left = Ui.text(a, "💔", 40, Color.WHITE);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        hl.leftMargin = Ui.dp(a, 76);
        hl.topMargin = Ui.dp(a, 30);
        top.addView(left, hl);
        card.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 150)));
        Fx.GoldText title = new Fx.GoldText(a, "Not this time…", 24, new int[]{0xffe0e6ff, 0xffb8c1ef, 0xff8e98cc}, 0xff1a1c36);
        title.setGravity(Gravity.CENTER);
        card.addView(title, Ui.lpMatchWrap());
        TextView sub = Ui.text(a, Flags.named(them, them.optString("username")) + " declined your friend request 😢", 15, 0xffd6dbf5);
        sub.setGravity(Gravity.CENTER);
        Ui.margins(sub, a, 6, 6, 6, 0);
        card.addView(sub, Ui.lpMatchWrap());
        View ok = button(a, "OK", Fx.PURPLE, 0xff3b0d8c);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(a, 170), Ui.dp(a, 52));
        blp.topMargin = Ui.dp(a, 18);
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(ok, blp);
        ok.setOnClickListener(v -> s.close(null));
        s.setCard(card);
        s.show();
        // The heart drops and shakes; the avatar droops.
        left.setTranslationY(-Ui.dp(a, 80));
        left.setAlpha(0f);
        left.animate().translationY(0).alpha(1).setStartDelay(250).setDuration(600).setInterpolator(new android.view.animation.BounceInterpolator()).start();
        ObjectAnimator shake = ObjectAnimator.ofFloat(left, "rotation", 0, -14, 14, -8, 8, 0);
        shake.setStartDelay(900);
        shake.setDuration(600);
        shake.start();
        av.animate().scaleX(0.92f).scaleY(0.92f).rotation(-6).setStartDelay(500).setDuration(900).start();
        sound(Sfx.S.DEFEAT, 0.6f, 1.15f);
    }

    // ------------------------------------------------------------------ pieces

    static void sound(Sfx.S s, float v, float r) {
        Sfx sfx = Sfx.get();
        if (sfx != null) sfx.play(s, v, r);
    }

    static void spin(View v, long ms) {
        ObjectAnimator turn = ObjectAnimator.ofFloat(v, "rotation", 0, 360);
        turn.setDuration(ms);
        turn.setRepeatCount(ValueAnimator.INFINITE);
        turn.setInterpolator(new LinearInterpolator());
        turn.start();
    }

    static void breathe(View v) {
        ObjectAnimator pulse = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.06f, 1f), PropertyValuesHolder.ofFloat("scaleY", 1f, 1.06f, 1f));
        pulse.setDuration(1000);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setStartDelay(600);
        pulse.start();
    }

    /** A player's picture (or initial) in their frame, with a gold ring. */
    private static View avatar(Context c, JSONObject who, int sizeDp) {
        FrameLayout f = new FrameLayout(c);
        View ring = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0);
        g.setStroke(Ui.dp(c, 4), 0xffffc94a);
        ring.setBackground(g);
        int inner = sizeDp - 10;
        f.addView(Ui.avatar(c, who, who != null ? who.optString("username", "?") : "?", inner), new FrameLayout.LayoutParams(Ui.dp(c, inner), Ui.dp(c, inner), Gravity.CENTER));
        f.addView(ring, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        f.setElevation(Ui.dp(c, 8));
        return f;
    }

    private static LinearLayout cardBox(Context c, int[] colors) {
        LinearLayout card = Ui.vbox(c);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        Fx.bevel(card, colors, 24, 7, false);
        card.setPadding(Ui.dp(c, 18), Ui.dp(c, 6), Ui.dp(c, 18), Ui.dp(c, 24));
        card.setClipChildren(false);
        return card;
    }

    static View button(Context c, String label, int[] colors, int outline) {
        Fx.Tile t = new Fx.Tile(c, colors, 16, 5);
        Fx.GoldText l = Fx.GoldText.label(c, label, 17, outline);
        l.setGravity(Gravity.CENTER);
        l.setSingleLine(true);
        t.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return t;
    }

    /**
     * A full-screen, see-through window with a dark backdrop that centres its content
     * itself (no dialog theme can shift it), and flies it in and out.
     */
    static final class Shell extends Dialog {
        private final FrameLayout root;
        private final View scrim;
        private final FrameLayout safe;
        private View content;
        private boolean closing;

        Shell(BaseActivity a, boolean card) {
            super(a, android.R.style.Theme_Translucent_NoTitleBar);
            root = new FrameLayout(a);
            scrim = new View(a);
            scrim.setBackgroundColor(0xd90a0320);
            root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            safe = new FrameLayout(a);
            safe.setFitsSystemWindows(true);
            safe.setClipChildren(false);
            root.addView(safe, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            setContentView(root);
            Window w = getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            }
            setCancelable(true);
        }

        /** A card centred with equal room both sides. */
        void setCard(View card) {
            int screen = getContext().getResources().getDisplayMetrics().widthPixels;
            int width = Math.min(screen - Ui.dp(getContext(), 40), Ui.dp(getContext(), 380));
            safe.addView(card, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            content = card;
        }

        /** Full-screen content (the celebration). */
        void setStage(View stage) {
            safe.addView(stage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            content = stage;
        }

        @Override
        public void show() {
            super.show();
            scrim.setAlpha(0f);
            scrim.animate().alpha(1).setDuration(260).start();
            content.setScaleX(0.6f);
            content.setScaleY(0.6f);
            content.setAlpha(0f);
            content.setTranslationY(Ui.dp(getContext(), 60));
            content.animate().scaleX(1).scaleY(1).alpha(1).translationY(0).setDuration(480).setInterpolator(new OvershootInterpolator(1.5f)).start();
        }

        @Override
        public void onBackPressed() {
            close(null);
        }

        void close(Runnable after) {
            if (closing) return;
            closing = true;
            scrim.animate().alpha(0).setDuration(220).start();
            content.animate().scaleX(0.8f).scaleY(0.8f).alpha(0).setDuration(200).setInterpolator(new AccelerateInterpolator()).withEndAction(() -> {
                if (isShowing()) dismiss();
                if (after != null) after.run();
            }).start();
        }
    }

    /** Slowly turning golden rays. */
    static final class Rays extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;

        Rays(Context c, int color) {
            super(c);
            this.color = color;
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
            p.setShader(new RadialGradient(cx, cy, r, new int[]{color, (color & 0x00ffffff) | 0x33000000, color & 0x00ffffff}, new float[]{0, 0.6f, 1}, Shader.TileMode.CLAMP));
            RectF o = new RectF(cx - r, cy - r, cx + r, cy + r);
            for (int i = 0; i < 16; i++) c.drawArc(o, i * 22.5f, 11.25f, true, p);
        }
    }

    /**
     * Emoji drifting up (hearts, sparkles), falling (tears), or bursting out (confetti).
     */
    static final class Floaters extends View {
        private final String[] glyphs;
        private final boolean burst;
        boolean falling;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random rnd = new Random();
        private final java.util.List<float[]> bits = new java.util.ArrayList<>();
        private long last;
        private boolean running;

        Floaters(Context c, String[] glyphs, boolean burst) {
            super(c);
            this.glyphs = glyphs;
            this.burst = burst;
            p.setTextAlign(Paint.Align.CENTER);
            setClickable(false);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!burst) {
                running = true;
                last = System.nanoTime();
                postInvalidateOnAnimation();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            running = false;
        }

        void burst() {
            float d = getResources().getDisplayMetrics().density;
            for (int i = 0; i < 46; i++) {
                double ang = -Math.PI / 2 + (rnd.nextFloat() - 0.5f) * Math.PI * 1.6;
                float sp = (350 + rnd.nextFloat() * 650) * d;
                bits.add(new float[]{getWidth() / 2f, getHeight() * 0.42f, (float) Math.cos(ang) * sp, (float) Math.sin(ang) * sp,
                        (14 + rnd.nextFloat() * 14) * d, rnd.nextInt(glyphs.length), 1f, (rnd.nextFloat() - 0.5f) * 400, 0});
            }
            running = true;
            last = System.nanoTime();
            postInvalidateOnAnimation();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!running) return;
            long now = System.nanoTime();
            float dt = Math.min(0.05f, (now - last) / 1e9f);
            last = now;
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight();
            if (!burst && rnd.nextFloat() < dt * 4f) {
                // A new floater: hearts rise from the bottom, tears fall from the top.
                float y = falling ? -10 * d : h + 10 * d;
                bits.add(new float[]{w * (0.1f + rnd.nextFloat() * 0.8f), y, (rnd.nextFloat() - 0.5f) * 20 * d,
                        (falling ? 1 : -1) * (40 + rnd.nextFloat() * 40) * d, (12 + rnd.nextFloat() * 10) * d, rnd.nextInt(glyphs.length), 1f, 0, 0});
            }
            float g = burst ? 700 * d : 0;
            for (int i = bits.size() - 1; i >= 0; i--) {
                float[] b = bits.get(i);
                b[3] += g * dt;
                b[0] += b[2] * dt;
                b[1] += b[3] * dt;
                b[8] += b[7] * dt;
                if (!burst) b[6] = Math.max(0, Math.min(1, falling ? 1 - b[1] / h : b[1] / h));
                if (b[1] > h + 60 * d || b[1] < -60 * d || b[6] <= 0.02f) {
                    bits.remove(i);
                    continue;
                }
                p.setTextSize(b[4]);
                p.setAlpha((int) (255 * b[6]));
                c.save();
                c.rotate(b[8], b[0], b[1]);
                c.drawText(glyphs[(int) b[5]], b[0], b[1], p);
                c.restore();
            }
            if (burst && bits.isEmpty()) running = false;
            if (running) postInvalidateOnAnimation();
        }
    }
}
