package com.ludo3d.game.ui;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
 * My Collection: everything I own in one place — boards, dice and frames — organised by
 * collection (Couple, Royal, Fantasy...). Each collection shows how much of it I have, the
 * items I own in full colour (tap to use) and the ones still to get, locked with their
 * price (tap to open the shop). The server keeps what I own and what's on.
 */
public class CollectionActivity extends BaseActivity {
    /** One thing I may own: a board, a die or a frame. */
    private static final class Thing {
        final String id, name, collection, rarity;
        final int price;
        final boolean gems;
        BoardThemes.Theme theme;
        DiceView.Skin skin;

        Thing(String id, String name, String collection, String rarity, int price, boolean gems) {
            this.id = id;
            this.name = name;
            this.collection = collection;
            this.rarity = rarity;
            this.price = price;
            this.gems = gems;
        }
    }

    /** One kind of thing: its server path and what I own of it. */
    private static final class Kind {
        final String path, listKey, label;
        final List<Thing> all = new ArrayList<>();
        final Set<String> owned = new HashSet<>();
        String equipped;
        boolean loaded;

        Kind(String path, String listKey, String label) {
            this.path = path;
            this.listKey = listKey;
            this.label = label;
        }

        int have() {
            int n = 0;
            for (Thing t : all) if (owned.contains(t.id)) n++;
            return n;
        }
    }

    private final Kind boards = new Kind("/boards", "boards", "👑 Boards");
    private final Kind dice = new Kind("/skins", "skins", "🎲 Dice");
    private final Kind frames = new Kind("/frames", "frames", "🔥 Frames");
    private Kind tab = boards;
    /** "all", "owned" or "locked". */
    private String show = "all";
    private LinearLayout col;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        col = pageWithBack("My Collection");
        if ("dice".equals(getIntent().getStringExtra("tab"))) tab = dice;
        col.addView(Ui.spinner(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the shop: something new may be in the collection.
        load(boards);
        load(dice);
        load(frames);
    }

    private void load(Kind k) {
        app.api.get(k.path, new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                k.all.clear();
                JSONArray arr = r.optJSONArray(k.listKey);
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString("id");
                    Thing t;
                    if (k == boards) {
                        BoardThemes.Theme th = id.equals(BoardThemes.CLASSIC.id) ? BoardThemes.CLASSIC : BoardThemes.Theme.from(o);
                        if (th == null) continue;
                        t = new Thing(id, o.optString("name"), o.optString("collection", "free"), o.optString("rarity", "common"),
                                o.optInt("price"), "gems".equals(o.optString("currency")));
                        t.theme = th;
                    } else if (k == dice) {
                        DiceView.Skin s = DiceView.Skin.from(o);
                        if (s == null) continue;
                        t = new Thing(s.id, s.name, s.collection, s.rarity, o.optInt("price"), true);
                        t.skin = s;
                    } else {
                        if (!AvatarView.Frames.known(id)) continue;
                        t = new Thing(id, o.optString("name"), "frames", o.optBoolean("royal") ? "legendary" : "rare", o.optInt("price"), true);
                    }
                    k.all.add(t);
                }
                k.owned.clear();
                JSONArray ow = r.optJSONArray("owned");
                for (int i = 0; ow != null && i < ow.length(); i++) k.owned.add(ow.optString(i));
                k.equipped = r.isNull("equipped") ? null : r.optString("equipped", null);
                if (k == boards) {
                    k.owned.add(BoardThemes.CLASSIC.id);
                    if (k.equipped == null) k.equipped = BoardThemes.CLASSIC.id;
                } else if (k == dice) {
                    k.owned.add(DiceView.Skin.CLASSIC.id);
                    if (k.equipped == null) k.equipped = DiceView.Skin.CLASSIC.id;
                }
                k.loaded = true;
                render();
            }
        });
    }

    private void render() {
        col.removeAllViews();

        // Overall progress across boards, dice and frames.
        int have = boards.have() + dice.have() + frames.have();
        int total = boards.all.size() + dice.all.size() + frames.all.size();
        LinearLayout hero = Ui.vbox(this);
        hero.setBackground(DiceLux.card(this, "legendary", true));
        hero.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        TextView title = new Fx.GoldText(this, "MY COLLECTION", 24);
        title.setGravity(Gravity.CENTER);
        hero.addView(title, Ui.lpMatchWrap());
        TextView sub = Ui.text(this, have + " of " + total + " items collected", 13, 0xffd9cfff);
        sub.setGravity(Gravity.CENTER);
        hero.addView(sub, Ui.lpMatchWrap());
        hero.addView(bar(have, total, Ui.GOLD), barLp(8));
        LinearLayout counts = Ui.hbox(this);
        counts.setPadding(0, Ui.dp(this, 10), 0, 0);
        for (Kind k : new Kind[]{boards, dice, frames}) {
            LinearLayout c = Ui.vbox(this);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            c.addView(Ui.bold(Ui.text(this, k.loaded ? k.have() + "/" + k.all.size() : "…", 17, Ui.INK)));
            c.addView(Ui.text(this, k.label, 11, 0xffb8acdf));
            counts.addView(c, Ui.weight(1));
        }
        hero.addView(counts, Ui.lpMatchWrap());
        col.addView(hero, Ui.lpMatchWrap());

        // Boards | Dice | Frames.
        LinearLayout tabs = Ui.hbox(this);
        Ui.margins(tabs, this, 0, 12, 0, 0);
        Kind[] all = {boards, dice, frames};
        for (int i = 0; i < all.length; i++) {
            Kind k = all[i];
            TextView b = Ui.smallButton(this, k.label, k == tab ? "primary" : "glass", v -> {
                if (tab == k) return;
                tab = k;
                render();
            });
            b.setSingleLine(true);
            LinearLayout.LayoutParams lp = Ui.weight(1);
            if (i < all.length - 1) lp.rightMargin = Ui.dp(this, 6);
            tabs.addView(b, lp);
        }
        col.addView(tabs, Ui.lpMatchWrap());

        // All | Owned | Not yet.
        LinearLayout filters = Ui.hbox(this);
        filters.setGravity(Gravity.CENTER);
        filters.setPadding(0, Ui.dp(this, 10), 0, 0);
        String[] ids = {"all", "owned", "locked"};
        String[] names = {"All", "✓ Owned", "🔒 Not yet"};
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i];
            boolean on = id.equals(show);
            TextView chip = Ui.bold(Ui.text(this, names[i], 12, on ? 0xff2a1400 : Ui.INK));
            chip.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 7));
            chip.setBackground(on ? Ui.round(Ui.GOLD, Ui.dp(this, 999)) : Ui.roundStroke(0x22ffffff, Ui.dp(this, 999), Ui.dp(this, 1), 0x44ffffff));
            chip.setOnClickListener(v -> {
                show = id;
                render();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 6);
            filters.addView(chip, lp);
        }
        col.addView(filters, Ui.lpMatchWrap());

        if (!tab.loaded) {
            col.addView(Ui.spinner(this));
            return;
        }
        String[] groups = tab == boards ? BoardLux.COLLECTIONS : tab == dice ? DiceLux.COLLECTIONS : new String[]{"frames"};
        boolean any = false;
        for (String g : groups) any |= section(g);
        // Anything in a collection this app version doesn't know yet.
        any |= section(null);
        if (!any) {
            TextView none = Ui.muted(this, "owned".equals(show) ? "Nothing here yet — visit the shop to start this collection." : "You have every one of them. Amazing!");
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 8));
            col.addView(none, Ui.lpMatchWrap());
        }
        TextView more = Ui.button(this, "🛍  Open the shop", "primary", v -> startActivity(new Intent(this, ShopActivity.class)
                .putExtra("tab", tab == boards ? "boards" : tab == frames ? "frames" : "dice")));
        LinearLayout.LayoutParams ml = Ui.lpMatchWrap();
        ml.topMargin = Ui.dp(this, 18);
        col.addView(more, ml);
    }

    /** The collection's name, how much of it I have, and its items in a row. False when empty. */
    private boolean section(String group) {
        String[] known = tab == boards ? BoardLux.COLLECTIONS : tab == dice ? DiceLux.COLLECTIONS : new String[]{"frames"};
        List<Thing> in = new ArrayList<>(), shown = new ArrayList<>();
        for (Thing t : tab.all) {
            boolean match = group != null ? group.equals(t.collection) : !contains(known, t.collection);
            if (!match) continue;
            in.add(t);
            boolean own = tab.owned.contains(t.id);
            if ("all".equals(show) || ("owned".equals(show) == own)) shown.add(t);
        }
        if (shown.isEmpty()) return false;
        // Mine first (the one in use leading), then the rest from the rarest down.
        java.util.Collections.sort(shown, (a, b) -> {
            int oa = rankOwned(a), ob = rankOwned(b);
            if (oa != ob) return ob - oa;
            return DiceLux.rank(b.rarity) - DiceLux.rank(a.rarity);
        });
        int have = 0;
        for (Thing t : in) if (tab.owned.contains(t.id)) have++;

        LinearLayout head = Ui.hbox(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(this, 2), Ui.dp(this, 18), Ui.dp(this, 2), Ui.dp(this, 2));
        String name = tab == boards ? BoardLux.collectionName(group) : tab == dice ? DiceLux.collectionName(group) : "Profile Frames";
        String icon = tab == boards ? BoardLux.collectionShort(group) : tab == dice ? DiceLux.collectionShort(group) : "🔥";
        TextView h = Fx.GoldText.label(this, icon.split(" ")[0] + " " + name, 16, 0xff3b0d8c);
        head.addView(h, Ui.weight(1));
        boolean done = have == in.size();
        head.addView(Ui.tag(this, done ? "✓ COMPLETE" : have + " / " + in.size(), done ? 0xff9affc0 : Ui.GOLD));
        col.addView(head, Ui.lpMatchWrap());
        col.addView(bar(have, in.size(), done ? Ui.SUCCESS : Ui.GOLD), barLp(4));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.hbox(this);
        row.setPadding(0, Ui.dp(this, 8), 0, 0);
        for (Thing t : shown) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, tab == boards ? 150 : 116), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 10);
            row.addView(card(t), lp);
        }
        hs.addView(row);
        col.addView(hs, Ui.lpMatchWrap());
        return true;
    }

    private int rankOwned(Thing t) {
        return t.id.equals(tab.equipped) ? 2 : tab.owned.contains(t.id) ? 1 : 0;
    }

    private static boolean contains(String[] a, String v) {
        for (String x : a) if (x.equals(v)) return true;
        return false;
    }

    /** One item: in colour with Use when mine, dimmed with a lock and its price when not. */
    private View card(Thing t) {
        boolean own = tab.owned.contains(t.id), using = t.id.equals(tab.equipped);
        LinearLayout card = Ui.vbox(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(DiceLux.card(this, t.rarity, using));
        card.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 10));
        card.addView(DiceLux.badge(this, t.rarity), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout pic = new FrameLayout(this);
        int w = Ui.dp(this, tab == boards ? 134 : 92), hgt = tab == boards ? Math.round(w * 0.78f) : w;
        View art;
        if (tab == boards) {
            ImageView iv = new ImageView(this);
            BoardLux.thumb(iv, t.theme, w);
            art = iv;
        } else if (tab == dice) {
            ImageView iv = new ImageView(this);
            DiceLux.thumb(iv, t.skin, w);
            art = iv;
        } else {
            JSONObject me = app.user;
            art = Ui.avatar(this, me, me != null ? me.optString("username", "?") : "?", 76).frame(t.id);
        }
        pic.addView(art, new FrameLayout.LayoutParams(tab == frames ? Ui.dp(this, 76) : w, tab == frames ? Ui.dp(this, 76) : hgt, Gravity.CENTER));
        if (!own) {
            art.setAlpha(0.38f);
            TextView lock = Ui.text(this, "🔒", 22, Ui.INK);
            pic.addView(lock, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(w, tab == frames ? Ui.dp(this, 84) : hgt);
        pl.topMargin = Ui.dp(this, 4);
        card.addView(pic, pl);

        TextView n = Ui.bold(Ui.text(this, t.name, 13, own ? Ui.INK : 0xffb8acdf));
        n.setGravity(Gravity.CENTER);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        n.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(n, Ui.lpMatchWrap());

        TextView act;
        if (using) {
            act = Ui.smallButton(this, "✓ In use", "ok", null);
            Ui.setEnabled(act, false);
        } else if (own) {
            act = Ui.smallButton(this, "Use", "ok", v -> equip(tab, t));
        } else {
            String price = t.price == 0 ? "Free" : (t.gems ? "💎 " : "🪙 ") + Ui.fmt(t.price);
            act = Ui.smallButton(this, price, "glass", v -> openShop());
        }
        act.setSingleLine(true);
        LinearLayout.LayoutParams al = Ui.lpMatchWrap();
        al.topMargin = Ui.dp(this, 6);
        card.addView(act, al);
        card.setOnClickListener(v -> {
            if (own && !using) equip(tab, t);
            else if (!own) openShop();
        });
        card.setContentDescription(t.name + (using ? ", in use" : own ? ", owned" : ", locked"));
        return card;
    }

    private void openShop() {
        startActivity(new Intent(this, ShopActivity.class).putExtra("tab", tab == boards ? "boards" : tab == frames ? "frames" : "dice"));
    }

    private void equip(Kind k, Thing t) {
        if (busy) return;
        busy = true;
        app.api.post(k.path + "/" + t.id + "/equip", new JSONObject(), new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                busy = false;
                k.equipped = r.isNull("equipped") ? null : r.optString("equipped", t.id);
                if (k == boards) app.prefs.edit().putString(ShopActivity.PREF_BOARD, k.equipped).apply();
                if (k == frames && app.user != null) {
                    try {
                        app.user.put("frame", k.equipped == null ? JSONObject.NULL : k.equipped);
                    } catch (Exception ignored) {
                    }
                }
                Sfx.get().play(Sfx.S.EXTRA);
                LudoApp.toast(k == boards ? "👑 " + t.name + " is your board now" : k == dice ? "🎲 " + t.name + " is on your die" : "🔥 " + t.name + " is round your picture");
                render();
            }

            @Override
            public void err(Api.Error e) {
                busy = false;
                super.err(e);
            }
        });
    }

    private LinearLayout.LayoutParams barLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
        lp.topMargin = Ui.dp(this, topDp);
        return lp;
    }

    private View bar(int have, int of, int color) {
        FrameLayout bar = new FrameLayout(this);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(Ui.dp(this, 999));
        track.setColor(0x33ffffff);
        bar.setBackground(track);
        View fill = new View(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Fx.blend(color, 0xffffffff, 0.35f), color});
        g.setCornerRadius(Ui.dp(this, 999));
        fill.setBackground(g);
        bar.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        bar.post(() -> {
            fill.getLayoutParams().width = have == 0 ? 0 : Math.max(Ui.dp(this, 6), (int) (bar.getWidth() * have / (float) Math.max(1, of)));
            fill.requestLayout();
        });
        return bar;
    }
}
