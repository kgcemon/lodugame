package com.ludo3d.game.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
 * and then shown in every match (opponents see them too; a room plays on its host's paid board,
 * else on the paid board of whoever sat down first
 * board). The big preview at the top shows what you picked; tap any tile below to look at
 * it there. The server sets the prices (an administrator can change them) and keeps what
 * you own.
 */
public class ShopActivity extends BaseActivity {
    /** The board I play on offline (online see RoomActivity: the host's paid board, else the first one seated). */
    public static final String PREF_BOARD = "board";

    /** One thing for sale: a dice skin, a profile frame or a board. */
    private static final class Item {
        final String id, name, blurb;
        /** Shop ribbon instead of the plain royal one (null: none). */
        String badge;
        /** The server's product (dice: rarity, collection, labels, stock...). */
        JSONObject data = new JSONObject();
        /** Boards: the look, straight from the server's product. */
        BoardThemes.Theme theme;
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
    private TextView balance, boardGems;
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
        if (balance != null) balance.setText(Ui.fmt(app.wallet.optLong(tab == boards ? "balance" : "gems")));
        if (boardGems != null) boardGems.setText("💎 " + Ui.fmt(app.wallet.optLong("gems")));
    }

    /** A board paid in diamonds (the rest are paid in coins). */
    private static boolean paidInGems(Item k) {
        return "gems".equals(k.data.optString("currency"));
    }

    /** A board's price with its currency: "🪙 3,500" or "💎 650". */
    private static String boardPrice(Item k) {
        return (paidInGems(k) ? "💎 " : "🪙 ") + Ui.fmt(k.price);
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
                        if (k != null) {
                            Item it = new Item(k.id, k.name, o.optInt("price"), k, k.blurb, DiceLux.rank(k.rarity) >= 3);
                            it.data = o;
                            s.items.add(it);
                        }
                    } else if (s == boards) {
                        BoardThemes.Theme th = id.equals(BoardThemes.CLASSIC.id) ? BoardThemes.CLASSIC : BoardThemes.Theme.from(o);
                        if (th == null) continue;
                        Item it = new Item(id, o.optString("name"), o.optInt("price"), null, o.optString("blurb", null), o.optBoolean("royal"));
                        it.badge = o.isNull("badge") ? null : o.optString("badge", null);
                        it.data = o;
                        it.theme = th;
                        s.items.add(it);
                    } else if (AvatarView.Frames.known(id)) {
                        Item it = new Item(id, o.optString("name"), o.optInt("price"), null, o.optString("blurb", null), o.optBoolean("royal"));
                        it.badge = o.isNull("badge") ? null : o.optString("badge", null);
                        s.items.add(it);
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
        boardGems = null;
        if (tab == boards) {
            // Boards are paid in coins, the rarest in diamonds: show both purses.
            bar.addView(Ui.text(this, "🪙", 24, Ui.INK));
            balance = new Fx.GoldText(this, Ui.fmt(app.wallet.optLong("balance")), 22);
            balance.setPadding(Ui.dp(this, 6), 0, 0, 0);
            bar.addView(balance, Ui.weight(1));
            boardGems = Ui.bold(Ui.text(this, "💎 " + Ui.fmt(app.wallet.optLong("gems")), 15, 0xffb9f2ff));
            boardGems.setPadding(Ui.dp(this, 12), Ui.dp(this, 5), Ui.dp(this, 12), Ui.dp(this, 5));
            boardGems.setBackground(Ui.roundStroke(0x660b2a4f, Ui.dp(this, 999), Ui.dp(this, 1), 0x884fd8ff));
            boardGems.setOnClickListener(v -> startActivity(new Intent(this, GemShopActivity.class)));
            boardGems.setContentDescription("Diamonds — tap to get more");
            bar.addView(boardGems);
        } else {
            bar.addView(Ui.text(this, "💎", 26, Ui.INK));
            balance = new Fx.GoldText(this, Ui.fmt(app.wallet.optLong("gems")), 24, new int[]{0xffffffff, 0xffb9f2ff, 0xff4fd8ff}, 0xff0b4f8a);
            balance.setPadding(Ui.dp(this, 8), 0, 0, 0);
            bar.addView(balance, Ui.weight(1));
            bar.addView(Ui.smallButton(this, "+ Get diamonds", "blue", v -> startActivity(new Intent(this, GemShopActivity.class))));
        }
        col.addView(bar, Ui.lpMatchWrap());

        // Everything I own, organised by collection.
        TextView mine = Ui.smallButton(this, "📚  My Collection — everything you own", "primary", v -> startActivity(new Intent(this, CollectionActivity.class)));
        LinearLayout.LayoutParams mlp = Ui.lpMatchWrap();
        mlp.topMargin = Ui.dp(this, 10);
        col.addView(mine, mlp);

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
        TextView intro = Ui.muted(this, tab == dice ? "The LudoX Dice Collection — every opponent sees your die. Looks only: every die rolls the same."
                : tab == frames ? "A ring round your picture: on your profile, with friends and at every table."
                : "Premium 3D boards. Host a room — or be the first to join with a paid board — and everyone plays on yours, free for them.");
        intro.setGravity(Gravity.CENTER);
        intro.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8), 0);
        col.addView(intro, Ui.lpMatchWrap());

        if (!tab.loaded || tab.items.isEmpty()) {
            col.addView(tab.loaded ? Ui.muted(this, "Nothing for sale here yet.") : Ui.spinner(this));
            return;
        }
        if (tab == dice) {
            renderDice();
            return;
        }
        if (tab == boards) {
            renderBoards();
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
        if (pick.royal || pick.badge != null) {
            TextView ribbon = Ui.tag(this, pick.badge != null ? pick.badge : tab == boards ? "👑 ROYAL BOARD" : "✨ PREMIUM", Ui.GOLD);
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
                : "✓ Your board now — everyone at your table plays on it.";
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
            // A little crown (or the badge's own symbol) in the corner of royal boards.
            String mark = k.badge != null ? k.badge.substring(0, k.badge.offsetByCodePoints(0, k.badge.startsWith("🇧") ? 2 : 1)) : "👑";
            TextView crown = Ui.text(this, mark, 16, Ui.INK);
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
                : "It's your board right away — everyone at your table plays on it.";
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

    // ================================================================= the dice collection marketplace

    /** Dice filters: free text, collection and rarity ("all" = any). */
    private String diceQuery = "", diceCollection = "all", diceRarity = "all";
    private LinearLayout diceGrid;

    private static List<String> labelsOf(Item k) {
        List<String> out = new ArrayList<>();
        JSONArray a = k.data.optJSONArray("labels");
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    /** Copies of a limited die still for sale, or -1 when it isn't limited. */
    private static int leftOf(Item k) {
        return k.data.isNull("left") || !k.data.has("left") ? -1 : k.data.optInt("left");
    }

    private void renderDice() {
        // My collection: progress and a way in.
        int total = dice.items.size(), have = 0;
        for (Item k : dice.items) if (dice.owned.contains(k.id)) have++;
        LinearLayout mine = Ui.hbox(this);
        mine.setGravity(Gravity.CENTER_VERTICAL);
        mine.setBackground(DiceLux.card(this, "legendary", true));
        mine.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        ImageView eq = new ImageView(this);
        Item on = dice.byId(dice.equipped);
        if (on != null) DiceLux.thumb(eq, on.skin, Ui.dp(this, 56));
        mine.addView(eq, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));
        LinearLayout mt = Ui.vbox(this);
        mt.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 8), 0);
        mt.addView(Ui.bold(Ui.text(this, "My Dice Collection", 15, Ui.INK)));
        mt.addView(Ui.text(this, have + " of " + total + " collected" + (on != null ? " · using " + on.name : ""), 12, 0xffd9cfff));
        mt.addView(progress(have, total), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6)));
        mine.addView(mt, Ui.weight(1));
        mine.addView(Ui.smallButton(this, "Open", "primary", v -> startActivity(new Intent(this, DiceCollectionActivity.class))));
        LinearLayout.LayoutParams ml = Ui.lpMatchWrap();
        ml.topMargin = Ui.dp(this, 12);
        col.addView(mine, ml);

        // Featured.
        List<Item> featured = new ArrayList<>(), fresh = new ArrayList<>(), best = new ArrayList<>(), exclusive = new ArrayList<>();
        for (Item k : dice.items) {
            List<String> ls = labelsOf(k);
            if (k.data.optBoolean("featured")) featured.add(k);
            if (ls.contains("NEW ARRIVAL")) fresh.add(k);
            if (ls.contains("BEST SELLER") || ls.contains("MOST POPULAR")) best.add(k);
            if ("exclusive".equals(k.skin.collection)) exclusive.add(k);
        }
        // The rarest first: the showpieces lead.
        java.util.Collections.sort(featured, (x, y) -> DiceLux.rank(y.skin.rarity) - DiceLux.rank(x.skin.rarity));
        if (!featured.isEmpty()) {
            col.addView(Ui.heading(this, "✨ Featured"));
            col.addView(row(featured, true));
        }
        if (!fresh.isEmpty()) {
            col.addView(Ui.heading(this, "🆕 New Arrivals"));
            col.addView(row(fresh, false));
        }
        // Only shown when real sales put dice there.
        if (!best.isEmpty()) {
            col.addView(Ui.heading(this, "🔥 Best Sellers"));
            col.addView(row(best, false));
        }
        if (!exclusive.isEmpty()) {
            col.addView(Ui.heading(this, "💜 Exclusive Collection"));
            col.addView(row(exclusive, false));
        }

        // Browse: search and filters, then every die.
        col.addView(Ui.heading(this, "🎲 All Dice"));
        android.widget.EditText search = Ui.input(this, "🔍  Search dice", android.text.InputType.TYPE_CLASS_TEXT);
        search.setText(diceQuery);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(android.text.Editable e) {
                diceQuery = e.toString().trim().toLowerCase(java.util.Locale.ROOT);
                fillDiceGrid();
            }
        });
        col.addView(search);
        String[] cols = new String[DiceLux.COLLECTIONS.length + 1];
        String[] colNames = new String[cols.length];
        cols[0] = "all";
        colNames[0] = "All";
        for (int i = 0; i < DiceLux.COLLECTIONS.length; i++) {
            cols[i + 1] = DiceLux.COLLECTIONS[i];
            colNames[i + 1] = DiceLux.collectionShort(DiceLux.COLLECTIONS[i]);
        }
        col.addView(chips(cols, colNames, true));
        String[] rars = new String[DiceLux.RARITIES.length + 1];
        String[] rarNames = new String[rars.length];
        rars[0] = "all";
        rarNames[0] = "Any rarity";
        for (int i = 0; i < DiceLux.RARITIES.length; i++) {
            rars[i + 1] = DiceLux.RARITIES[i];
            rarNames[i + 1] = DiceLux.rarityName(DiceLux.RARITIES[i]);
        }
        col.addView(chips(rars, rarNames, false));
        diceGrid = Ui.vbox(this);
        col.addView(diceGrid, Ui.lpMatchWrap());
        fillDiceGrid();
        TextView note = Ui.muted(this, "Dice are bought once with diamonds and kept forever. A die only changes how your rolls look — the server rolls the same for everyone.");
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, Ui.dp(this, 14), 0, 0);
        col.addView(note);
    }

    private View progress(int have, int total) {
        FrameLayout bar = new FrameLayout(this);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(Ui.dp(this, 999));
        track.setColor(0x33ffffff);
        bar.setBackground(track);
        View fill = new View(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xffffe27a, 0xffffb547});
        g.setCornerRadius(Ui.dp(this, 999));
        fill.setBackground(g);
        bar.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        bar.post(() -> {
            fill.getLayoutParams().width = Math.max(Ui.dp(this, 6), (int) (bar.getWidth() * (total == 0 ? 0 : have / (float) total)));
            fill.requestLayout();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
        lp.topMargin = Ui.dp(this, 6);
        bar.setLayoutParams(lp);
        return bar;
    }

    /** A sideways row of cards (big ones for Featured). */
    private View row(List<Item> items, boolean big) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        LinearLayout r = Ui.hbox(this);
        for (Item k : items) {
            View card = big ? featuredCard(k) : diceCard(k);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, big ? 300 : 158), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 10);
            r.addView(card, lp);
        }
        hs.addView(r);
        return hs;
    }

    private View chips(String[] values, String[] names, boolean collection) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = Ui.hbox(this);
        List<TextView> all = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            String v = values[i];
            boolean on = v.equals(collection ? diceCollection : diceRarity);
            TextView chip = Ui.bold(Ui.text(this, names[i], 12, on ? 0xff2a1400 : Ui.INK));
            chip.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 7));
            styleChip(chip, on, collection ? 0 : (i == 0 ? 0 : DiceLux.color(v)));
            chip.setOnClickListener(x -> {
                if (collection) diceCollection = v;
                else diceRarity = v;
                for (int j = 0; j < all.size(); j++) {
                    boolean sel = values[j].equals(v);
                    all.get(j).setTextColor(sel ? 0xff2a1400 : Ui.INK);
                    styleChip(all.get(j), sel, collection || j == 0 ? 0 : DiceLux.color(values[j]));
                }
                fillDiceGrid();
            });
            all.add(chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 6));
            r.addView(chip, lp);
        }
        hs.addView(r);
        return hs;
    }

    private void styleChip(TextView chip, boolean on, int dot) {
        GradientDrawable g = on
                ? new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xffffe27a, 0xfff5b400})
                : new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xff3a1d6e, 0xff26104f});
        g.setCornerRadius(Ui.dp(this, 999));
        g.setStroke(Ui.dp(this, 1), on ? 0xffa66f00 : (dot != 0 ? dot : 0x55ffffff));
        chip.setBackground(g);
    }

    /** Every die matching the search and filters, two to a row. */
    private void fillDiceGrid() {
        if (diceGrid == null) return;
        diceGrid.removeAllViews();
        List<Item> match = new ArrayList<>();
        for (Item k : dice.items) {
            if (!"all".equals(diceCollection) && !diceCollection.equals(k.skin.collection)) continue;
            if (!"all".equals(diceRarity) && !diceRarity.equals(k.skin.rarity)) continue;
            if (!diceQuery.isEmpty()) {
                String hay = (k.name + " " + k.skin.collection + " " + k.skin.rarity + " " + (k.blurb == null ? "" : k.blurb)).toLowerCase(java.util.Locale.ROOT);
                if (!hay.contains(diceQuery)) continue;
            }
            match.add(k);
        }
        if (match.isEmpty()) {
            TextView none = Ui.muted(this, "No dice match. Try another search or filter.");
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
            diceGrid.addView(none, Ui.lpMatchWrap());
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < match.size(); i++) {
            if (i % 2 == 0) {
                row = Ui.hbox(this);
                diceGrid.addView(row, Ui.lpMatchWrap());
            }
            row.addView(diceCard(match.get(i)), tileLp(i % 2, 2));
        }
        if (match.size() % 2 == 1) row.addView(new View(this), tileLp(1, 2));
    }

    /** One die for sale: its 3D render on a lit platform, rarity, collection, price and actions. */
    private View diceCard(Item k) {
        boolean using = k.id.equals(dice.equipped), have = dice.owned.contains(k.id);
        int left = leftOf(k);
        LinearLayout card = Ui.vbox(this);
        card.setBackground(DiceLux.card(this, k.skin.rarity, using || DiceLux.rank(k.skin.rarity) >= 3));
        card.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        LinearLayout top = Ui.hbox(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(DiceLux.badge(this, k.skin.rarity));
        top.addView(new View(this), Ui.weight(1));
        if (using) top.addView(DiceLux.label(this, "✓ EQUIPPED"));
        else if (have) top.addView(Ui.bold(Ui.text(this, "OWNED", 10, 0xffb9f2ff)));
        card.addView(top, Ui.lpMatchWrap());

        FrameLayout pedestal = new FrameLayout(this);
        pedestal.addView(new DiceLux.Platform(this, k.skin.rarity), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView iv = new ImageView(this);
        DiceLux.thumb(iv, k.skin, Ui.dp(this, 110));
        pedestal.addView(iv, new FrameLayout.LayoutParams(Ui.dp(this, 110), Ui.dp(this, 110), Gravity.CENTER));
        card.addView(pedestal, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 118)));

        List<String> ls = labelsOf(k);
        if (!ls.isEmpty()) {
            TextView first = DiceLux.label(this, ls.get(0));
            LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            fl.gravity = Gravity.CENTER_HORIZONTAL;
            card.addView(first, fl);
        }
        TextView n = Ui.bold(Ui.text(this, k.skin.flag != null ? Flags.emoji(k.skin.flag) + " " + k.name : k.name, 15, Ui.INK));
        n.setGravity(Gravity.CENTER);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        n.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(n, Ui.lpMatchWrap());
        TextView c = Ui.text(this, DiceLux.collectionShort(k.skin.collection), 11, 0xffb8acdf);
        c.setGravity(Gravity.CENTER);
        card.addView(c, Ui.lpMatchWrap());
        String priceText = using ? "In use" : have ? "Owned" : k.price == 0 ? "Free" : "💎 " + Ui.fmt(k.price);
        TextView price = Ui.bold(Ui.text(this, priceText, 15, have ? 0xffb9f2ff : 0xff7fe3ff));
        price.setGravity(Gravity.CENTER);
        price.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(price, Ui.lpMatchWrap());
        if (left >= 0 && !have) {
            TextView l = Ui.bold(Ui.text(this, left == 0 ? "Sold out" : "Only " + left + " left", 11, 0xffff8a80));
            l.setGravity(Gravity.CENTER);
            card.addView(l, Ui.lpMatchWrap());
        }

        LinearLayout acts = Ui.hbox(this);
        TextView pv = Ui.smallButton(this, "Preview", "glass", v -> previewDice(k));
        pv.setPadding(Ui.dp(this, 4), pv.getPaddingTop(), Ui.dp(this, 4), pv.getPaddingBottom());
        TextView act;
        if (using) {
            act = Ui.smallButton(this, "✓ On", "ok", null);
            Ui.setEnabled(act, false);
        } else if (have) {
            act = Ui.smallButton(this, "Equip", "ok", v -> equip(dice, k.id));
        } else if (left == 0) {
            act = Ui.smallButton(this, "Sold out", "glass", null);
            Ui.setEnabled(act, false);
        } else {
            act = Ui.smallButton(this, k.price == 0 ? "Get" : "Buy", "primary", v -> confirmDiceBuy(k));
        }
        act.setPadding(Ui.dp(this, 4), act.getPaddingTop(), Ui.dp(this, 4), act.getPaddingBottom());
        LinearLayout.LayoutParams pl = Ui.weight(1);
        pl.rightMargin = Ui.dp(this, 4);
        LinearLayout.LayoutParams al = Ui.weight(1);
        al.leftMargin = Ui.dp(this, 4);
        acts.addView(pv, pl);
        acts.addView(act, al);
        LinearLayout.LayoutParams actsLp = Ui.lpMatchWrap();
        actsLp.topMargin = Ui.dp(this, 8);
        card.addView(acts, actsLp);
        card.setOnClickListener(v -> previewDice(k));
        card.setContentDescription(k.name + ", " + DiceLux.rarityName(k.skin.rarity) + ", " + priceText);
        return card;
    }

    /** A wide card for the Featured row. */
    private View featuredCard(Item k) {
        boolean using = k.id.equals(dice.equipped), have = dice.owned.contains(k.id);
        LinearLayout card = Ui.hbox(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(DiceLux.card(this, k.skin.rarity, true));
        card.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        FrameLayout pedestal = new FrameLayout(this);
        pedestal.addView(new DiceLux.Platform(this, k.skin.rarity), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView iv = new ImageView(this);
        DiceLux.thumb(iv, k.skin, Ui.dp(this, 130));
        pedestal.addView(iv, new FrameLayout.LayoutParams(Ui.dp(this, 130), Ui.dp(this, 130), Gravity.CENTER));
        card.addView(pedestal, new LinearLayout.LayoutParams(Ui.dp(this, 132), Ui.dp(this, 140)));
        LinearLayout t = Ui.vbox(this);
        t.setPadding(Ui.dp(this, 6), 0, 0, 0);
        t.addView(DiceLux.badge(this, k.skin.rarity), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView name = new Fx.GoldText(this, k.name, 20);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(name, Ui.lpMatchWrap());
        t.addView(Ui.text(this, DiceLux.collectionName(k.skin.collection), 11, 0xffb8acdf));
        TextView price = Ui.bold(Ui.text(this, using ? "✓ Equipped" : have ? "Owned" : "💎 " + Ui.fmt(k.price), 15, 0xff7fe3ff));
        price.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
        t.addView(price);
        t.addView(Ui.smallButton(this, "Preview", "primary", v -> previewDice(k)), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(t, Ui.weight(1));
        card.setOnClickListener(v -> previewDice(k));
        return card;
    }

    /** Open the full-screen 3D preview, with the right action for this die. */
    private void previewDice(Item k) {
        boolean using = k.id.equals(dice.equipped), have = dice.owned.contains(k.id);
        int left = leftOf(k);
        DicePreview.Offer o = new DicePreview.Offer();
        if (using) {
            o.status = "✓ Equipped";
            o.action = "In use";
            o.actionStyle = "ok";
        } else if (have) {
            o.status = "Owned";
            o.action = "Equip this dice";
            o.actionStyle = "ok";
            o.onAction = () -> equip(dice, k.id);
        } else if (left == 0) {
            o.status = "💎 " + Ui.fmt(k.price);
            o.note = "Sold out — every copy has been bought.";
            o.action = "Sold out";
            o.actionStyle = "glass";
        } else {
            o.status = k.price == 0 ? "Free" : "💎 " + Ui.fmt(k.price) + " diamonds";
            if (left > 0) o.note = "Limited edition: only " + left + " left.";
            o.action = k.price == 0 ? "Get it free" : "Buy for 💎 " + Ui.fmt(k.price);
            o.onAction = () -> confirmDiceBuy(k);
        }
        DicePreview.show(this, k.skin, labelsOf(k), o);
    }

    /** A clear confirmation: what it is, the exact price, what pays for it, the balance after. */
    private void confirmDiceBuy(Item k) {
        if (busy) return;
        long gems = app.wallet.optLong("gems");
        if (gems < k.price) {
            notEnough(dice, k);
            return;
        }
        LinearLayout box = Ui.vbox(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView iv = new ImageView(this);
        DiceLux.thumb(iv, k.skin, Ui.dp(this, 120));
        box.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 120)));
        LinearLayout tags = Ui.hbox(this);
        tags.setGravity(Gravity.CENTER);
        tags.addView(DiceLux.badge(this, k.skin.rarity));
        tags.addView(Ui.text(this, "  " + DiceLux.collectionName(k.skin.collection), 12, 0xffd9cfff));
        box.addView(tags, Ui.lpMatchWrap());
        box.addView(receiptLine("Price", "💎 " + Ui.fmt(k.price) + " diamonds"));
        box.addView(receiptLine("Paid with", "Diamonds in your wallet"));
        box.addView(receiptLine("Your diamonds", Ui.fmt(gems) + "  →  " + Ui.fmt(gems - k.price)));
        int left = leftOf(k);
        if (left > 0) box.addView(receiptLine("Limited edition", left + " left"));
        TextView fine = Ui.text(this, "Bought once, yours forever. No real money is charged here. Looks only — it never changes a roll.", 11, Ui.MUTED);
        fine.setGravity(Gravity.CENTER);
        fine.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(fine, Ui.lpMatchWrap());
        Popup.showModal(this, "Buy " + k.name + "?", box, Popup.plain("Cancel", null), Popup.ok("Pay 💎 " + Ui.fmt(k.price), () -> buyDice(k)));
    }

    private View receiptLine(String what, String value) {
        LinearLayout r = Ui.hbox(this);
        r.setPadding(0, Ui.dp(this, 6), 0, 0);
        r.addView(Ui.text(this, what, 14, Ui.MUTED), Ui.weight(1));
        r.addView(Ui.bold(Ui.text(this, value, 14, Ui.INK)));
        LinearLayout.LayoutParams lp = Ui.lpMatchWrap();
        r.setLayoutParams(lp);
        return r;
    }

    private void buyDice(Item k) {
        if (busy) return;
        busy = true;
        app.api.post(dice.path + "/" + k.id + "/buy", new JSONObject(), new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                busy = false;
                apply(dice, r);
                dice.selected = k.id;
                Sfx.get().play(Sfx.S.MATCH_FOUND);
                render();
                load(dice);
                // Confirmation: it's in the collection and on your die.
                LinearLayout box = Ui.vbox(ShopActivity.this);
                box.setGravity(Gravity.CENTER_HORIZONTAL);
                ImageView iv = new ImageView(ShopActivity.this);
                DiceLux.thumb(iv, k.skin, Ui.dp(ShopActivity.this, 140));
                box.addView(iv, new LinearLayout.LayoutParams(Ui.dp(ShopActivity.this, 140), Ui.dp(ShopActivity.this, 140)));
                TextView t = Ui.text(ShopActivity.this, k.name + " is in your Dice Collection and on your die now. Everyone at the table will see it.", 14, Ui.INK);
                t.setGravity(Gravity.CENTER);
                box.addView(t, Ui.lpMatchWrap());
                Popup.showModal(ShopActivity.this, "🎉 It's yours!", box,
                        Popup.plain("My collection", () -> startActivity(new Intent(ShopActivity.this, DiceCollectionActivity.class))),
                        Popup.ok("Great!", null));
            }

            @Override
            public void err(Api.Error e) {
                busy = false;
                if ("NOT_ENOUGH_GEMS".equals(e.code) || e.status == 402) notEnough(dice, k);
                else {
                    super.err(e);
                    load(dice);
                }
            }
        });
    }

    // ================================================================= the board marketplace

    /** Board filters: free text, collection and rarity ("all" = any). */
    private String boardQuery = "", boardCollection = "all", boardRarity = "all";
    private LinearLayout boardGrid;

    private void renderBoards() {
        // My boards: the one in use, and what it means.
        int have = 0;
        for (Item k : boards.items) if (boards.owned.contains(k.id)) have++;
        Item on = boards.byId(boards.equipped);
        LinearLayout mine = Ui.hbox(this);
        mine.setGravity(Gravity.CENTER_VERTICAL);
        mine.setBackground(DiceLux.card(this, "legendary", true));
        mine.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        if (on != null && on.theme != null) {
            ImageView iv = new ImageView(this);
            BoardLux.thumb(iv, on.theme, Ui.dp(this, 84));
            mine.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 66)));
        }
        LinearLayout mt = Ui.vbox(this);
        mt.setPadding(Ui.dp(this, 10), 0, 0, 0);
        mt.addView(Ui.bold(Ui.text(this, "My Boards · " + have + " of " + boards.items.size(), 15, Ui.INK)));
        mt.addView(Ui.text(this, (on != null ? "Using " + on.name + ". " : "") + "Host or join first, and everyone plays on your board — free for them.", 12, 0xffd9cfff));
        mine.addView(mt, Ui.weight(1));
        LinearLayout.LayoutParams ml = Ui.lpMatchWrap();
        ml.topMargin = Ui.dp(this, 12);
        col.addView(mine, ml);

        List<Item> featured = new ArrayList<>(), couple = new ArrayList<>(), fresh = new ArrayList<>(), best = new ArrayList<>(), diamond = new ArrayList<>();
        for (Item k : boards.items) {
            if (k.theme == null) continue;
            List<String> ls = labelsOf(k);
            if (k.data.optBoolean("featured")) featured.add(k);
            if (paidInGems(k)) diamond.add(k);
            if ("couple".equals(k.theme.collection) || "love".equals(k.theme.collection)) couple.add(k);
            if (ls.contains("NEW ARRIVAL")) fresh.add(k);
            if (ls.contains("BEST SELLER") || ls.contains("MOST POPULAR")) best.add(k);
        }
        java.util.Collections.sort(featured, (x, y) -> DiceLux.rank(y.theme.rarity) - DiceLux.rank(x.theme.rarity));
        if (!featured.isEmpty()) {
            col.addView(Ui.heading(this, "✨ Featured Boards"));
            col.addView(boardRow(featured, true));
        }
        if (!diamond.isEmpty()) {
            java.util.Collections.sort(diamond, (x, y) -> y.price - x.price);
            col.addView(Ui.heading(this, "💎 Diamond Boards"));
            TextView why = Ui.muted(this, "The rarest boards in LudoX, paid with diamonds.");
            why.setPadding(Ui.dp(this, 2), 0, 0, Ui.dp(this, 6));
            col.addView(why);
            col.addView(boardRow(diamond, false));
        }
        if (!couple.isEmpty()) {
            col.addView(Ui.heading(this, "💞 Couple & Romantic"));
            TextView why = Ui.muted(this, "Made for two people who love playing and talking together — anyone can choose them.");
            why.setPadding(Ui.dp(this, 2), 0, 0, Ui.dp(this, 6));
            col.addView(why);
            col.addView(boardRow(couple, false));
        }
        if (!best.isEmpty()) {
            col.addView(Ui.heading(this, "🔥 Best Sellers"));
            col.addView(boardRow(best, false));
        }
        if (!fresh.isEmpty()) {
            col.addView(Ui.heading(this, "🆕 New Arrivals"));
            col.addView(boardRow(fresh, false));
        }

        // Browse every board.
        col.addView(Ui.heading(this, "🎲 All Boards"));
        android.widget.EditText search = Ui.input(this, "🔍  Search boards", android.text.InputType.TYPE_CLASS_TEXT);
        search.setText(boardQuery);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(android.text.Editable e) {
                boardQuery = e.toString().trim().toLowerCase(java.util.Locale.ROOT);
                fillBoardGrid();
            }
        });
        col.addView(search);
        String[] cols = new String[BoardLux.COLLECTIONS.length + 1];
        String[] colNames = new String[cols.length];
        cols[0] = "all";
        colNames[0] = "All";
        for (int i = 0; i < BoardLux.COLLECTIONS.length; i++) {
            cols[i + 1] = BoardLux.COLLECTIONS[i];
            colNames[i + 1] = BoardLux.collectionShort(BoardLux.COLLECTIONS[i]);
        }
        col.addView(boardChips(cols, colNames, true));
        String[] rars = new String[DiceLux.RARITIES.length + 1];
        String[] rarNames = new String[rars.length];
        rars[0] = "all";
        rarNames[0] = "Any rarity";
        for (int i = 0; i < DiceLux.RARITIES.length; i++) {
            rars[i + 1] = DiceLux.RARITIES[i];
            rarNames[i + 1] = DiceLux.rarityName(DiceLux.RARITIES[i]);
        }
        col.addView(boardChips(rars, rarNames, false));
        boardGrid = Ui.vbox(this);
        col.addView(boardGrid, Ui.lpMatchWrap());
        fillBoardGrid();
        TextView note = Ui.muted(this, "Boards are bought once — with 🪙 coins, or 💎 diamonds for the rarest — and kept forever. Every board has the same paths, safe cells and rules — only the look changes.");
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, Ui.dp(this, 14), 0, 0);
        col.addView(note);
    }

    private View boardRow(List<Item> items, boolean big) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = Ui.hbox(this);
        for (Item k : items) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, big ? 290 : 170), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 10);
            r.addView(boardCard(k, big), lp);
        }
        hs.addView(r);
        return hs;
    }

    private View boardChips(String[] values, String[] names, boolean collection) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = Ui.hbox(this);
        List<TextView> all = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            String v = values[i];
            boolean on = v.equals(collection ? boardCollection : boardRarity);
            TextView chip = Ui.bold(Ui.text(this, names[i], 12, on ? 0xff2a1400 : Ui.INK));
            chip.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 7));
            styleChip(chip, on, collection || i == 0 ? 0 : DiceLux.color(v));
            chip.setOnClickListener(x -> {
                if (collection) boardCollection = v;
                else boardRarity = v;
                for (int j = 0; j < all.size(); j++) {
                    boolean sel = values[j].equals(v);
                    all.get(j).setTextColor(sel ? 0xff2a1400 : Ui.INK);
                    styleChip(all.get(j), sel, collection || j == 0 ? 0 : DiceLux.color(values[j]));
                }
                fillBoardGrid();
            });
            all.add(chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 6));
            r.addView(chip, lp);
        }
        hs.addView(r);
        return hs;
    }

    private void fillBoardGrid() {
        if (boardGrid == null) return;
        boardGrid.removeAllViews();
        List<Item> match = new ArrayList<>();
        for (Item k : boards.items) {
            if (k.theme == null) continue;
            if (!"all".equals(boardCollection) && !boardCollection.equals(k.theme.collection)) continue;
            if (!"all".equals(boardRarity) && !boardRarity.equals(k.theme.rarity)) continue;
            if (!boardQuery.isEmpty()) {
                String hay = (k.name + " " + k.theme.collection + " " + k.theme.rarity + " " + (paidInGems(k) ? "diamond gems" : "coins") + " " + (k.blurb == null ? "" : k.blurb)).toLowerCase(java.util.Locale.ROOT);
                if (!hay.contains(boardQuery)) continue;
            }
            match.add(k);
        }
        if (match.isEmpty()) {
            TextView none = Ui.muted(this, "No boards match. Try another search or collection.");
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
            boardGrid.addView(none, Ui.lpMatchWrap());
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < match.size(); i++) {
            if (i % 2 == 0) {
                row = Ui.hbox(this);
                boardGrid.addView(row, Ui.lpMatchWrap());
            }
            row.addView(boardCard(match.get(i), false), tileLp(i % 2, 2));
        }
        if (match.size() % 2 == 1) row.addView(new View(this), tileLp(1, 2));
    }

    /** One board for sale: its tilted 3D render, rarity, collection, price (coins or diamonds) and actions. */
    private View boardCard(Item k, boolean big) {
        boolean using = k.id.equals(boards.equipped), have = boards.owned.contains(k.id);
        LinearLayout card = Ui.vbox(this);
        card.setBackground(DiceLux.card(this, k.theme.rarity, using || DiceLux.rank(k.theme.rarity) >= 3));
        card.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        LinearLayout top = Ui.hbox(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(DiceLux.badge(this, k.theme.rarity));
        top.addView(new View(this), Ui.weight(1));
        if (using) top.addView(DiceLux.label(this, "✓ IN USE"));
        else if (have) top.addView(Ui.bold(Ui.text(this, "OWNED", 10, 0xffb9f2ff)));
        card.addView(top, Ui.lpMatchWrap());
        ImageView iv = new ImageView(this);
        int tw = Ui.dp(this, big ? 260 : 148);
        BoardLux.thumb(iv, k.theme, tw);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(tw, Math.round(tw * 0.78f));
        il.gravity = Gravity.CENTER_HORIZONTAL;
        il.topMargin = Ui.dp(this, 4);
        card.addView(iv, il);
        String ribbon = k.badge != null ? k.badge : labelsOf(k).isEmpty() ? null : labelsOf(k).get(0);
        if (ribbon != null) {
            LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            fl.gravity = Gravity.CENTER_HORIZONTAL;
            card.addView(DiceLux.label(this, ribbon), fl);
        }
        TextView n = big ? new Fx.GoldText(this, k.name, 20) : Ui.bold(Ui.text(this, k.name, 15, Ui.INK));
        n.setGravity(Gravity.CENTER);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        n.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(n, Ui.lpMatchWrap());
        TextView c = Ui.text(this, BoardLux.collectionShort(k.theme.collection), 11, 0xffb8acdf);
        c.setGravity(Gravity.CENTER);
        card.addView(c, Ui.lpMatchWrap());
        String priceText = using ? "In use" : have ? "Owned" : k.price == 0 ? "Free" : boardPrice(k);
        TextView price = Ui.bold(Ui.text(this, priceText, 15, have ? 0xffb9f2ff : paidInGems(k) ? 0xff7fe3ff : Ui.GOLD));
        price.setGravity(Gravity.CENTER);
        price.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(price, Ui.lpMatchWrap());
        LinearLayout acts = Ui.hbox(this);
        TextView pv = Ui.smallButton(this, "3D View", "glass", v -> previewBoard(k));
        pv.setPadding(Ui.dp(this, 4), pv.getPaddingTop(), Ui.dp(this, 4), pv.getPaddingBottom());
        TextView act;
        if (using) {
            act = Ui.smallButton(this, "✓ On", "ok", null);
            Ui.setEnabled(act, false);
        } else if (have) {
            act = Ui.smallButton(this, "Use", "ok", v -> equip(boards, k.id));
        } else {
            act = Ui.smallButton(this, k.price == 0 ? "Get" : "Buy", "primary", v -> confirmBoardBuy(k));
        }
        act.setPadding(Ui.dp(this, 4), act.getPaddingTop(), Ui.dp(this, 4), act.getPaddingBottom());
        LinearLayout.LayoutParams pl = Ui.weight(1);
        pl.rightMargin = Ui.dp(this, 4);
        LinearLayout.LayoutParams al = Ui.weight(1);
        al.leftMargin = Ui.dp(this, 4);
        acts.addView(pv, pl);
        acts.addView(act, al);
        LinearLayout.LayoutParams actsLp = Ui.lpMatchWrap();
        actsLp.topMargin = Ui.dp(this, 8);
        card.addView(acts, actsLp);
        card.setOnClickListener(v -> previewBoard(k));
        card.setContentDescription(k.name + ", " + DiceLux.rarityName(k.theme.rarity) + ", " + priceText);
        return card;
    }

    private void previewBoard(Item k) {
        boolean using = k.id.equals(boards.equipped), have = boards.owned.contains(k.id);
        DicePreview.Offer o = new DicePreview.Offer();
        if (using) {
            o.status = "✓ In use";
            o.action = "In use";
            o.actionStyle = "ok";
            o.note = "Rooms you host, or join first, are played on this board.";
        } else if (have) {
            o.status = "Owned";
            o.action = "Use this board";
            o.actionStyle = "ok";
            o.onAction = () -> equip(boards, k.id);
        } else {
            o.status = k.price == 0 ? "Free" : boardPrice(k) + (paidInGems(k) ? " diamonds" : " coins");
            o.note = "Opponents in your rooms play on it for free.";
            o.action = k.price == 0 ? "Get it" : "Buy";
            o.onAction = () -> confirmBoardBuy(k);
        }
        List<String> ls = new ArrayList<>(labelsOf(k));
        if (k.badge != null) ls.add(0, k.badge);
        BoardShowcase.show(this, k.theme, ls, o);
    }

    /** A clear confirmation: the board, the exact price, what pays (coins or diamonds), the balance after. */
    private void confirmBoardBuy(Item k) {
        if (busy) return;
        boolean gems = paidInGems(k);
        String sym = gems ? "💎" : "🪙", word = gems ? "diamonds" : "coins";
        long coins = app.wallet.optLong(gems ? "gems" : "balance");
        if (coins < k.price) {
            Sfx.get().play(Sfx.S.ERROR);
            if (gems) {
                Popup.message(this, "Not enough diamonds", "💎\nYou need " + Ui.fmt(k.price - coins) + " more diamonds for " + k.name + ".",
                        Popup.plain("Close", null), new Popup.Btn("Diamond shop", "gold", () -> startActivity(new Intent(this, GemShopActivity.class))));
            } else {
                Popup.message(this, "Not enough coins", "🪙\nYou need " + Ui.fmt(k.price - coins) + " more coins for " + k.name + ". Win matches and collect your daily bonus to earn coins.",
                        Popup.ok("OK", null));
            }
            return;
        }
        LinearLayout box = Ui.vbox(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView iv = new ImageView(this);
        BoardLux.thumb(iv, k.theme, Ui.dp(this, 180));
        box.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 180), Ui.dp(this, 140)));
        LinearLayout tags = Ui.hbox(this);
        tags.setGravity(Gravity.CENTER);
        tags.addView(DiceLux.badge(this, k.theme.rarity));
        tags.addView(Ui.text(this, "  " + BoardLux.collectionName(k.theme.collection), 12, 0xffd9cfff));
        box.addView(tags, Ui.lpMatchWrap());
        box.addView(receiptLine("Price", sym + " " + Ui.fmt(k.price) + " " + word));
        box.addView(receiptLine("Paid with", gems ? "Your diamonds" : "Coins in your wallet"));
        box.addView(receiptLine(gems ? "Your diamonds" : "Your coins", Ui.fmt(coins) + "  →  " + Ui.fmt(coins - k.price)));
        TextView fine = Ui.text(this, "Bought once, yours forever. No real money is charged. Everyone at your table plays on it for free.", 11, Ui.MUTED);
        fine.setGravity(Gravity.CENTER);
        fine.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(fine, Ui.lpMatchWrap());
        Popup.showModal(this, "Buy " + k.name + "?", box, Popup.plain("Cancel", null), Popup.ok("Pay " + sym + " " + Ui.fmt(k.price), () -> buyBoard(k)));
    }

    private void buyBoard(Item k) {
        if (busy) return;
        busy = true;
        app.api.post(boards.path + "/" + k.id + "/buy", new JSONObject(), new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                busy = false;
                apply(boards, r);
                Sfx.get().play(Sfx.S.MATCH_FOUND);
                render();
                LinearLayout box = Ui.vbox(ShopActivity.this);
                box.setGravity(Gravity.CENTER_HORIZONTAL);
                ImageView iv = new ImageView(ShopActivity.this);
                BoardLux.thumb(iv, k.theme, Ui.dp(ShopActivity.this, 200));
                box.addView(iv, new LinearLayout.LayoutParams(Ui.dp(ShopActivity.this, 200), Ui.dp(ShopActivity.this, 156)));
                TextView t = Ui.text(ShopActivity.this, k.name + " is yours and in use. Everyone at your table will play on it.", 14, Ui.INK);
                t.setGravity(Gravity.CENTER);
                box.addView(t, Ui.lpMatchWrap());
                Popup.showModal(ShopActivity.this, "🎉 It's yours!", box, Popup.ok("Great!", null));
            }

            @Override
            public void err(Api.Error e) {
                busy = false;
                super.err(e);
                load(boards);
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
