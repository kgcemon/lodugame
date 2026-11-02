package com.ludo3d.game.ui;

import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.ludo3d.game.LudoApp;
import com.ludo3d.game.audio.Sfx;
import com.ludo3d.game.net.Api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The gem shop: gem packs sold through Google Play. Prices come from Play; after a
 * purchase the server verifies it with Google and adds the gems, then the purchase is
 * consumed so the pack can be bought again. Purchases that weren't redeemed (app closed,
 * no network) are picked up the next time the shop opens.
 */
public class GemShopActivity extends BaseActivity {
    private LinearLayout col;
    private BillingClient billing;
    private TextView balance;
    private final Map<String, ProductDetails> details = new HashMap<>();
    private LinearLayout packs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        col = pageWithBack("Diamond shop");
        render();
        if (app.config != null && !app.config.optBoolean("purchasesEnabled")) {
            showUnavailable("Purchases aren't switched on on the game server yet.");
            return;
        }
        // Devices without Google Play never answer: don't spin forever.
        LudoApp.MAIN.postDelayed(() -> {
            if (!isFinishing() && details.isEmpty() && packs != null && packs.getChildCount() == 1 && !(packs.getChildAt(0) instanceof LinearLayout)) {
                showUnavailable("Couldn't reach Google Play. Make sure the Play Store is installed and you're signed in.");
            }
        }, 8000);
        billing = BillingClient.newBuilder(this)
                .setListener(this::onPurchases)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .build();
        billing.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult result) {
                runOnUiThread(() -> {
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        loadProducts();
                        redeemPending();
                    } else {
                        showUnavailable("Google Play billing isn't available on this device.");
                    }
                });
            }

            @Override
            public void onBillingServiceDisconnected() {
                // The library reconnects on the next call.
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (billing != null) billing.endConnection();
    }

    private JSONArray gemPacks() {
        JSONArray p = app.config != null ? app.config.optJSONArray("gemPacks") : null;
        return p != null ? p : new JSONArray();
    }

    private void render() {
        col.removeAllViews();
        LinearLayout head = Ui.card(this);
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView gem = Ui.text(this, "💎", 54, Ui.INK);
        gem.setGravity(Gravity.CENTER);
        head.addView(gem);
        balance = new Fx.GoldText(this, Ui.fmt(app.wallet.optLong("gems")) + " diamonds", 30, new int[]{0xffffffff, 0xffb9f2ff, 0xff4fd8ff}, 0xff0b4f8a);
        balance.setGravity(Gravity.CENTER);
        head.addView(balance, Ui.lpMatchWrap());
        JSONArray costs = app.config != null ? app.config.optJSONArray("rerollCosts") : null;
        String c = costs != null && costs.length() >= 3 ? costs.optInt(0) + ", " + costs.optInt(1) + " and " + costs.optInt(2) : "5, 15 and 45";
        TextView how = Ui.muted(this, "Use diamonds to re-roll the dice when you don't like your roll: " + c + " diamonds — up to 3 times per match.");
        how.setGravity(Gravity.CENTER);
        how.setPadding(0, Ui.dp(this, 8), 0, 0);
        head.addView(how);
        col.addView(head);

        col.addView(Ui.heading(this, "Diamond packs"));
        packs = Ui.vbox(this);
        packs.addView(Ui.spinner(this));
        col.addView(packs);
        TextView note = Ui.muted(this, "Payments are handled by Google Play. Diamonds have no cash value and can't be withdrawn.");
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, Ui.dp(this, 12), 0, 0);
        col.addView(note);
    }

    private void showUnavailable(String why) {
        if (packs == null) return;
        packs.removeAllViews();
        LinearLayout c = Ui.card(this);
        c.addView(Ui.bold(Ui.text(this, "Diamond shop isn't open yet", 16, Ui.INK)));
        c.addView(Ui.muted(this, why));
        packs.addView(c);
    }

    private void loadProducts() {
        if (app.config != null && !app.config.optBoolean("purchasesEnabled")) {
            showUnavailable("Purchases aren't switched on on the game server yet.");
            return;
        }
        JSONArray list = gemPacks();
        List<QueryProductDetailsParams.Product> products = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            String id = list.optJSONObject(i).optString("productId");
            products.add(QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(BillingClient.ProductType.INAPP).build());
        }
        if (products.isEmpty()) {
            showUnavailable("No diamond packs are on sale right now.");
            return;
        }
        billing.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build(), (result, found) -> runOnUiThread(() -> {
            details.clear();
            if (found != null) for (ProductDetails d : found.getProductDetailsList()) details.put(d.getProductId(), d);
            showPacks();
        }));
    }

    private void showPacks() {
        packs.removeAllViews();
        JSONArray list = gemPacks();
        int[][] palette = {Fx.BLUE, Fx.PURPLE, Fx.YELLOW};
        int shown = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject p = list.optJSONObject(i);
            ProductDetails d = details.get(p.optString("productId"));
            if (d == null) continue;
            ProductDetails.OneTimePurchaseOfferDetails offer = d.getOneTimePurchaseOfferDetails();
            String price = offer != null ? offer.getFormattedPrice() : "";
            Fx.Tile t = new Fx.Tile(this, palette[shown % palette.length], 18, 6);
            LinearLayout row = Ui.hbox(this);
            row.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
            row.addView(Ui.text(this, i == 0 ? "💎" : i == 1 ? "💎💎" : "💰", 28, Ui.INK));
            Fx.GoldText amount = Fx.GoldText.label(this, Ui.fmt(p.optLong("gems")) + " GEMS", 20, 0x99000000);
            amount.setPadding(Ui.dp(this, 12), 0, 0, 0);
            row.addView(amount, Ui.weight(1));
            row.addView(new Fx.GoldText(this, price, 18));
            t.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            t.setOnClickListener(v -> buy(d));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 78));
            lp.bottomMargin = Ui.dp(this, 12);
            packs.addView(t, lp);
            shown++;
        }
        if (shown == 0) {
            showUnavailable("The diamond packs aren't available from Google Play yet. They appear once the app and its products are published in the Play Console.");
        }
    }

    private void buy(ProductDetails d) {
        String userId = app.user != null ? app.user.optString("id") : "";
        BillingFlowParams params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(java.util.Collections.singletonList(
                        BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()))
                // Ties the purchase to this account: the server refuses it for anyone else.
                .setObfuscatedAccountId(userId)
                .build();
        billing.launchBillingFlow(this, params);
    }

    private void onPurchases(BillingResult result, List<Purchase> purchases) {
        if (result.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) return;
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK || purchases == null) {
            runOnUiThread(() -> LudoApp.toast("Purchase failed: " + result.getDebugMessage()));
            return;
        }
        for (Purchase p : purchases) runOnUiThread(() -> redeem(p));
    }

    /** Purchases bought earlier but never redeemed (app closed mid-way, no network). */
    private void redeemPending() {
        billing.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
                (result, list) -> runOnUiThread(() -> {
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) return;
                    for (Purchase p : list) redeem(p);
                }));
    }

    private void redeem(Purchase p) {
        if (p.getPurchaseState() == Purchase.PurchaseState.PENDING) {
            LudoApp.toast("Payment pending — your diamonds arrive when it completes.");
            return;
        }
        if (p.getPurchaseState() != Purchase.PurchaseState.PURCHASED || p.getProducts().isEmpty()) return;
        String productId = p.getProducts().get(0);
        JSONObject body = PlayFlow.obj("productId", productId);
        try {
            body.put("purchaseToken", p.getPurchaseToken());
        } catch (Exception ignored) {
            return;
        }
        app.api.post("/gems/purchase", body, new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                app.mergeWallet(r.optJSONObject("wallet"));
                long granted = r.optLong("granted");
                if (granted > 0) {
                    Sfx.get().play(Sfx.S.MATCH_FOUND);
                    Popup.message(GemShopActivity.this, "Thank you!", "💎 +" + Ui.fmt(granted) + " diamonds added to your account.");
                }
                balance.setText(Ui.fmt(app.wallet.optLong("gems")) + " diamonds");
                // The server already consumed it; this just clears the local copy.
                billing.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(p.getPurchaseToken()).build(), (res, token) -> {});
            }

            @Override
            public void err(Api.Error e) {
                // Keep the purchase unconsumed so it is retried next time; acknowledge so
                // Google doesn't refund it automatically while we retry.
                if (!p.isAcknowledged()) {
                    billing.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.getPurchaseToken()).build(), res -> {});
                }
                Popup.message(GemShopActivity.this, "Couldn't add diamonds yet", e.message + "\n\nYour payment is safe: the diamonds are added automatically next time you open the shop.");
            }
        });
    }

    @Override
    public void onSocketEvent(String event, Object data) {
        super.onSocketEvent(event, data);
        if ("wallet".equals(event) && balance != null) balance.setText(Ui.fmt(app.wallet.optLong("gems")) + " diamonds");
    }
}
