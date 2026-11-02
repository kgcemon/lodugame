package com.ludo3d.game.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.audio.Sfx;
import com.ludo3d.game.net.Api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The dice shop: dice skins bought once with diamonds and then shown on your die in every
 * match (opponents see it too). The big spinning die at the top previews the skin you
 * picked; the server sets the prices and keeps what you own.
 */
public class DiceShopActivity extends BaseActivity {
    private LinearLayout col;
    private TextView balance;
    private final Set<String> owned = new HashSet<>();
    private String equipped = DiceView.Skin.CLASSIC.id;
    private String selected;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        col = pageWithBack("Dice shop");
        col.addView(Ui.spinner(this));
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the diamond shop: show the new balance.
        if (balance != null) balance.setText(Ui.fmt(app.wallet.optLong("gems")));
    }

    private void load() {
        app.api.get("/skins", new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                apply(r);
                render();
            }
        });
    }

    private void apply(JSONObject r) {
        owned.clear();
        JSONArray o = r.optJSONArray("owned");
        for (int i = 0; o != null && i < o.length(); i++) owned.add(o.optString(i));
        owned.add(DiceView.Skin.CLASSIC.id);
        equipped = r.optString("equipped", DiceView.Skin.CLASSIC.id);
        if (selected == null) selected = equipped;
        app.mergeWallet(r.optJSONObject("wallet"));
    }

    private void render() {
        col.removeAllViews();
        List<DiceView.Skin> skins = DiceView.Skin.all();
        DiceView.Skin pick = DiceView.Skin.byId(selected);

        // Diamonds and a way to get more.
        LinearLayout bar = Ui.hbox(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(Ui.text(this, "💎", 26, Ui.INK));
        balance = new Fx.GoldText(this, Ui.fmt(app.wallet.optLong("gems")), 24, new int[]{0xffffffff, 0xffb9f2ff, 0xff4fd8ff}, 0xff0b4f8a);
        balance.setPadding(Ui.dp(this, 8), 0, 0, 0);
        bar.addView(balance, Ui.weight(1));
        bar.addView(Ui.smallButton(this, "+ Get diamonds", "blue", v -> startActivity(new Intent(this, GemShopActivity.class))));
        col.addView(bar, Ui.lpMatchWrap());

        // The picked skin, spinning, with what you can do with it.
        LinearLayout hero = Ui.card(this);
        Ui.margins(hero, this, 0, 12, 0, 0);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.addView(new Spinning(this, pick), new LinearLayout.LayoutParams(Ui.dp(this, 170), Ui.dp(this, 170)));
        TextView name = new Fx.GoldText(this, pick.name.toUpperCase(java.util.Locale.ROOT), 26);
        name.setGravity(Gravity.CENTER);
        hero.addView(name, Ui.lpMatchWrap());
        boolean have = owned.contains(pick.id);
        boolean using = pick.id.equals(equipped);
        TextView sub = Ui.muted(this, using ? "Your dice in every match — everyone at the table sees it."
                : have ? "You own this dice." : "Yours forever for " + pick.price + " diamonds.");
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 12));
        hero.addView(sub, Ui.lpMatchWrap());
        TextView action;
        if (using) {
            action = Ui.button(this, "✓ In use", "glass", null);
            Ui.setEnabled(action, false);
        } else if (have) {
            action = Ui.button(this, "Use this dice", "ok", v -> equip(pick));
        } else {
            action = Ui.button(this, "Buy for 💎 " + pick.price, "primary", v -> confirmBuy(pick));
        }
        hero.addView(action, Ui.lpMatchWrap());
        col.addView(hero, Ui.lpMatchWrap());

        // Every skin, three to a row.
        col.addView(Ui.heading(this, "All dice"));
        LinearLayout row = null;
        for (int i = 0; i < skins.size(); i++) {
            if (i % 3 == 0) {
                row = Ui.hbox(this);
                col.addView(row, Ui.lpMatchWrap());
            }
            row.addView(tile(skins.get(i)), tileLp(i % 3));
        }
        // Keep the last row's tiles the same width as the others.
        for (int k = skins.size() % 3; k != 0 && k < 3; k++) row.addView(new View(this), tileLp(k));
        TextView note = Ui.muted(this, "Diamonds come from the diamond shop. A dice is bought once and kept forever.");
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, Ui.dp(this, 14), 0, 0);
        col.addView(note);
    }

    private LinearLayout.LayoutParams tileLp(int column) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.topMargin = Ui.dp(this, 10);
        lp.leftMargin = column == 0 ? 0 : Ui.dp(this, 5);
        lp.rightMargin = column == 2 ? 0 : Ui.dp(this, 5);
        return lp;
    }

    private View tile(DiceView.Skin k) {
        boolean picked = k.id.equals(selected);
        Fx.Tile t = new Fx.Tile(this, picked ? Fx.YELLOW : Fx.PURPLE, 16, 5);
        LinearLayout box = Ui.vbox(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 12));
        box.addView(new Face(this, k), new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));
        TextView n = Ui.bold(Ui.text(this, k.name, 13, picked ? 0xff4a2600 : Ui.INK));
        n.setGravity(Gravity.CENTER);
        n.setSingleLine(true);
        n.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(n, Ui.lpMatchWrap());
        String tag = k.id.equals(equipped) ? "✓ In use" : owned.contains(k.id) ? "Owned" : "💎 " + k.price;
        TextView p = Ui.bold(Ui.text(this, tag, 12, picked ? 0xff7a4a00 : 0xffd9cfff));
        p.setGravity(Gravity.CENTER);
        box.addView(p, Ui.lpMatchWrap());
        t.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        t.setOnClickListener(v -> {
            if (k.id.equals(selected)) return;
            selected = k.id;
            render();
        });
        return t;
    }

    private void confirmBuy(DiceView.Skin k) {
        if (busy) return;
        long have = app.wallet.optLong("gems");
        if (have < k.price) {
            notEnough(k);
            return;
        }
        Popup.message(this, "Buy " + k.name + " dice?", "💎 " + k.price + " diamonds. It's yours forever and goes on your die right away.",
                Popup.plain("Cancel", null), Popup.ok("Buy", () -> buy(k)));
    }

    private void notEnough(DiceView.Skin k) {
        Sfx.get().play(Sfx.S.ERROR);
        long need = k.price - app.wallet.optLong("gems");
        Popup.message(this, "Not enough diamonds", "💎\nYou need " + Math.max(1, need) + " more diamonds for the " + k.name + " dice.",
                Popup.plain("Close", null), new Popup.Btn("Diamond shop", "gold", () -> startActivity(new Intent(this, GemShopActivity.class))));
    }

    private void buy(DiceView.Skin k) {
        busy = true;
        app.api.post("/skins/" + k.id + "/buy", new JSONObject(), new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                busy = false;
                apply(r);
                Sfx.get().play(Sfx.S.MATCH_FOUND);
                LudoApp.toast(k.name + " dice is yours!");
                render();
            }

            @Override
            public void err(Api.Error e) {
                busy = false;
                if ("NOT_ENOUGH_GEMS".equals(e.code) || e.status == 402) notEnough(k);
                else super.err(e);
            }
        });
    }

    private void equip(DiceView.Skin k) {
        if (busy) return;
        busy = true;
        app.api.post("/skins/" + k.id + "/equip", new JSONObject(), new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                busy = false;
                apply(r);
                Sfx.get().play(Sfx.S.EXTRA);
                render();
            }

            @Override
            public void err(Api.Error e) {
                busy = false;
                super.err(e);
            }
        });
    }

    /** A skin's die face-on (the 5), for the grid. */
    private static final class Face extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final DiceView.Skin skin;

        Face(Context c, DiceView.Skin skin) {
            super(c);
            this.skin = skin;
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float s = Math.min(getWidth(), getHeight()) * 0.86f;
            DiceView.drawDie(canvas, paint, path, (getWidth() - s) / 2f, (getHeight() - s) / 2f, s, 5, true, skin);
        }
    }

    /** The picked skin as a real 3D die turning slowly. */
    private static final class Spinning extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final DiceView.Skin skin;
        private final float[] tilt = DiceView.Cube.rot(new float[]{1, 0, 0}, 22f);
        private ValueAnimator spin;
        private float angle;

        Spinning(Context c, DiceView.Skin skin) {
            super(c);
            this.skin = skin;
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            spin = ValueAnimator.ofFloat(0f, 360f);
            spin.setDuration(6000);
            spin.setRepeatCount(ValueAnimator.INFINITE);
            spin.setInterpolator(new LinearInterpolator());
            spin.addUpdateListener(a -> {
                angle = (float) a.getAnimatedValue();
                invalidate();
            });
            spin.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (spin != null) spin.cancel();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float s = Math.min(getWidth(), getHeight());
            float[] m = DiceView.Cube.mul(tilt, DiceView.Cube.mul(DiceView.Cube.rot(new float[]{0, 1, 0}, angle),
                    DiceView.Cube.rot(new float[]{1, 0, 0}, -20f)));
            DiceView.Cube.draw(canvas, paint, path, getWidth() / 2f, getHeight() / 2f, s * 0.3f, m, true, skin);
        }
    }
}
