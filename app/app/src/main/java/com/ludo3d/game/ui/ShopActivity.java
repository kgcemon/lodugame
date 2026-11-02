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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The shop: dice skins and profile frames, each bought once with diamonds and then shown
 * in every match (opponents see them too). The big preview at the top shows what you
 * picked; the server sets the prices (an administrator can change them) and keeps what
 * you own.
 */
public class ShopActivity extends BaseActivity {
    /** One thing for sale: a dice skin or a profile frame. */
    private static final class Item {
        final String id, name;
        final int price;
        final DiceView.Skin skin;

        Item(String id, String name, int price, DiceView.Skin skin) {
            this.id = id;
            this.name = name;
            this.price = price;
            this.skin = skin;
        }
    }

    /** A shop tab: its server path, what it sells, and what you own / have on. */
    private static final class Shop {
        final String path, word;
        final List<Item> items = new ArrayList<>();
        final Set<String> owned = new HashSet<>();
        /** Null for frames means none. */
        String equipped;
        String selected;
        boolean loaded;

        Shop(String path, String word) {
            this.path = path;
            this.word = word;
        }

        Item byId(String id) {
            for (Item i : items) if (i.id.equals(id)) return i;
            return items.isEmpty() ? null : items.get(0);
        }
    }

    private final Shop dice = new Shop("/skins", "dice");
    private final Shop frames = new Shop("/frames", "frame");
    private Shop tab = dice;
    private LinearLayout col;
    private TextView balance;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        col = pageWithBack("Shop");
        if ("frames".equals(getIntent().getStringExtra("tab"))) tab = frames;
        col.addView(Ui.spinner(this));
        load(dice, "skins");
        load(frames, "frames");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the diamond shop: show the new balance.
        if (balance != null) balance.setText(Ui.fmt(app.wallet.optLong("gems")));
    }

    private void load(Shop s, String listKey) {
        app.api.get(s.path, new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                s.items.clear();
                JSONArray arr = r.optJSONArray(listKey);
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    if (s == dice) {
                        DiceView.Skin k = DiceView.Skin.from(o);
                        if (k != null) s.items.add(new Item(k.id, k.name, o.optInt("price"), k));
                    } else if (AvatarView.Frames.known(o.optString("id"))) {
                        s.items.add(new Item(o.optString("id"), o.optString("name"), o.optInt("price"), null));
                    }
                }
                if (s == dice && s.items.isEmpty()) {
                    for (DiceView.Skin k : DiceView.Skin.all()) s.items.add(new Item(k.id, k.name, k.price, k));
                }
                apply(s, r);
                s.loaded = true;
                if (s == tab) render();
            }
        });
    }

    private void apply(Shop s, JSONObject r) {
        s.owned.clear();
        JSONArray o = r.optJSONArray("owned");
        for (int i = 0; o != null && i < o.length(); i++) s.owned.add(o.optString(i));
        s.equipped = r.isNull("equipped") ? null : r.optString("equipped", null);
        if (s == dice) {
            s.owned.add(DiceView.Skin.CLASSIC.id);
            if (s.equipped == null) s.equipped = DiceView.Skin.CLASSIC.id;
        } else if (app.user != null) {
            // Everywhere else in the app reads the frame from the signed-in user.
            try {
                app.user.put("frame", s.equipped == null ? JSONObject.NULL : s.equipped);
            } catch (Exception ignored) {
            }
        }
        if (s.selected == null) s.selected = s.equipped != null ? s.equipped : s.items.isEmpty() ? null : s.items.get(0).id;
        app.mergeWallet(r.optJSONObject("wallet"));
    }

    private void render() {
        col.removeAllViews();

        // Diamonds and a way to get more.
        LinearLayout bar = Ui.hbox(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(Ui.text(this, "💎", 26, Ui.INK));
        balance = new Fx.GoldText(this, Ui.fmt(app.wallet.optLong("gems")), 24, new int[]{0xffffffff, 0xffb9f2ff, 0xff4fd8ff}, 0xff0b4f8a);
        balance.setPadding(Ui.dp(this, 8), 0, 0, 0);
        bar.addView(balance, Ui.weight(1));
        bar.addView(Ui.smallButton(this, "+ Get diamonds", "blue", v -> startActivity(new Intent(this, GemShopActivity.class))));
        col.addView(bar, Ui.lpMatchWrap());

        // Dice | Frames.
        LinearLayout tabs = Ui.hbox(this);
        Ui.margins(tabs, this, 0, 12, 0, 0);
        String[][] opts = {{"dice", "🎲  Dice"}, {"frame", "🔥  Frames"}};
        for (String[] o : opts) {
            Shop s = "dice".equals(o[0]) ? dice : frames;
            TextView b = Ui.button(this, o[1], s == tab ? "primary" : "glass", v -> {
                if (tab == s) return;
                tab = s;
                render();
            });
            LinearLayout.LayoutParams lp = Ui.weight(1);
            if (s == dice) lp.rightMargin = Ui.dp(this, 8);
            tabs.addView(b, lp);
        }
        col.addView(tabs, Ui.lpMatchWrap());

        if (!tab.loaded || tab.items.isEmpty()) {
            col.addView(tab.loaded ? Ui.muted(this, "Nothing for sale here yet.") : Ui.spinner(this));
            return;
        }
        Item pick = tab.byId(tab.selected);

        // The picked item, big, with what you can do with it.
        LinearLayout hero = Ui.card(this);
        Ui.margins(hero, this, 0, 12, 0, 0);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.addView(preview(pick, 170), new LinearLayout.LayoutParams(Ui.dp(this, 170), Ui.dp(this, 170)));
        TextView name = new Fx.GoldText(this, pick.name.toUpperCase(java.util.Locale.ROOT), 26);
        name.setGravity(Gravity.CENTER);
        hero.addView(name, Ui.lpMatchWrap());
        boolean have = tab.owned.contains(pick.id);
        boolean using = pick.id.equals(tab.equipped);
        String everyone = tab == dice ? "Your dice in every match — everyone at the table sees it."
                : "Round your picture everywhere — friends and every table see it.";
        TextView sub = Ui.muted(this, using ? everyone : have ? "You own this " + tab.word + "."
                : pick.price == 0 ? "Free — take it!" : "Yours forever for " + pick.price + " diamonds.");
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 12));
        hero.addView(sub, Ui.lpMatchWrap());
        TextView action;
        if (using) {
            if (tab == frames) {
                action = Ui.button(this, "Take frame off", "glass", v -> equip(tab, null, "none"));
            } else {
                action = Ui.button(this, "✓ In use", "glass", null);
                Ui.setEnabled(action, false);
            }
        } else if (have) {
            action = Ui.button(this, "Use this " + tab.word, "ok", v -> equip(tab, pick, pick.id));
        } else {
            action = Ui.button(this, pick.price == 0 ? "Get it free" : "Buy for 💎 " + pick.price, "primary", v -> confirmBuy(tab, pick));
        }
        hero.addView(action, Ui.lpMatchWrap());
        col.addView(hero, Ui.lpMatchWrap());

        // Everything in this tab, three to a row.
        col.addView(Ui.heading(this, tab == dice ? "All dice" : "All frames"));
        LinearLayout row = null;
        List<Item> items = tab.items;
        for (int i = 0; i < items.size(); i++) {
            if (i % 3 == 0) {
                row = Ui.hbox(this);
                col.addView(row, Ui.lpMatchWrap());
            }
            row.addView(tile(items.get(i)), tileLp(i % 3));
        }
        // Keep the last row's tiles the same width as the others.
        for (int k = items.size() % 3; k != 0 && k < 3; k++) row.addView(new View(this), tileLp(k));
        TextView note = Ui.muted(this, "Diamonds come from the diamond shop. Everything here is bought once and kept forever.");
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, Ui.dp(this, 14), 0, 0);
        col.addView(note);
    }

    /** The item drawn big or small: a spinning die, or your own picture in the frame. */
    private View preview(Item k, int sizeDp) {
        if (k.skin != null) return sizeDp >= 100 ? new Spinning(this, k.skin) : new Face(this, k.skin);
        JSONObject me = app.user;
        String name = me != null ? me.optString("username", "?") : "?";
        AvatarView v = Ui.avatar(this, me, name, sizeDp);
        return v.frame(k.id);
    }

    private LinearLayout.LayoutParams tileLp(int column) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.topMargin = Ui.dp(this, 10);
        lp.leftMargin = column == 0 ? 0 : Ui.dp(this, 5);
        lp.rightMargin = column == 2 ? 0 : Ui.dp(this, 5);
        return lp;
    }

    private View tile(Item k) {
        boolean picked = k.id.equals(tab.selected);
        Fx.Tile t = new Fx.Tile(this, picked ? Fx.YELLOW : Fx.PURPLE, 16, 5);
        LinearLayout box = Ui.vbox(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 12));
        int size = k.skin != null ? 56 : 66;
        box.addView(preview(k, size), new LinearLayout.LayoutParams(Ui.dp(this, size), Ui.dp(this, size)));
        TextView n = Ui.bold(Ui.text(this, k.name, 13, picked ? 0xff4a2600 : Ui.INK));
        n.setGravity(Gravity.CENTER);
        n.setSingleLine(true);
        n.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(n, Ui.lpMatchWrap());
        String tag = k.id.equals(tab.equipped) ? "✓ In use" : tab.owned.contains(k.id) ? "Owned" : k.price == 0 ? "Free" : "💎 " + k.price;
        TextView p = Ui.bold(Ui.text(this, tag, 12, picked ? 0xff7a4a00 : 0xffd9cfff));
        p.setGravity(Gravity.CENTER);
        box.addView(p, Ui.lpMatchWrap());
        t.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        t.setOnClickListener(v -> {
            if (k.id.equals(tab.selected)) return;
            tab.selected = k.id;
            render();
        });
        return t;
    }

    private void confirmBuy(Shop s, Item k) {
        if (busy) return;
        long have = app.wallet.optLong("gems");
        if (have < k.price) {
            notEnough(s, k);
            return;
        }
        if (k.price == 0) {
            buy(s, k);
            return;
        }
        String where = s == dice ? "It goes on your die right away." : "It goes round your picture right away.";
        Popup.message(this, "Buy " + k.name + " " + s.word + "?", "💎 " + k.price + " diamonds. It's yours forever. " + where,
                Popup.plain("Cancel", null), Popup.ok("Buy", () -> buy(s, k)));
    }

    private void notEnough(Shop s, Item k) {
        Sfx.get().play(Sfx.S.ERROR);
        long need = k.price - app.wallet.optLong("gems");
        Popup.message(this, "Not enough diamonds", "💎\nYou need " + Math.max(1, need) + " more diamonds for the " + k.name + " " + s.word + ".",
                Popup.plain("Close", null), new Popup.Btn("Diamond shop", "gold", () -> startActivity(new Intent(this, GemShopActivity.class))));
    }

    private void buy(Shop s, Item k) {
        busy = true;
        app.api.post(s.path + "/" + k.id + "/buy", new JSONObject(), new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                busy = false;
                apply(s, r);
                Sfx.get().play(Sfx.S.MATCH_FOUND);
                LudoApp.toast(k.name + " " + s.word + " is yours!");
                render();
            }

            @Override
            public void err(Api.Error e) {
                busy = false;
                if ("NOT_ENOUGH_GEMS".equals(e.code) || e.status == 402) notEnough(s, k);
                else super.err(e);
            }
        });
    }

    /** Put on an item you own (`none` takes the frame off). */
    private void equip(Shop s, Item k, String id) {
        if (busy) return;
        busy = true;
        app.api.post(s.path + "/" + id + "/equip", new JSONObject(), new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                busy = false;
                apply(s, r);
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
