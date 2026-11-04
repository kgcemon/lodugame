package com.ludox.game.ui;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludox.game.LudoApp;
import com.ludox.game.audio.Sfx;
import com.ludox.game.net.Api;

import org.json.JSONObject;

/**
 * Opening a gift, full screen: a floating box with a glow, OPEN GIFT, a shake and light, the
 * lid flies off and the real item appears with its name, quantity and rarity, then CLAIM
 * REWARD. The server says what is inside (POST open) and credits it (POST claim); this screen
 * only shows the answers. A claimed gift can be opened again to look at it: nothing is paid.
 */
final class GiftOpening {
    private static GiftOpening current;

    static boolean isOpen() {
        return current != null;
    }

    /** Fetch one gift by id, then open it (from a notification). */
    static void open(BaseActivity a, String id, Runnable closed) {
        a.app.api.get("/gifts/" + id, new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                JSONObject g = r.optJSONObject("gift");
                if (g != null) show(a, g, closed);
            }
        });
    }

    static void show(BaseActivity a, JSONObject gift, Runnable closed) {
        if (current != null || a.isFinishing()) return;
        current = new GiftOpening(a, gift, closed);
    }

    private final BaseActivity a;
    private JSONObject gift;
    private final FriendPopups.Shell shell;
    private final FrameLayout stage;
    private final FriendPopups.Rays rays;
    private final FriendPopups.Floaters confetti;
    private final GiftBox box;
    private final FrameLayout itemHolder;
    private final Fx.GoldText title;
    private final TextView sub, message, rarity;
    private final FrameLayout primary;
    private final TextView primaryLabel;
    private final TextView coinsPill, gemsPill;
    private final View secondary;
    private ObjectAnimator floating;
    private boolean busy;
    private Runnable onPrimary;

    private GiftOpening(BaseActivity a, JSONObject g, Runnable closed) {
        this.a = a;
        this.gift = g;
        shell = new FriendPopups.Shell(a, false);
        stage = new FrameLayout(a);
        stage.setClipChildren(false);

        rays = new FriendPopups.Rays(a, 0x88ffd54a);
        int rs = Ui.dp(a, 420);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(rs, rs, Gravity.CENTER);
        rl.bottomMargin = Ui.dp(a, 120);
        stage.addView(rays, rl);

        LinearLayout col = Ui.vbox(a);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        col.setPadding(Ui.dp(a, 22), 0, Ui.dp(a, 22), 0);
        JSONObject boxInfo = g.optJSONObject("box");
        title = new Fx.GoldText(a, boxInfo != null ? boxInfo.optString("label").toUpperCase() : "A GIFT FOR YOU!", 32);
        title.setGravity(Gravity.CENTER);
        col.addView(title, Ui.lpMatchWrap());
        sub = Ui.bold(Ui.text(a, "From " + g.optString("from", "LudoX"), 14, 0xfffff3c4));
        sub.setGravity(Gravity.CENTER);
        col.addView(sub, Ui.lpMatchWrap());

        FrameLayout center = new FrameLayout(a);
        center.setClipChildren(false);
        box = new GiftBox(a, boxInfo != null ? boxInfo.optString("id") : "");
        center.addView(box, new FrameLayout.LayoutParams(Ui.dp(a, 200), Ui.dp(a, 200), Gravity.CENTER));
        itemHolder = new FrameLayout(a);
        itemHolder.setClipChildren(false);
        itemHolder.setVisibility(View.GONE);
        center.addView(itemHolder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 170), Gravity.CENTER));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 220));
        cl.topMargin = Ui.dp(a, 6);
        col.addView(center, cl);

        rarity = Ui.bold(Ui.text(a, "", 12, Color.WHITE));
        rarity.setGravity(Gravity.CENTER);
        // Takes its room from the start, so the big button stays under the finger between steps.
        rarity.setVisibility(View.INVISIBLE);
        rarity.setPadding(Ui.dp(a, 12), Ui.dp(a, 3), Ui.dp(a, 12), Ui.dp(a, 3));
        col.addView(rarity, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        String words = g.optString("message");
        message = Ui.text(a, words.isEmpty() ? "" : "“" + words + "”", 15, Color.WHITE);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, Ui.dp(a, 8), 0, 0);
        col.addView(message, Ui.lpMatchWrap());

        primary = (FrameLayout) FriendPopups.button(a, "OPEN GIFT", Fx.YELLOW, 0xffa66f00);
        primaryLabel = (TextView) primary.getChildAt(0);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Ui.dp(a, 250), Ui.dp(a, 60));
        bl.topMargin = Ui.dp(a, 20);
        col.addView(primary, bl);
        secondary = Ui.smallButton(a, "Later", "glass", v -> shell.close(null));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = Ui.dp(a, 12);
        col.addView(secondary, sl);
        stage.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // The wallet the reward lands in.
        LinearLayout pills = Ui.hbox(a);
        coinsPill = pill();
        gemsPill = pill();
        pills.addView(coinsPill);
        pills.addView(Ui.gap(a, 8));
        pills.addView(gemsPill);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        pl.setMargins(0, Ui.dp(a, 12), Ui.dp(a, 12), 0);
        stage.addView(pills, pl);
        renderWallet();

        confetti = new FriendPopups.Floaters(a, new String[]{"🎉", "✨", "💛", "⭐", "🎊"}, true);
        stage.addView(confetti, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        primary.setOnClickListener(v -> {
            if (!busy && onPrimary != null) onPrimary.run();
        });
        onPrimary = this::open;
        shell.setStage(stage);
        shell.setOnDismissListener(d -> {
            current = null;
            if (floating != null) floating.cancel();
            if (closed != null) closed.run();
        });
        shell.show();

        boolean motion = Effects.level() != Effects.REDUCED;
        if (motion) {
            FriendPopups.spin(rays, 18000);
            floating = ObjectAnimator.ofFloat(box, "translationY", 0, -Ui.dp(a, 12), 0);
            floating.setDuration(2200);
            floating.setRepeatCount(ValueAnimator.INFINITE);
            floating.start();
            FriendPopups.breathe(primary);
        }
        FriendPopups.sound(Sfx.S.GIFT_NEW, 0.9f, 1f);
    }

    private TextView pill() {
        TextView t = Ui.bold(Ui.text(a, "", 13, Color.WHITE));
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.roundStroke(0xcc1f0f40, Ui.dp(a, 999), Ui.dp(a, 1), 0xaaffc94a));
        t.setPadding(Ui.dp(a, 10), Ui.dp(a, 4), Ui.dp(a, 10), Ui.dp(a, 4));
        return t;
    }

    private void renderWallet() {
        coinsPill.setText("🪙 " + Ui.fmt(a.app.balance()));
        gemsPill.setText("💎 " + Ui.fmt(a.app.wallet.optLong("gems")));
    }

    private void setPrimary(String label, Runnable action) {
        primaryLabel.setText(label);
        onPrimary = action;
    }

    private void close() {
        shell.close(null);
    }

    // ------------------------------------------------------------------ the steps

    /** OPEN GIFT: ask the server what is inside; a claimed gift already knows. */
    private void open() {
        if ("claimed".equals(gift.optString("status"))) {
            reveal();
            return;
        }
        busy = true;
        a.app.api.post("/gifts/" + gift.optString("id") + "/open", null, new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                busy = false;
                JSONObject g = r.optJSONObject("gift");
                if (g != null) gift = g;
                String st = gift.optString("status");
                if ("expired".equals(st) || "cancelled".equals(st)) gone(st);
                else reveal();
            }

            @Override
            public void err(Api.Error e) {
                busy = false;
                LudoApp.toast(e.message);
            }
        });
    }

    private void gone(String status) {
        title.setText("expired".equals(status) ? "GIFT EXPIRED" : "GIFT CANCELLED");
        sub.setText("expired".equals(status) ? "This gift can no longer be claimed." : "This gift was withdrawn by LudoX.");
        setPrimary("CLOSE", this::close);
        secondary.setVisibility(View.INVISIBLE);
        FriendPopups.sound(Sfx.S.ERROR, 0.7f, 1f);
    }

    /** The shake, the light, the lid, and the real item. */
    private void reveal() {
        secondary.setVisibility(View.INVISIBLE);
        busy = true;
        primary.setVisibility(View.INVISIBLE);
        if (Effects.level() == Effects.REDUCED) {
            showItem();
            return;
        }
        if (floating != null) floating.cancel();
        box.setTranslationY(0);
        FriendPopups.sound(Sfx.S.GIFT_OPEN, 1f, 1f);
        ObjectAnimator shake = ObjectAnimator.ofPropertyValuesHolder(box,
                PropertyValuesHolder.ofFloat("rotation", 0, -7, 7, -10, 10, -12, 12, -6, 0),
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.05f, 1f, 1.08f, 1f, 1.1f, 1f, 1.12f, 1.14f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 0.97f, 1f, 0.95f, 1f, 0.93f, 1f, 0.92f, 1.08f));
        shake.setDuration(480);
        shake.start();
        ValueAnimator lid = ValueAnimator.ofFloat(0f, 1f);
        lid.setStartDelay(460);
        lid.setDuration(420);
        lid.setInterpolator(new DecelerateInterpolator());
        lid.addUpdateListener(an -> box.setOpen((float) an.getAnimatedValue()));
        lid.start();
        rays.animate().scaleX(1.25f).scaleY(1.25f).setStartDelay(420).setDuration(400).start();
        LudoApp.MAIN.postDelayed(() -> {
            if (shell.isShowing()) showItem();
        }, 560);
    }

    private void showItem() {
        JSONObject c = gift.optJSONObject("content");
        busy = false;
        primary.setVisibility(View.VISIBLE);
        if (c == null) {
            // The server didn't say: never guess what's inside.
            gone("cancelled");
            return;
        }
        boolean already = "claimed".equals(gift.optString("status"));
        itemHolder.removeAllViews();
        View item = itemView(c);
        itemHolder.addView(item, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        itemHolder.setVisibility(View.VISIBLE);
        box.setAlpha(0.35f);
        if (Effects.level() != Effects.REDUCED) {
            item.setScaleX(0.1f);
            item.setScaleY(0.1f);
            item.setTranslationY(Ui.dp(a, 44));
            item.animate().scaleX(1).scaleY(1).translationY(0).setDuration(520).setInterpolator(new OvershootInterpolator(2f)).start();
            confetti.burst();
            FriendPopups.sound(Sfx.S.E_PARTY, 0.65f, 1.05f);
        }
        title.setText(already ? "YOUR GIFT" : "CONGRATULATIONS!");
        title.setTextSize(already ? 32 : 30);
        sub.setText(contentName(c));
        sub.setTextSize(19);
        String rare = c.optString("rarity");
        rarity.setText(rare.toUpperCase());
        rarity.setBackground(Ui.round(rarityColor(rare), Ui.dp(a, 999)));
        rarity.setVisibility(View.VISIBLE);
        if (already) {
            JSONObject got = gift.optJSONObject("received");
            message.setText("Claimed ✓" + (got != null && got.optBoolean("duplicate") ? "  " + refundLine(c, got) : ""));
            setPrimary("CLOSE", this::close);
        } else {
            setPrimary("CLAIM REWARD", this::claim);
        }
    }

    /** CLAIM REWARD: safe to press again after a lost reply, the server credits once. */
    private void claim() {
        busy = true;
        primaryLabel.setText("…");
        a.app.api.post("/gifts/" + gift.optString("id") + "/claim", null, new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                busy = false;
                a.app.mergeWallet(r.optJSONObject("wallet"));
                a.app.unclaimedGifts = r.optInt("unclaimed", a.app.unclaimedGifts);
                JSONObject g = r.optJSONObject("gift");
                if (g != null) gift = g;
                claimed(r.optJSONObject("reward"));
            }

            @Override
            public void err(Api.Error e) {
                busy = false;
                if ("GIFT_EXPIRED".equals(e.code)) gone("expired");
                else if ("GIFT_CANCELLED".equals(e.code)) gone("cancelled");
                else {
                    // Nothing was lost: the same button tries again.
                    primaryLabel.setText("CLAIM REWARD");
                    LudoApp.toast(e.message);
                }
            }
        });
    }

    private void claimed(JSONObject reward) {
        JSONObject c = gift.optJSONObject("content");
        JSONObject got = gift.optJSONObject("received");
        boolean duplicate = got != null && got.optBoolean("duplicate");
        String kind = c != null ? c.optString("kind") : "";
        title.setText("CLAIMED!");
        setPrimary("DONE", this::close);
        if (reward != null) {
            String rewardKind = reward.optString("kind");
            boolean diamonds = "diamonds".equals(rewardKind);
            RewardFx.Play p = new RewardFx.Play();
            p.reward = reward;
            p.counter = diamonds ? gemsPill : coinsPill;
            p.anchor = p.counter;
            p.prefix = diamonds ? "💎 " : "🪙 ";
            p.from = itemHolder;
            p.announce = false;
            RewardFx.play(stage, p);
            if (duplicate && c != null && got != null) message.setText(refundLine(c, got));
            else message.setText(diamonds ? "Added to your diamonds" : "Added to your wallet");
        } else if (duplicate) {
            message.setText("You already own this.");
            FriendPopups.sound(Sfx.S.MESSAGE, 0.8f, 1f);
        } else {
            message.setText("Added to your collection");
            FriendPopups.sound(Sfx.S.HOME, 0.9f, 1.1f);
            String shop = "dice".equals(kind) ? "/skins" : "board".equals(kind) ? "/boards" : "/frames";
            String id = c != null ? c.optString("itemId") : "";
            setPrimary("USE IT NOW", () -> equip(shop, id, kind));
            secondary.setVisibility(View.VISIBLE);
            if (secondary instanceof TextView) ((TextView) secondary).setText("Done");
        }
        renderWalletExcept(reward);
    }

    /** The pill that isn't counting up shows the server's number straight away. */
    private void renderWalletExcept(JSONObject reward) {
        if (reward == null) {
            renderWallet();
            return;
        }
        if ("diamonds".equals(reward.optString("kind"))) coinsPill.setText("🪙 " + Ui.fmt(a.app.balance()));
        else gemsPill.setText("💎 " + Ui.fmt(a.app.wallet.optLong("gems")));
    }

    private void equip(String shop, String id, String kind) {
        busy = true;
        a.app.api.post(shop + "/" + id + "/equip", null, new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                busy = false;
                if ("board".equals(kind)) a.app.prefs.edit().putString(ShopActivity.PREF_BOARD, id).apply();
                if ("frame".equals(kind) && a.app.user != null) {
                    try {
                        a.app.user.put("frame", id);
                    } catch (org.json.JSONException ignored) {
                        // Not reachable: the key and value are plain strings.
                    }
                }
                LudoApp.toast("Now in use ✓");
                close();
            }

            @Override
            public void err(Api.Error e) {
                busy = false;
                LudoApp.toast(e.message);
            }
        });
    }

    // ------------------------------------------------------------------ what's inside

    private static String refundLine(JSONObject c, JSONObject got) {
        long n = got.optLong("amount");
        String unit = "diamonds".equals(got.optString("kind")) ? "diamonds" : "coins";
        return n > 0 ? "You already own " + c.optString("name") + ", so you got " + Ui.fmt(n) + " " + unit + " instead." : "You already own " + c.optString("name") + ".";
    }

    private static String contentName(JSONObject c) {
        String kind = c.optString("kind");
        long q = c.optLong("quantity");
        if ("coins".equals(kind)) return Ui.fmt(q) + (q == 1 ? " coin" : " coins");
        if ("diamonds".equals(kind)) return Ui.fmt(q) + (q == 1 ? " diamond" : " diamonds");
        return c.optString("name") + ("dice".equals(kind) ? " dice" : "board".equals(kind) ? " board" : " frame");
    }

    private static int rarityColor(String r) {
        switch (r) {
            case "rare": return 0xff1b8fe8;
            case "epic": return 0xff8b3df0;
            case "legendary": return 0xffe08a00;
            case "mythic": return 0xffe0307d;
            case "ultimate": return 0xffe53945;
            default: return 0xff6c7293;
        }
    }

    private View itemView(JSONObject c) {
        String kind = c.optString("kind");
        String id = c.optString("itemId");
        switch (kind) {
            case "dice": {
                DiceView d = new DiceView(a, false);
                d.setSkin(DiceView.Skin.byId(id));
                d.set(6, false, false);
                return sized(d, 130, 130);
            }
            case "board":
                return sized(new BoardPreview(a, id, true), 150, 150);
            case "frame": {
                AvatarView av = Ui.avatar(a, a.app.user, a.app.user != null ? a.app.user.optString("username") : "?", 130);
                av.frame(id);
                return sized(av, 130, 130);
            }
            default: {
                ImageView coin = new ImageView(a);
                long q = c.optLong("quantity");
                String style = "diamonds".equals(kind) ? (q >= 100 ? "legendary_diamond" : "diamond") : (q >= 5000 ? "crown_coin" : q >= 100 ? "gold_coin" : "silver_coin");
                coin.setImageBitmap(RewardFx.sprite(a, style, 120));
                coin.setScaleType(ImageView.ScaleType.FIT_CENTER);
                return sized(coin, 120, 120);
            }
        }
    }

    private View sized(View v, int wDp, int hDp) {
        FrameLayout f = new FrameLayout(a);
        f.addView(v, new FrameLayout.LayoutParams(Ui.dp(a, wDp), Ui.dp(a, hDp), Gravity.CENTER));
        return f;
    }

    /** A gift box drawn in three-quarter light, its colours by style; its lid lifts off as it opens. */
    private static final class GiftBox extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] body;
        private final int ribbon;
        private final boolean question;
        private float open;
        private Shader bodyShader, lidShader, glowShader;

        GiftBox(Context c, String style) {
            super(c);
            switch (style) {
                case "royal": body = new int[]{0xff9f6bff, 0xff4b1aa8}; ribbon = 0xffffc94a; break;
                case "legendary": body = new int[]{0xffffd65c, 0xffd97a00}; ribbon = 0xffb5121b; break;
                case "mystery": body = new int[]{0xff37405f, 0xff141829}; ribbon = 0xff33e0d0; break;
                case "festival": body = new int[]{0xffff6ba6, 0xffc2185b}; ribbon = 0xffffe066; break;
                case "vip": body = new int[]{0xff3a3a42, 0xff0c0c10}; ribbon = 0xffffc94a; break;
                default: body = new int[]{0xffff6b6b, 0xffb81d2a}; ribbon = 0xffffd65c; break;
            }
            question = "mystery".equals(style);
        }

        void setOpen(float t) {
            open = t;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            bodyShader = new LinearGradient(w * 0.15f, 0, w * 0.85f, 0, new int[]{body[0], body[1]}, null, Shader.TileMode.CLAMP);
            lidShader = new LinearGradient(w * 0.1f, 0, w * 0.9f, 0, new int[]{Fx.blend(body[0], Color.WHITE, 0.18f), body[1]}, null, Shader.TileMode.CLAMP);
            glowShader = new RadialGradient(w / 2f, h * 0.55f, w * 0.52f, new int[]{0xccfff3a8, 0x44ffd54a, 0x00ffd54a}, new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setShader(glowShader);
            p.setAlpha((int) (140 + 115 * open));
            c.drawCircle(w / 2f, h * 0.55f, w * 0.52f, p);
            p.setAlpha(255);
            float left = w * 0.17f, right = w * 0.83f, top = h * 0.44f, bottom = h * 0.9f;
            // Contact shadow, then the body and its ribbon.
            p.setShader(null);
            p.setColor(0x55000000);
            c.drawOval(new RectF(left, bottom - h * 0.03f, right, bottom + h * 0.05f), p);
            // A shader is multiplied by the paint's alpha: back to opaque after the shadow.
            p.setColor(Color.BLACK);
            p.setShader(bodyShader);
            c.drawRoundRect(new RectF(left, top, right, bottom), w * 0.03f, w * 0.03f, p);
            p.setShader(null);
            p.setColor(ribbon);
            c.drawRect(w / 2f - w * 0.055f, top, w / 2f + w * 0.055f, bottom, p);
            p.setColor(0x22000000);
            c.drawRect(left, top, right, top + h * 0.035f, p);
            if (open > 0) {
                // Light pours out of the open box.
                p.setColor(Color.argb((int) (190 * open), 255, 244, 190));
                c.drawOval(new RectF(left + w * 0.02f, top - h * 0.03f, right - w * 0.02f, top + h * 0.05f), p);
            }
            // The lid, with its bow, lifting and tipping away.
            c.save();
            c.translate(w * 0.22f * open, -h * 0.5f * open);
            c.rotate(26f * open, w / 2f, top);
            p.setAlpha(255);
            p.setShader(lidShader);
            p.setAlpha((int) (255 * (1 - open * 0.85f)));
            c.drawRoundRect(new RectF(left - w * 0.05f, top - h * 0.1f, right + w * 0.05f, top + h * 0.02f), w * 0.035f, w * 0.035f, p);
            p.setShader(null);
            p.setColor(ribbon);
            p.setAlpha((int) (255 * (1 - open * 0.85f)));
            c.drawRect(w / 2f - w * 0.055f, top - h * 0.1f, w / 2f + w * 0.055f, top + h * 0.02f, p);
            c.save();
            c.rotate(-24, w / 2f, top - h * 0.11f);
            c.drawOval(new RectF(w / 2f - w * 0.19f, top - h * 0.19f, w / 2f, top - h * 0.09f), p);
            c.restore();
            c.save();
            c.rotate(24, w / 2f, top - h * 0.11f);
            c.drawOval(new RectF(w / 2f, top - h * 0.19f, w / 2f + w * 0.19f, top - h * 0.09f), p);
            c.restore();
            c.drawCircle(w / 2f, top - h * 0.11f, w * 0.04f, p);
            c.restore();
            p.setAlpha(255);
            if (question && open < 0.1f) {
                p.setColor(0xff33e0d0);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(h * 0.2f);
                p.setFakeBoldText(true);
                c.drawText("?", w / 2f - w * 0.17f, bottom - h * 0.13f, p);
                c.drawText("?", w / 2f + w * 0.17f, bottom - h * 0.13f, p);
            }
        }
    }
}
