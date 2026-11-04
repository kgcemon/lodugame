package com.ludox.game.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ludox.game.LudoApp;
import com.ludox.game.audio.Sfx;
import com.ludox.game.game.Board;
import com.ludox.game.gl.GiftThumbs;
import com.ludox.game.net.Api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The gift shop during a match: a panel over the lower part of the screen (the board stays in
 * view). Pick who gets it and what, then confirm. Prices are shown from the server's catalogue and
 * the server charges from its own copy; the match carries on underneath.
 */
final class GiftShopPanel {
    interface Host {
        /** Show this gift's journey on this phone only, to try it out. */
        void previewGift(JSONObject product, String toColor);
    }

    private final BaseActivity a;
    private final LudoApp app = LudoApp.get();
    private final Host host;
    final LinearLayout view;
    private final LinearLayout seatsRow, grid, categories;
    private final TextView coins, gems, gold, diamond, send, preview, chosen;
    private List<JSONObject> products = new ArrayList<>();
    private String currency = "coins", category = "";
    private JSONObject product, seat;
    /** The retry key for this exact gift: kept until the server has answered, so a lost reply can't pay twice. */
    private String pendingKey, pendingFor;
    private boolean sending;

    GiftShopPanel(BaseActivity a, Host host) {
        this.a = a;
        this.host = host;
        view = Ui.vbox(a);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xfa3a1787, 0xfa170a3a});
        bg.setCornerRadius(Ui.dp(a, 20));
        bg.setStroke(Ui.dp(a, 2), 0xccffc94a);
        view.setBackground(bg);
        int p = Ui.dp(a, 10);
        view.setPadding(p, p, p, p);
        view.setClickable(true);

        LinearLayout head = Ui.hbox(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new Fx.GoldText(a, "SEND A GIFT", 19);
        head.addView(title, Ui.weight(1));
        coins = Ui.bold(Ui.text(a, "", 13, 0xfffff3c4));
        head.addView(coins);
        gems = Ui.bold(Ui.text(a, "", 13, 0xffbfe9ff));
        gems.setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 10), 0);
        head.addView(gems);
        head.addView(Ui.smallButton(a, "Close", "glass", v -> close()));
        view.addView(head);

        HorizontalScrollView seatScroll = new HorizontalScrollView(a);
        seatScroll.setHorizontalScrollBarEnabled(false);
        seatsRow = Ui.hbox(a);
        seatScroll.addView(seatsRow);
        LinearLayout.LayoutParams sl = Ui.lpMatchWrap();
        sl.topMargin = Ui.dp(a, 6);
        view.addView(seatScroll, sl);

        LinearLayout tabs = Ui.hbox(a);
        gold = tab("🪙 Gold gifts", v -> setCurrency("coins"));
        diamond = tab("💎 Diamond gifts", v -> setCurrency("diamonds"));
        tabs.addView(gold, Ui.weight(1));
        LinearLayout.LayoutParams dl = Ui.weight(1);
        dl.leftMargin = Ui.dp(a, 6);
        tabs.addView(diamond, dl);
        LinearLayout.LayoutParams tl = Ui.lpMatchWrap();
        tl.topMargin = Ui.dp(a, 6);
        view.addView(tabs, tl);

        HorizontalScrollView catScroll = new HorizontalScrollView(a);
        catScroll.setHorizontalScrollBarEnabled(false);
        categories = Ui.hbox(a);
        catScroll.addView(categories);
        LinearLayout.LayoutParams cl = Ui.lpMatchWrap();
        cl.topMargin = Ui.dp(a, 4);
        view.addView(catScroll, cl);

        ScrollView scroll = new ScrollView(a);
        grid = Ui.vbox(a);
        scroll.addView(grid);
        boolean small = a.getResources().getConfiguration().screenHeightDp < 700;
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, small ? 124 : 186));
        gl.topMargin = Ui.dp(a, 4);
        view.addView(scroll, gl);

        chosen = Ui.bold(Ui.text(a, "", 13, 0xfffff3c4));
        chosen.setGravity(Gravity.CENTER);
        chosen.setSingleLine(true);
        chosen.setEllipsize(TextUtils.TruncateAt.END);
        chosen.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 4));
        view.addView(chosen, Ui.lpMatchWrap());

        LinearLayout foot = Ui.hbox(a);
        preview = Ui.smallButton(a, "▶ Preview", "glass", v -> {
            if (product != null && seat != null) host.previewGift(product, seat.optString("color"));
        });
        foot.addView(preview);
        send = Ui.smallButton(a, "🎁 SEND GIFT", "primary", v -> confirm());
        LinearLayout.LayoutParams fl = Ui.weight(1);
        fl.leftMargin = Ui.dp(a, 8);
        foot.addView(send, fl);
        view.addView(foot, Ui.lpMatchWrap());
        view.setVisibility(View.GONE);
    }

    private TextView tab(String label, View.OnClickListener l) {
        TextView t = Ui.bold(Ui.text(a, label, 14, Color.WHITE));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
        t.setOnClickListener(l);
        return t;
    }

    boolean isOpen() {
        return view.getVisibility() == View.VISIBLE;
    }

    void close() {
        view.setVisibility(View.GONE);
    }

    void open() {
        Sfx.get().play(Sfx.S.CLICK);
        view.setVisibility(View.VISIBLE);
        refresh();
        app.api.get("/player-gifts/catalog", new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                products = new ArrayList<>();
                JSONArray arr = r.optJSONArray("products");
                for (int i = 0; arr != null && i < arr.length(); i++) products.add(arr.optJSONObject(i));
                refresh();
            }
        });
    }

    private void setCurrency(String c) {
        currency = c;
        category = "";
        product = null;
        refresh();
    }

    /** Redraw from the wallet, the seats and the catalogue as they are now. */
    void refresh() {
        if (!isOpen()) return;
        coins.setText("🪙 " + Ui.fmt(app.balance()));
        gems.setText("💎 " + Ui.fmt(app.wallet.optLong("gems")));
        gold.setBackground(Ui.round("coins".equals(currency) ? 0xffb9770e : 0x33ffffff, Ui.dp(a, 10)));
        diamond.setBackground(Ui.round("diamonds".equals(currency) ? 0xff2f7fd6 : 0x33ffffff, Ui.dp(a, 10)));
        renderSeats();
        renderCategories();
        renderGrid();
        renderChoice();
    }

    private void renderSeats() {
        seatsRow.removeAllViews();
        JSONObject room = app.socket.room;
        JSONArray seats = room != null ? room.optJSONArray("seats") : null;
        int you = room != null ? room.optInt("you", -1) : -1;
        boolean stillThere = false;
        JSONObject only = null;
        int eligible = 0;
        for (int i = 0; seats != null && i < seats.length(); i++) {
            JSONObject s = seats.optJSONObject(i);
            if (s == null || i == you || "ai".equals(s.optString("kind")) || s.optBoolean("left")) continue;
            boolean online = "connected".equals(s.optString("status", "connected")) && s.optBoolean("connected", true);
            if (online) {
                eligible++;
                only = s;
            }
            boolean picked = seat != null && seat.optString("id").equals(s.optString("id"));
            if (picked && online) stillThere = true;
            seatsRow.addView(seatChip(s, picked && online, online));
        }
        if (!stillThere) seat = eligible == 1 ? only : null;
        if (seatsRow.getChildCount() == 0) seatsRow.addView(Ui.muted(a, "There's nobody here to send a gift to: bots can't receive them."));
        else if (seat != null && !stillThere) renderSeatsAgain();
    }

    private void renderSeatsAgain() {
        // The only eligible player was just chosen for me: show it.
        JSONObject chosenSeat = seat;
        seatsRow.post(() -> {
            if (chosenSeat == seat && isOpen()) renderSeats();
        });
    }

    private View seatChip(JSONObject s, boolean picked, boolean online) {
        LinearLayout c = Ui.hbox(a);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(Ui.dp(a, 6), Ui.dp(a, 4), Ui.dp(a, 10), Ui.dp(a, 4));
        c.setBackground(Ui.roundStroke(picked ? 0xff5b21b6 : 0x33ffffff, Ui.dp(a, 999), Ui.dp(a, 2), picked ? 0xffffc94a : Board.colorArgb(s.optString("color"))));
        c.addView(Ui.avatar(a, s, s.optString("name"), 26));
        LinearLayout t = Ui.vbox(a);
        t.setPadding(Ui.dp(a, 6), 0, 0, 0);
        t.addView(Ui.bold(Ui.text(a, s.optString("name"), 12, Color.WHITE)));
        t.addView(Ui.text(a, online ? "● Online" : "● Offline", 10, online ? 0xff86efac : 0xfffca5a5));
        c.addView(t);
        c.setAlpha(online ? 1f : 0.5f);
        if (online) c.setOnClickListener(v -> {
            Sfx.get().play(Sfx.S.CLICK);
            seat = s;
            refresh();
        });
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.rightMargin = Ui.dp(a, 6);
        c.setLayoutParams(l);
        return c;
    }

    private void renderCategories() {
        categories.removeAllViews();
        List<String> seen = new ArrayList<>();
        for (JSONObject p : products) {
            if (currency.equals(p.optString("currency")) && !seen.contains(p.optString("category"))) seen.add(p.optString("category"));
        }
        categories.addView(categoryChip("", "All"));
        for (String c : seen) categories.addView(categoryChip(c, c.isEmpty() ? c : c.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + c.substring(1)));
    }

    private View categoryChip(String id, String label) {
        TextView t = Ui.text(a, label, 12, id.equals(category) ? 0xff2a1200 : 0xffe9e2ff);
        t.setBackground(Ui.round(id.equals(category) ? 0xffffc94a : 0x22ffffff, Ui.dp(a, 999)));
        t.setPadding(Ui.dp(a, 10), Ui.dp(a, 3), Ui.dp(a, 10), Ui.dp(a, 3));
        t.setOnClickListener(v -> {
            category = id;
            refresh();
        });
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.rightMargin = Ui.dp(a, 5);
        t.setLayoutParams(l);
        return t;
    }

    private void renderGrid() {
        grid.removeAllViews();
        List<JSONObject> shown = new ArrayList<>();
        for (JSONObject p : products) {
            if (currency.equals(p.optString("currency")) && (category.isEmpty() || category.equals(p.optString("category")))) shown.add(p);
        }
        LinearLayout row = null;
        for (int i = 0; i < shown.size(); i++) {
            if (i % 4 == 0) {
                row = Ui.hbox(a);
                grid.addView(row, Ui.lpMatchWrap());
            }
            row.addView(card(shown.get(i)), Ui.weight(1));
        }
        while (row != null && row.getChildCount() < 4) row.addView(new View(a), Ui.weight(1));
    }

    static String rarityLabel(String r) {
        return r.isEmpty() ? r : r.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + r.substring(1);
    }

    static int rarityColor(String r) {
        switch (r) {
            case "rare": return 0xff7dd3fc;
            case "epic": return 0xffd8b4fe;
            case "legendary": return 0xffffc94a;
            default: return 0xffd1d5db;
        }
    }

    /** A still of the real 3D model. */
    static ImageView thumb(BaseActivity a, String model) {
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        GiftThumbs.get(model, b -> {
            if (b != null) img.setImageBitmap(b);
        });
        return img;
    }

    private View card(JSONObject p) {
        boolean picked = product != null && product.optString("id").equals(p.optString("id"));
        boolean diamonds = "diamonds".equals(p.optString("currency"));
        LinearLayout c = Ui.vbox(a);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        int rare = rarityColor(p.optString("rarity"));
        c.setBackground(Ui.roundStroke(picked ? 0xff5b21b6 : 0x26ffffff, Ui.dp(a, 12), Ui.dp(a, picked ? 2 : 1), picked ? 0xffffc94a : (rare & 0x00ffffff) | 0x77000000));
        c.setPadding(Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 4));
        FrameLayout pic = new FrameLayout(a);
        pic.addView(thumb(a, p.optString("model")), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (p.optBoolean("featured")) {
            TextView star = Ui.text(a, "⭐", 11, Color.WHITE);
            pic.addView(star, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
        }
        c.addView(pic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 50)));
        TextView n = Ui.bold(Ui.text(a, p.optString("name"), 10, Color.WHITE));
        n.setGravity(Gravity.CENTER);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(n, Ui.lpMatchWrap());
        TextView price = Ui.bold(Ui.text(a, (diamonds ? "💎 " : "🪙 ") + Ui.fmt(p.optLong("price")), 11, diamonds ? 0xffbfe9ff : 0xffffd76a));
        price.setGravity(Gravity.CENTER);
        c.addView(price, Ui.lpMatchWrap());
        c.setOnClickListener(v -> {
            Sfx.get().play(Sfx.S.CLICK);
            product = p;
            refresh();
        });
        LinearLayout.LayoutParams l = Ui.weight(1);
        l.setMargins(Ui.dp(a, 2), Ui.dp(a, 2), Ui.dp(a, 2), Ui.dp(a, 2));
        c.setLayoutParams(l);
        return c;
    }

    private void renderChoice() {
        boolean ready = product != null && seat != null;
        Ui.setEnabled(send, ready);
        Ui.setEnabled(preview, ready);
        if (product != null) {
            chosen.setText(product.optString("name") + " · " + rarityLabel(product.optString("rarity")) + (seat != null ? " → " + seat.optString("name") : " · choose a player"));
        } else {
            chosen.setText(seat != null ? "Choose a gift for " + seat.optString("name") : "Choose a player and a gift");
        }
    }

    private void confirm() {
        JSONObject p = product, to = seat;
        if (p == null || to == null || sending) return;
        boolean diamonds = "diamonds".equals(p.optString("currency"));
        long price = p.optLong("price");
        long have = diamonds ? app.wallet.optLong("gems") : app.balance();
        String unit = diamonds ? "Diamonds" : "Gold";
        LinearLayout box = Ui.vbox(a);
        ImageView pic = thumb(a, p.optString("model"));
        box.addView(pic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 90)));
        box.addView(line("Recipient", to.optString("name")));
        box.addView(line("Price", Ui.fmt(price) + " " + unit));
        box.addView(line("Your current balance", Ui.fmt(have) + " " + unit));
        if (have < price) {
            TextView no = Ui.bold(Ui.text(a, "You don't have enough " + unit.toLowerCase(java.util.Locale.ROOT) + " for this gift.", 14, 0xfffca5a5));
            no.setPadding(0, Ui.dp(a, 8), 0, 0);
            box.addView(no);
            Class<?> shop = diamonds ? GemShopActivity.class : WalletActivity.class;
            Popup.show(a, "Not enough " + unit, box, Popup.plain("Close", null), new Popup.Btn(diamonds ? "Get diamonds" : "Open wallet", "gold", () -> a.startActivity(new android.content.Intent(a, shop))));
            return;
        }
        box.addView(line("Balance after sending", Ui.fmt(have - price) + " " + unit));
        Popup.show(a, "SEND " + p.optString("name").toUpperCase(java.util.Locale.getDefault()) + "?", box, Popup.plain("CANCEL", null), Popup.ok("SEND GIFT", () -> doSend(p, to)));
    }

    private LinearLayout line(String k, String v) {
        LinearLayout r = Ui.row(a);
        r.addView(Ui.muted(a, k), Ui.weight(1));
        r.addView(Ui.bold(Ui.text(a, v, 15, Ui.INK)));
        return r;
    }

    private void doSend(JSONObject p, JSONObject to) {
        String what = p.optString("id") + ">" + to.optString("id");
        // Asking again about the same gift after no reply must not pay twice.
        if (!what.equals(pendingFor)) {
            pendingFor = what;
            pendingKey = UUID.randomUUID().toString();
        }
        sending = true;
        Ui.setEnabled(send, false);
        JSONObject msg = PlayFlow.obj("productId", p.optString("id"), "toSeatId", to.optString("id"), "key", pendingKey);
        app.socket.emit("playerGift:send", msg, res -> {
            sending = false;
            renderChoice();
            if (res.optBoolean("ok")) {
                pendingFor = null;
                close();
                return;
            }
            String code = res.optString("code");
            // No answer: it may have gone through. The same key keeps the retry safe; anything else was a real refusal.
            boolean unknown = res.optString("error").contains("did not respond") || res.optString("error").contains("Not connected");
            if (!unknown) pendingFor = null;
            if ("INSUFFICIENT_COINS".equals(code) || "NOT_ENOUGH_GEMS".equals(code)) {
                Popup.message(a, "Not enough balance", res.optString("error"), Popup.ok("OK", null));
            } else {
                LudoApp.toast(res.optString("error", "The gift could not be sent"));
            }
        });
    }
}
