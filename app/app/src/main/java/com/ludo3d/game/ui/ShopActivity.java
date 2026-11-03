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
import android.view.ViewParent;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.audio.Sfx;
import com.ludo3d.game.gl.BoardThemes;
import com.ludo3d.game.net.Api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The shop: dice skins, profile frames and royal boards, each bought once with diamonds
 * and then shown in every match (opponents see them too; a room plays on its host's
 * board). The big preview at the top shows what you picked; tap any tile below to look at
 * it there. The server sets the prices (an administrator can change them) and keeps what
 * you own.
 */
public class ShopActivity extends BaseActivity {
    /** The board I play on offline (online the room plays on its host's). */
    public static final String PREF_BOARD = "board";

    /** One thing for sale: a dice skin, a profile frame or a board. */
    private static final class Item {
        final String id, name, blurb;
        final int price;
        final boolean royal;
        final DiceView.Skin skin;

        Item(String id, String name, int price, DiceView.Skin skin, String blurb, boolean royal) {
            this.id = id;
            this.name = name;
            this.price = price;
            this.skin = skin;
            this.blurb = blurb;
            this.royal = royal;
        }
    }

    /** A shop tab: its server path, what it sells, and what you own / have on. */
    private static final class Shop {
        final String path, word, listKey;
        final int columns;
        final List<Item> items = new ArrayList<>();
        final Set<String> owned = new HashSet<>();
        /** Null for frames means none. */
        String equipped;
        String selected;
        boolean loaded;

        Shop(String path, String word, String listKey, int columns) {
            this.path = path;
            this.word = word;
            this.listKey = listKey;
            this.columns = columns;
        }

        Item byId(String id) {
            for (Item i : items) if (i.id.equals(id)) return i;
            return items.isEmpty() ? null : items.get(0);
        }
    }

    private final Shop dice = new Shop("/skins", "dice", "skins", 3);
    private final Shop frames = new Shop("/frames", "frame", "frames", 3);
    private final Shop boards = new Shop("/boards", "board", "boards", 2);
    private Shop tab = dice;
    private LinearLayout col;
    private TextView balance;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        col = pageWithBack("Shop");
        String want = getIntent().getStringExtra("tab");
        if ("frames".equals(want)) tab = frames;
        else if ("boards".equals(want)) tab = boards;
        col.addView(Ui.spinner(this));
        load(dice);
        load(frames);
        load(boards);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the diamond shop: show the new balance.
        if (balance != null) balance.setText(Ui.fmt(app.wallet.optLong("gems")));
    }

    private void load(Shop s) {
        app.api.get(s.path, new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                s.items.clear();
                JSONArray arr = r.optJSONArray(s.listKey);
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString("id");
                    if (s == dice) {
                        DiceView.Skin k = DiceView.Skin.from(o);
                        if (k != null) s.items.add(new Item(k.id, k.name, o.optInt("price"), k, null, k.price >= 80));
                    } else if (s == frames ? AvatarView.Frames.known(id) : BoardThemes.known(id)) {
                        s.items.add(new Item(id, o.optString("name"), o.optInt("price"), null, o.optString("blurb", null), o.optBoolean("royal")));
                    }
                }
                if (s == dice && s.items.isEmpty()) {
                    for (DiceView.Skin k : DiceView.Skin.all()) s.items.add(new Item(k.id, k.name, k.price, k, null, k.price >= 80));
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
        } else if (s == boards) {
            s.owned.add(BoardThemes.CLASSIC.id);
            if (s.equipped == null) s.equipped = BoardThemes.CLASSIC.id;
            app.prefs.edit().putString(PREF_BOARD, s.equipped).apply();
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

        // Dice | Frames | Boards.
        LinearLayout tabs = Ui.hbox(this);
        Ui.margins(tabs, this, 0, 12, 0, 0);
        Shop[] all = {dice, frames, boards};
        String[] labels = {"🎲 Dice", "🔥 Frames", "👑 Boards"};
        for (int i = 0; i < all.length; i++) {
            Shop s = all[i];
            TextView b = Ui.smallButton(this, labels[i], s == tab ? "primary" : "glass", v -> {
                if (tab == s) return;
                tab = s;
                render();
            });
            b.setPadding(Ui.dp(this, 4), b.getPaddingTop() + Ui.dp(this, 3), Ui.dp(this, 4), b.getPaddingBottom() + Ui.dp(this, 3));
            b.setSingleLine(true);
            LinearLayout.LayoutParams lp = Ui.weight(1);
            if (i < all.length - 1) lp.rightMargin = Ui.dp(this, 6);
            tabs.addView(b, lp);
        }
        col.addView(tabs, Ui.lpMatchWrap());
        TextView intro = Ui.muted(this, tab == dice ? "Your die in every match — the whole table sees it roll."
                : tab == frames ? "A ring round your picture: on your profile, with friends and at every table."
                : "Royal boards. When you host a room, everyone in it plays on your board.");
        intro.setGravity(Gravity.CENTER);
        intro.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8), 0);
        col.addView(intro, Ui.lpMatchWrap());

        if (!tab.loaded || tab.items.isEmpty()) {
            col.addView(tab.loaded ? Ui.muted(this, "Nothing for sale here yet.") : Ui.spinner(this));
            return;
        }
        Item pick = tab.byId(tab.selected);
        col.addView(hero(pick), Ui.lpMatchWrap());

        // Everything in this tab.
        col.addView(Ui.heading(this, tab == dice ? "All dice" : tab == frames ? "All frames" : "All boards"));
        int n = tab.columns;
        LinearLayout row = null;
        List<Item> items = tab.items;
        for (int i = 0; i < items.size(); i++) {
            if (i % n == 0) {
                row = Ui.hbox(this);
                col.addView(row, Ui.lpMatchWrap());
            }
            row.addView(tile(items.get(i)), tileLp(i % n, n));
        }
        // Keep the last row's tiles the same width as the others.
        for (int k = items.size() % n; k != 0 && k < n; k++) row.addView(new View(this), tileLp(k, n));
        TextView note = Ui.muted(this, "Diamonds come from the diamond shop. Everything here is bought once and kept forever.");
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, Ui.dp(this, 14), 0, 0);
        col.addView(note);
    }

    /** The picked item, big, with what it is and what you can do with it. */
    private View hero(Item pick) {
        LinearLayout hero = Ui.card(this);
        Ui.margins(hero, this, 0, 12, 0, 0);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        if (pick.royal) {
            TextView ribbon = Ui.tag(this, tab == boards ? "👑 ROYAL BOARD" : "✨ PREMIUM", Ui.GOLD);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rl.bottomMargin = Ui.dp(this, 8);
            hero.addView(ribbon, rl);
        }
        int size = tab == boards ? 230 : 170;
        View big = preview(pick, size);
        hero.addView(big, new LinearLayout.LayoutParams(Ui.dp(this, size), Ui.dp(this, size)));
        // A small pop each time a new item is picked.
        big.setScaleX(0.9f);
        big.setScaleY(0.9f);
        big.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new OvershootInterpolator(2f)).start();

        TextView name = new Fx.GoldText(this, pick.name.toUpperCase(java.util.Locale.ROOT), 24);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, Ui.dp(this, 8), 0, 0);
        hero.addView(name, Ui.lpMatchWrap());
        if (pick.blurb != null && !pick.blurb.isEmpty()) {
            TextView blurb = Ui.text(this, pick.blurb, 14, Ui.INK);
            blurb.setGravity(Gravity.CENTER);
            hero.addView(blurb, Ui.lpMatchWrap());
        }
        boolean have = tab.owned.contains(pick.id);
        boolean using = pick.id.equals(tab.equipped);
        String inUse = tab == dice ? "✓ On your die now — everyone at the table sees it."
                : tab == frames ? "✓ Round your picture now — friends and every table see it."
                : "✓ Your board now — everyone in rooms you host plays on it.";
        TextView sub = Ui.muted(this, using ? inUse : have ? "You own this " + tab.word + ". Put it on any time."
                : pick.price == 0 ? "Free — take it!" : "Buy once, keep forever.");
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 12));
        hero.addView(sub, Ui.lpMatchWrap());
        TextView action;
        if (using) {
            if (tab == frames) {
                action = Ui.button(this, "Take frame off", "glass", v -> equip(tab, "none"));
            } else {
                action = Ui.button(this, "✓ In use", "ok", null);
                Ui.setEnabled(action, false);
            }
        } else if (have) {
            action = Ui.button(this, "Use this " + tab.word, "ok", v -> equip(tab, pick.id));
        } else {
            boolean afford = app.wallet.optLong("gems") >= pick.price;
            action = Ui.button(this, pick.price == 0 ? "Get it free" : "Buy for 💎 " + pick.price, afford ? "primary" : "glass", v -> confirmBuy(tab, pick));
            if (!afford && pick.price > 0) {
                TextView need = Ui.text(this, "You need 💎 " + (pick.price - app.wallet.optLong("gems")) + " more", 12, Ui.GOLD);
                need.setGravity(Gravity.CENTER);
                need.setPadding(0, 0, 0, Ui.dp(this, 6));
                hero.addView(need, Ui.lpMatchWrap());
            }
        }
        hero.addView(action, Ui.lpMatchWrap());
        return hero;
    }

    /** The item drawn big or small: a spinning die, your own picture in the frame, or the board. */
    private View preview(Item k, int sizeDp) {
        if (k.skin != null) return sizeDp >= 100 ? new Spinning(this, k.skin) : new Face(this, k.skin);
        if (tab == boards) return new BoardPreview(this, k.id, sizeDp >= 150);
        JSONObject me = app.user;
        String name = me != null ? me.optString("username", "?") : "?";
        AvatarView v = Ui.avatar(this, me, name, sizeDp);
        return v.frame(k.id);
    }

    private LinearLayout.LayoutParams tileLp(int column, int columns) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.topMargin = Ui.dp(this, 10);
        lp.leftMargin = column == 0 ? 0 : Ui.dp(this, 5);
        lp.rightMargin = column == columns - 1 ? 0 : Ui.dp(this, 5);
        return lp;
    }

    private View tile(Item k) {
        boolean picked = k.id.equals(tab.selected);
        boolean using = k.id.equals(tab.equipped);
        Fx.Tile t = new Fx.Tile(this, picked ? Fx.YELLOW : using ? Fx.GREEN : Fx.PURPLE, 16, 5);
        LinearLayout box = Ui.vbox(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 12));
        int size = tab == boards ? 120 : k.skin != null ? 56 : 66;
        box.addView(preview(k, size), new LinearLayout.LayoutParams(Ui.dp(this, size), Ui.dp(this, size)));
        int ink = picked ? 0xff4a2600 : Ui.INK;
        TextView n = Ui.bold(Ui.text(this, k.skin != null && k.skin.flag != null ? Flags.emoji(k.skin.flag) + " " + k.name : k.name, 13, ink));
        n.setGravity(Gravity.CENTER);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        n.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(n, Ui.lpMatchWrap());
        String tag = using ? "✓ In use" : tab.owned.contains(k.id) ? "Owned" : k.price == 0 ? "Free" : "💎 " + Ui.fmt(k.price);
        int tagColor = picked ? 0xff7a4a00 : using ? 0xffd9ffcf : tab.owned.contains(k.id) ? 0xffd9cfff : 0xffb9f2ff;
        TextView p = Ui.bold(Ui.text(this, tag, 12, tagColor));
        p.setGravity(Gravity.CENTER);
        box.addView(p, Ui.lpMatchWrap());
        t.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (k.royal && tab == boards) {
            // A little crown in the corner of royal boards.
            TextView crown = Ui.text(this, "👑", 16, Ui.INK);
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            cl.topMargin = Ui.dp(this, 4);
            cl.rightMargin = Ui.dp(this, 6);
            t.addView(crown, cl);
        }
        t.setContentDescription(k.name + ", " + tag);
        t.setOnClickListener(v -> {
            if (k.id.equals(tab.selected)) return;
            Sfx.get().play(Sfx.S.CLICK, 0.7f, 1f);
            tab.selected = k.id;
            render();
            scrollToTop();
        });
        return t;
    }

    /** After picking a tile, bring the big preview into view. */
    private void scrollToTop() {
        ViewParent v = col.getParent();
        while (v != null && !(v instanceof ScrollView)) v = v.getParent();
        if (v != null) ((ScrollView) v).smoothScrollTo(0, 0);
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
        String where = s == dice ? "It goes on your die right away."
                : s == frames ? "It goes round your picture right away."
                : "It's your board right away — everyone in rooms you host plays on it.";
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
                LudoApp.toast((k.royal ? "👑 " : "") + k.name + " " + s.word + " is yours!");
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
    private void equip(Shop s, String id) {
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
