package com.ludox.game.ui;

import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludox.game.LudoApp;
import com.ludox.game.audio.Sfx;
import com.ludox.game.game.Social;
import com.ludox.game.gl.GiftChooser;
import com.ludox.game.gl.GiftRenderer;

import org.json.JSONObject;

/**
 * A gift up close: the real 3D model turning in its own light, with its arrival animation, a replay
 * button and, for a gift I received and haven't answered, a row of thanks.
 */
final class GiftReveal {
    private GiftReveal() {}

    /**
     * `gift` has name, model, rarity, particle, receiveAnim and optionally id; `fromName` may be null.
     * With `canThank` the recipient can answer with an emote, once.
     */
    static void show(BaseActivity a, JSONObject gift, String fromName, boolean canThank) {
        LinearLayout box = Ui.vbox(a);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        GiftRenderer renderer = new GiftRenderer(Effects.level());
        GLSurfaceView gl = new GLSurfaceView(a);
        gl.setEGLContextClientVersion(2);
        gl.setEGLConfigChooser(new GiftChooser());
        gl.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        gl.setZOrderOnTop(true);
        gl.setRenderer(renderer);
        gl.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        renderer.requestRender = gl::requestRender;
        FrameLayout stage = new FrameLayout(a);
        stage.addView(gl, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 230)));

        String rarity = gift.optString("rarity");
        TextView tier = Ui.bold(Ui.text(a, GiftShopPanel.rarityLabel(rarity).toUpperCase(java.util.Locale.ROOT), 12, GiftShopPanel.rarityColor(rarity)));
        tier.setGravity(Gravity.CENTER);
        box.addView(tier, Ui.lpMatchWrap());
        if (fromName != null) {
            TextView from = Ui.text(a, "From " + fromName, 15, Ui.INK);
            from.setGravity(Gravity.CENTER);
            box.addView(from, Ui.lpMatchWrap());
        }
        TextView kept = Ui.muted(a, "A keepsake for your collection. Gifts don't add coins or diamonds.");
        kept.setGravity(Gravity.CENTER);
        kept.setPadding(0, Ui.dp(a, 4), 0, 0);
        box.addView(kept, Ui.lpMatchWrap());

        Runnable play = () -> {
            renderer.clear();
            GiftRenderer.Flight f = new GiftRenderer.Flight();
            f.id = gift.optString("id");
            f.model = gift.optString("model");
            f.send = "float";
            f.receive = gift.optString("receiveAnim", "pop");
            f.particle = gift.optString("particle", "sparks");
            f.rarity = rarity;
            f.stage = true;
            int w = Math.max(1, stage.getWidth()), h = Math.max(1, stage.getHeight());
            f.x0 = f.x1 = w / 2f;
            f.y0 = f.y1 = h * 0.54f;
            f.size = Math.min(w, h) * 0.17f;
            f.durationMs = 2600;
            // Straight to the arrival: it's already here.
            f.startAt = SystemClock.elapsedRealtime() - Math.round(f.durationMs * GiftRenderer.FLY_END);
            renderer.play(f);
            Sfx.get().play("legendary".equals(rarity) ? Sfx.S.GIFT_OPEN : Sfx.S.GIFT_NEW, 0.8f, 1f);
        };
        stage.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l <= 0) return;
                v.removeOnLayoutChangeListener(this);
                play.run();
            }
        });

        if (canThank && gift.has("id")) {
            LinearLayout row = Ui.hbox(a);
            row.setGravity(Gravity.CENTER);
            row.setPadding(0, Ui.dp(a, 8), 0, 0);
            for (String id : Social.INSTANT_REACTIONS) {
                TextView e = Ui.text(a, Social.emoji(id), 24, Ui.INK);
                e.setGravity(Gravity.CENTER);
                e.setContentDescription("Thank them with " + id);
                e.setOnClickListener(v -> {
                    LudoApp.get().socket.emit("playerGift:react", PlayFlow.obj("giftId", gift.optString("id"), "reaction", id), PlayFlow.toastOnError());
                    row.removeAllViews();
                    TextView done = Ui.text(a, Social.emoji(id) + " Thanks sent", 14, Ui.GOLD);
                    row.addView(done);
                });
                row.addView(e, Ui.weight(1));
            }
            box.addView(row, Ui.lpMatchWrap());
        }

        Popup popup = Popup.show(a, gift.optString("name"), box, new Popup.Btn("▶ Replay", "plain", play).keepOpen(), Popup.ok("Close", null));
        popup.setOnDismissListener(d -> gl.onPause());
    }
}
