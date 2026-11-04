package com.ludox.game.ui;

import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludox.game.LudoApp;
import com.ludox.game.audio.Sfx;
import com.ludox.game.gl.GiftChooser;
import com.ludox.game.gl.GiftRenderer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Gifts crossing the match screen: the 3D journey from the sender's card to the recipient's, the
 * line saying who sent what to whom and, for the recipient, a small card to look at it and say
 * thanks. Everything here sits above the game and takes no touches except that card.
 */
final class GiftFlights {
    /** Where a player's card is, in the coordinates of `root`'s content; null when not on screen. */
    interface Anchors {
        float[] of(String color);
    }

    /** The drawing surface goes away this long after the last gift: nothing to composite during a normal match. */
    private static final long IDLE_MS = 20_000;

    private final BaseActivity a;
    private final FrameLayout root;
    private final Anchors anchors;
    private final GiftRenderer renderer;
    private GLSurfaceView gl;
    private TextView banner;
    private View card;
    private final Runnable hide = () -> {
        if (gl != null) gl.setVisibility(View.GONE);
    };

    GiftFlights(BaseActivity a, FrameLayout root, Anchors anchors) {
        this.a = a;
        this.root = root;
        this.anchors = anchors;
        renderer = new GiftRenderer(Effects.level());
        renderer.onIdle = () -> LudoApp.MAIN.postDelayed(hide, IDLE_MS);
    }

    private void surface() {
        LudoApp.MAIN.removeCallbacks(hide);
        if (gl == null) {
            gl = new GLSurfaceView(a);
            gl.setEGLContextClientVersion(2);
            gl.setEGLConfigChooser(new GiftChooser());
            gl.getHolder().setFormat(PixelFormat.TRANSLUCENT);
            // Above the cards and the bars, which sit above the board's own surface.
            gl.setZOrderOnTop(true);
            gl.setRenderer(renderer);
            gl.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            renderer.requestRender = gl::requestRender;
            root.addView(gl, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        gl.setVisibility(View.VISIBLE);
    }

    private static float sizeDp(String rarity) {
        switch (rarity) {
            case "rare": return 32;
            case "epic": return 38;
            case "legendary": return 46;
            default: return 28;
        }
    }

    private GiftRenderer.Flight flight(String id, JSONObject look, float[] from, float[] to, long ms, boolean mine) {
        GiftRenderer.Flight f = new GiftRenderer.Flight();
        f.id = id;
        f.model = look.optString("model");
        f.send = look.optString("sendAnim", "arc");
        f.receive = look.optString("receiveAnim", "pop");
        f.particle = look.optString("particle", "sparks");
        f.rarity = look.optString("rarity", "common");
        f.x0 = from[0];
        f.y0 = from[1];
        f.x1 = to[0];
        f.y1 = to[1];
        f.size = Ui.dp(a, sizeDp(f.rarity));
        f.durationMs = ms;
        f.startAt = SystemClock.elapsedRealtime();
        f.mine = mine;
        return f;
    }

    /** Try a gift out, on this phone only: nothing is sent or charged. */
    void preview(JSONObject product, String fromColor, String toColor) {
        float[] from = anchors.of(fromColor), to = anchors.of(toColor);
        if (from == null || to == null) return;
        surface();
        renderer.play(flight("preview", product, from, to, 3000, false));
        Sfx.get().play(Sfx.S.GIFT_NEW, 0.6f, 1f);
    }

    /** The server's announcement of a gift. Each one flies once, whatever is sent again. */
    void play(JSONObject e) {
        String id = e.optString("id");
        boolean first = LudoApp.get().socket.giftsPlayed.add(id);
        if (!first) return;
        JSONObject fromSeat = e.optJSONObject("from"), toSeat = e.optJSONObject("to");
        if (fromSeat == null || toSeat == null) return;
        float[] from = anchors.of(fromSeat.optString("color")), to = anchors.of(toSeat.optString("color"));
        boolean mine = toSeat.optString("seatId").equals(LudoApp.get().socket.mySeatId());
        long ms = Math.max(1000, e.optLong("durationMs", 3000));
        String name = e.optString("name");
        String who = fromSeat.optString("name"), whom = toSeat.optString("name");
        if (from != null && to != null) {
            surface();
            renderer.play(flight(id, e, from, to, ms, mine));
        }
        String rarity = e.optString("rarity");
        Sfx.get().play("legendary".equals(rarity) ? Sfx.S.GIFT_OPEN : Sfx.S.GIFT_NEW, 0.7f, 1f);
        banner(who.toUpperCase(Locale.getDefault()) + " IS SENDING A GIFT TO " + whom.toUpperCase(Locale.getDefault()), rarity);
        LudoApp.MAIN.postDelayed(() -> {
            if (a.isFinishing()) return;
            Sfx.get().play(arrivalSound(e.optString("particle")), mine ? 0.9f : 0.6f, 1f);
            String article = "AEIOUaeiou".indexOf(name.charAt(0)) >= 0 ? " AN " : " A ";
            banner(who.toUpperCase(Locale.getDefault()) + " SENT" + article + name.toUpperCase(Locale.getDefault()) + " TO " + whom.toUpperCase(Locale.getDefault()) + "!", rarity);
            if (mine) {
                recipientCard(e, who);
                JSONArray ids = new JSONArray();
                ids.put(id);
                LudoApp.get().socket.emit("playerGift:seen", PlayFlow.obj("ids", ids), null);
            }
        }, Math.round(ms * GiftRenderer.FLY_END));
    }

    private static Sfx.S arrivalSound(String particle) {
        switch (particle) {
            case "hearts": case "petals": return Sfx.S.E_LOVE;
            case "fire": return Sfx.S.E_FIRE;
            case "confetti": return Sfx.S.E_PARTY;
            case "magic": return Sfx.S.E_WOW;
            case "diamonds": return Sfx.S.DIAMOND;
            default: return Sfx.S.COIN;
        }
    }

    private void banner(String text, String rarity) {
        if (banner == null) {
            banner = Ui.bold(Ui.text(a, "", 13, Color.WHITE));
            banner.setGravity(Gravity.CENTER);
            banner.setSingleLine(true);
            banner.setEllipsize(TextUtils.TruncateAt.END);
            banner.setPadding(Ui.dp(a, 12), Ui.dp(a, 5), Ui.dp(a, 12), Ui.dp(a, 5));
            FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            l.topMargin = Ui.dp(a, 56);
            l.leftMargin = l.rightMargin = Ui.dp(a, 8);
            root.addView(banner, l);
        }
        int edge = GiftShopPanel.rarityColor(rarity);
        banner.setBackground(Ui.roundStroke(0xee1b0a45, Ui.dp(a, 999), Ui.dp(a, 1), edge));
        banner.setText(text);
        banner.animate().cancel();
        banner.setAlpha(1f);
        banner.setVisibility(View.VISIBLE);
        banner.animate().alpha(0f).setStartDelay(2800).setDuration(400).start();
    }

    /** "You received …": the way into the gift, out of the way of the board's middle. */
    private void recipientCard(JSONObject e, String sender) {
        if (card != null) root.removeView(card);
        LinearLayout c = Ui.hbox(a);
        c.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xf8421c94, 0xf8241059});
        bg.setCornerRadius(Ui.dp(a, 16));
        bg.setStroke(Ui.dp(a, 2), 0xffffc94a);
        c.setBackground(bg);
        c.setPadding(Ui.dp(a, 8), Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6));
        c.addView(GiftShopPanel.thumb(a, e.optString("model")), new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44)));
        TextView t = Ui.bold(Ui.text(a, "🎁 You received " + e.optString("name") + " from " + sender + "!", 13, 0xfffff3c4));
        t.setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 8), 0);
        c.addView(t, Ui.weight(1));
        c.addView(Ui.smallButton(a, "View", "gold", v -> {
            dismissCard();
            GiftReveal.show(a, e, sender, true);
        }));
        FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        l.setMargins(Ui.dp(a, 10), 0, Ui.dp(a, 10), Ui.dp(a, 74));
        c.setTranslationY(Ui.dp(a, 30));
        c.setAlpha(0f);
        c.animate().translationY(0).alpha(1f).setDuration(260).start();
        card = c;
        root.addView(c, l);
        View shown = c;
        LudoApp.MAIN.postDelayed(() -> {
            if (card == shown) dismissCard();
        }, 8000);
    }

    private void dismissCard() {
        if (card != null) root.removeView(card);
        card = null;
    }

    void onResume() {
        if (gl != null) gl.onResume();
    }

    void onPause() {
        if (gl != null) gl.onPause();
        renderer.clear();
    }

    void stop() {
        LudoApp.MAIN.removeCallbacks(hide);
        renderer.onIdle = null;
    }
}
