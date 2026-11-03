package com.ludo3d.game.ui;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludo3d.game.audio.Sfx;
import com.ludo3d.game.net.Api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * My Dice Collection: every die I own, the one I use, how much of the catalogue I've
 * collected (overall and per rarity), and a tap to preview or equip any of them. The
 * server keeps what I own and which one is on; opponents see the equipped one.
 */
public class DiceCollectionActivity extends BaseActivity {
    private LinearLayout col;
    private final List<DiceView.Skin> all = new ArrayList<>();
    private final Set<String> owned = new HashSet<>();
    private String equipped = DiceView.Skin.CLASSIC.id;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        col = pageWithBack("My Dice");
        col.addView(Ui.spinner(this));
        load();
    }

    private void load() {
        app.api.get("/skins", new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                all.clear();
                JSONArray arr = r.optJSONArray("skins");
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    DiceView.Skin k = arr.optJSONObject(i) != null ? DiceView.Skin.from(arr.optJSONObject(i)) : null;
                    if (k != null) all.add(k);
                }
                owned.clear();
                JSONArray o = r.optJSONArray("owned");
                for (int i = 0; o != null && i < o.length(); i++) owned.add(o.optString(i));
                owned.add(DiceView.Skin.CLASSIC.id);
                equipped = r.isNull("equipped") ? DiceView.Skin.CLASSIC.id : r.optString("equipped", DiceView.Skin.CLASSIC.id);
                render();
            }
        });
    }

    private void render() {
        col.removeAllViews();
        List<DiceView.Skin> mine = new ArrayList<>();
        for (DiceView.Skin k : all) if (owned.contains(k.id)) mine.add(k);

        // The die in use, big.
        DiceView.Skin on = null;
        for (DiceView.Skin k : mine) if (k.id.equals(equipped)) on = k;
        if (on != null) {
            LinearLayout hero = Ui.hbox(this);
            hero.setGravity(Gravity.CENTER_VERTICAL);
            hero.setBackground(DiceLux.card(this, on.rarity, true));
            hero.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
            FrameLayout ped = new FrameLayout(this);
            ped.addView(new DiceLux.Platform(this, on.rarity), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ImageView iv = new ImageView(this);
            DiceLux.thumb(iv, on, Ui.dp(this, 120));
            ped.addView(iv, new FrameLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 120), Gravity.CENTER));
            hero.addView(ped, new LinearLayout.LayoutParams(Ui.dp(this, 124), Ui.dp(this, 130)));
            LinearLayout t = Ui.vbox(this);
            t.setPadding(Ui.dp(this, 8), 0, 0, 0);
            t.addView(Ui.bold(Ui.text(this, "EQUIPPED", 11, 0xff9affc0)));
            t.addView(new Fx.GoldText(this, on.name, 22));
            t.addView(DiceLux.badge(this, on.rarity), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            t.addView(Ui.text(this, "Everyone at the table sees this die when you roll.", 12, 0xffd9cfff));
            DiceView.Skin shown = on;
            t.addView(Ui.smallButton(this, "Preview", "glass", v -> preview(shown)), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            hero.addView(t, Ui.weight(1));
            col.addView(hero, Ui.lpMatchWrap());
        }

        // Progress: overall and per rarity.
        LinearLayout prog = Ui.card(this);
        Ui.margins(prog, this, 0, 12, 0, 0);
        prog.addView(Ui.bold(Ui.text(this, "Collection progress", 15, Ui.INK)));
        prog.addView(Ui.text(this, mine.size() + " of " + all.size() + " dice collected", 13, 0xffd9cfff));
        prog.addView(bar(mine.size(), all.size(), 0xffffb547));
        for (String r : DiceLux.RARITIES) {
            int have = 0, of = 0;
            for (DiceView.Skin k : all) {
                if (!r.equals(k.rarity)) continue;
                of++;
                if (owned.contains(k.id)) have++;
            }
            if (of == 0) continue;
            LinearLayout row = Ui.hbox(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 8), 0, 0);
            TextView b = DiceLux.badge(this, r);
            row.addView(b, new LinearLayout.LayoutParams(Ui.dp(this, 92), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, Ui.dp(this, 6), 1);
            bl.setMargins(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
            View bar = bar(have, of, DiceLux.color(r));
            row.addView(bar, bl);
            row.addView(Ui.text(this, have + "/" + of, 12, Ui.INK));
            prog.addView(row, Ui.lpMatchWrap());
        }
        col.addView(prog);

        // Every die I own.
        col.addView(Ui.heading(this, "Your dice (" + mine.size() + ")"));
        LinearLayout row = null;
        for (int i = 0; i < mine.size(); i++) {
            if (i % 2 == 0) {
                row = Ui.hbox(this);
                col.addView(row, Ui.lpMatchWrap());
            }
            row.addView(card(mine.get(i)), lp(i % 2));
        }
        if (mine.size() % 2 == 1 && row != null) row.addView(new View(this), lp(1));
        TextView more = Ui.button(this, "🛍  Get more dice", "primary", v -> {
            startActivity(new Intent(this, ShopActivity.class));
            finish();
        });
        LinearLayout.LayoutParams ml = Ui.lpMatchWrap();
        ml.topMargin = Ui.dp(this, 16);
        col.addView(more, ml);
    }

    private LinearLayout.LayoutParams lp(int column) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.topMargin = Ui.dp(this, 10);
        lp.leftMargin = column == 0 ? 0 : Ui.dp(this, 5);
        lp.rightMargin = column == 1 ? 0 : Ui.dp(this, 5);
        return lp;
    }

    private View bar(int have, int of, int color) {
        FrameLayout bar = new FrameLayout(this);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(Ui.dp(this, 999));
        track.setColor(0x33ffffff);
        bar.setBackground(track);
        View fill = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 999));
        g.setColor(color);
        fill.setBackground(g);
        bar.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        bar.post(() -> {
            fill.getLayoutParams().width = have == 0 ? 0 : Math.max(Ui.dp(this, 6), (int) (bar.getWidth() * have / (float) Math.max(1, of)));
            fill.requestLayout();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
        lp.topMargin = Ui.dp(this, 6);
        bar.setLayoutParams(lp);
        return bar;
    }

    private View card(DiceView.Skin k) {
        boolean using = k.id.equals(equipped);
        LinearLayout card = Ui.vbox(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(DiceLux.card(this, k.rarity, using));
        card.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        card.addView(DiceLux.badge(this, k.rarity), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageView iv = new ImageView(this);
        DiceLux.thumb(iv, k, Ui.dp(this, 96));
        card.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96)));
        TextView n = Ui.bold(Ui.text(this, k.name, 14, Ui.INK));
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        n.setGravity(Gravity.CENTER);
        card.addView(n, Ui.lpMatchWrap());
        TextView c = Ui.text(this, DiceLux.collectionShort(k.collection), 11, 0xffb8acdf);
        c.setGravity(Gravity.CENTER);
        card.addView(c, Ui.lpMatchWrap());
        TextView act = using ? Ui.smallButton(this, "✓ Equipped", "ok", null) : Ui.smallButton(this, "Equip", "primary", v -> equip(k));
        if (using) Ui.setEnabled(act, false);
        LinearLayout.LayoutParams al = Ui.lpMatchWrap();
        al.topMargin = Ui.dp(this, 8);
        card.addView(act, al);
        card.setOnClickListener(v -> preview(k));
        return card;
    }

    private void preview(DiceView.Skin k) {
        DicePreview.Offer o = new DicePreview.Offer();
        boolean using = k.id.equals(equipped);
        o.status = using ? "✓ Equipped" : "Owned";
        o.action = using ? "In use" : "Equip this dice";
        o.actionStyle = "ok";
        o.onAction = using ? null : () -> equip(k);
        DicePreview.show(this, k, null, o);
    }

    private void equip(DiceView.Skin k) {
        if (busy) return;
        busy = true;
        app.api.post("/skins/" + k.id + "/equip", new JSONObject(), new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                busy = false;
                equipped = r.optString("equipped", k.id);
                Sfx.get().play(Sfx.S.EXTRA);
                com.ludo3d.game.LudoApp.toast("🎲 " + k.name + " is on your die");
                render();
            }

            @Override
            public void err(Api.Error e) {
                busy = false;
                super.err(e);
            }
        });
    }
}
