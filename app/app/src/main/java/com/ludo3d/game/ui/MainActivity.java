package com.ludo3d.game.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.splashscreen.SplashScreen;

import com.ludo3d.game.LudoApp;

import com.ludo3d.game.net.Api;

import org.json.JSONObject;

/**
 * Signed-in home with four tabs (Home, Friends, Ranks, Profile). On launch it restores
 * the session from the saved cookie, or sends the player to sign-in — all while the splash
 * screen is up, so the first thing after the logo is the finished home screen.
 */
public class MainActivity extends BaseActivity {
    private FrameLayout content;
    private TextView balance;
    private TextView bell;
    private TextView gems;
    private LinearLayout nav;
    private int tab = 0;
    private boolean ready;
    /** The splash screen stays up until the first real screen is ready (or the time limit). */
    private boolean launched;
    /** The longest the splash may hold the app before the loading spinner takes over. */
    private static final long SPLASH_MAX_MS = 6000;
    PlayFlow play;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        splash.setKeepOnScreenCondition(() -> !launched);
        splash.setOnExitAnimationListener(provider -> {
            // The logo swells and fades as the home screen shows through.
            View icon = provider.getIconView();
            icon.animate().scaleX(1.35f).scaleY(1.35f).alpha(0f).setDuration(320)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator()).start();
            provider.getView().animate().alpha(0f).setStartDelay(120).setDuration(300)
                    .withEndAction(provider::remove).start();
        });
        LudoApp.MAIN.postDelayed(() -> launched = true, SPLASH_MAX_MS);
        play = new PlayFlow(this);
        root.addView(Ui.spinner(this), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loadSession();
    }

    /** Session and config load side by side; the home screen is built once both are in. */
    private void loadSession() {
        final JSONObject[] me = new JSONObject[1];
        final JSONObject[] cfg = new JSONObject[1];
        final boolean[] failed = {false};
        Runnable both = () -> {
            if (failed[0] || me[0] == null || cfg[0] == null) return;
            app.config = cfg[0];
            app.socket.connect();
            ready = true;
            buildShell();
            loadConfig();
            launched = true;
        };
        app.api.get("/auth/me", new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                if (failed[0]) return;
                JSONObject user = r.optJSONObject("user");
                if (user == null) {
                    failed[0] = true;
                    toAuth();
                    return;
                }
                app.user = user;
                app.mergeWallet(r.optJSONObject("wallet"));
                app.requireEmailVerification = r.optBoolean("requireEmailVerification");
                me[0] = r;
                both.run();
            }

            @Override
            public void err(Api.Error e) {
                if (failed[0]) return;
                failed[0] = true;
                if (e.status == 401) toAuth();
                else showRetry(e.message);
            }
        });
        app.api.get("/config", new Api.Cb(this) {
            @Override
            public void ok(JSONObject c) {
                cfg[0] = c;
                both.run();
            }

            @Override
            public void err(Api.Error e) {
                if (failed[0]) return;
                failed[0] = true;
                showRetry(e.message);
            }
        });
    }

    private void toAuth() {
        launched = true;
        startActivity(new Intent(this, AuthActivity.class));
        finish();
    }

    private void showRetry(String message) {
        launched = true;
        root.removeAllViews();
        LinearLayout l = Ui.vbox(this);
        l.setGravity(Gravity.CENTER);
        int p = Ui.dp(this, 24);
        l.setPadding(p, p, p, p);
        TextView t = Ui.title(this, "Can't reach the game server");
        t.setGravity(Gravity.CENTER);
        l.addView(t);
        TextView m = Ui.muted(this, message);
        m.setGravity(Gravity.CENTER);
        m.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 20));
        l.addView(m);
        l.addView(Ui.button(this, "Try again", "primary", v -> {
            root.removeAllViews();
            root.addView(Ui.spinner(this));
            loadSession();
        }));
        TextView offline = Ui.button(this, "📱 Play offline", "ok", v -> OfflineSetup.show(this));
        Ui.margins(offline, this, 0, 12, 0, 0);
        l.addView(offline);
        root.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * Game-style shell: a purple 3D top bar (mail, coins, avatar, gems, settings), the
     * current tab, and a chunky bottom bar with Home raised in the middle.
     */
    private void buildShell() {
        root.removeAllViews();
        root.setBackground(Fx.background(this));
        LinearLayout outer = Ui.vbox(this);

        // ---- top bar
        LinearLayout top = Ui.hbox(this);
        top.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 10));
        GradientDrawable barBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xff8b5cf6, Fx.BAR});
        barBg.setCornerRadii(new float[]{0, 0, 0, 0, Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22)});
        barBg.setStroke(Ui.dp(this, 2), Fx.BAR_EDGE);
        top.setBackground(barBg);
        top.setElevation(Ui.dp(this, 8));

        FrameLayout mail = new FrameLayout(this);
        mail.addView(Fx.badge(this, "✉️", Fx.PURPLE, 44, v -> startActivity(new Intent(this, NotificationsActivity.class))),
                new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 48)));
        bell = Ui.bold(Ui.text(this, "", 10, Color.WHITE));
        bell.setGravity(Gravity.CENTER);
        bell.setBackground(Ui.roundStroke(0xffe53945, Ui.dp(this, 999), Ui.dp(this, 2), Color.WHITE));
        mail.addView(bell, new FrameLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20), Gravity.TOP | Gravity.END));
        top.addView(mail, new LinearLayout.LayoutParams(Ui.dp(this, 50), Ui.dp(this, 50)));

        balance = pill(top, "🪙", v -> startActivity(new Intent(this, WalletActivity.class)));

        FrameLayout avatarWrap = new FrameLayout(this);
        TextView av = Ui.avatar(this, app.user.optString("username"), app.user.optInt("avatarHue", 210), 54);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0);
        ring.setStroke(Ui.dp(this, 4), 0xffffc94a);
        View ringView = new View(this);
        ringView.setBackground(ring);
        avatarWrap.addView(av, new FrameLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 54), Gravity.CENTER));
        avatarWrap.addView(ringView, new FrameLayout.LayoutParams(Ui.dp(this, 62), Ui.dp(this, 62), Gravity.CENTER));
        avatarWrap.setOnClickListener(v -> selectTab(3));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64));
        alp.leftMargin = Ui.dp(this, 4);
        alp.rightMargin = Ui.dp(this, 4);
        top.addView(avatarWrap, alp);

        gems = pill(top, "💎", v -> startActivity(new Intent(this, GemShopActivity.class)));
        top.addView(Fx.badge(this, "⚙️", Fx.PURPLE, 44, v -> selectTab(3)), new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 48)));
        outer.addView(top, Ui.lpMatchWrap());

        content = new FrameLayout(this);
        outer.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // ---- bottom bar: Ranks, Friends, HOME, Wallet, Profile
        nav = Ui.hbox(this);
        nav.setGravity(Gravity.BOTTOM);
        nav.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 6));
        GradientDrawable navBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xff4c1d95, 0xff2e1065});
        navBg.setCornerRadii(new float[]{Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22), 0, 0, 0, 0});
        nav.setBackground(navBg);
        outer.addView(nav, Ui.lpMatchWrap());
        root.addView(outer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        refreshHeader();
        selectTab(tab);
    }

    /** A dark 3D pill in the top bar ("🪙 1,100"). Returns its text view. */
    private TextView pill(LinearLayout parent, String icon, View.OnClickListener onClick) {
        Fx.Tile t = new Fx.Tile(this, Fx.PURPLE_DARK, 16, 3);
        LinearLayout row = Ui.hbox(this);
        row.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 10), 0);
        row.addView(Ui.text(this, icon, 16, Color.WHITE));
        TextView value = Ui.bold(Ui.text(this, "", 15, Color.WHITE));
        value.setSingleLine(true);
        value.setPadding(Ui.dp(this, 6), 0, 0, 0);
        row.addView(value);
        t.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        t.setOnClickListener(onClick);
        parent.addView(t, new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1));
        return value;
    }

    private static final String[][] NAV = {{"🏆", "RANKS"}, {"👥", "SOCIAL"}, {"🏠", "HOME"}, {"🪙", "WALLET"}, {"👤", "PROFILE"}};
    /** Tab index for each bottom-bar slot (-1 = opens the wallet screen). */
    private static final int[] NAV_TAB = {2, 1, 0, -1, 3};

    private void renderNav() {
        nav.removeAllViews();
        for (int k = 0; k < NAV.length; k++) {
            final int slot = k;
            boolean home = NAV_TAB[k] == 0;
            boolean selected = NAV_TAB[k] == tab;
            Fx.Tile t = new Fx.Tile(this, selected ? Fx.PURPLE : Fx.PURPLE_DARK, 16, selected ? 5 : 3);
            LinearLayout col = Ui.vbox(this);
            col.setGravity(Gravity.CENTER);
            // Vertical LinearLayout children default to full width, so centre the text itself.
            TextView icon = Ui.text(this, NAV[k][0], home ? 30 : 22, Color.WHITE);
            icon.setGravity(Gravity.CENTER);
            col.addView(icon);
            TextView label = Ui.bold(Ui.text(this, NAV[k][1], 10, selected ? Color.WHITE : 0xffc4b5fd));
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER);
            col.addView(label);
            t.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            t.setOnClickListener(v -> {
                if (NAV_TAB[slot] < 0) startActivity(new Intent(this, WalletActivity.class));
                else selectTab(NAV_TAB[slot]);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, home ? 76 : 62), home ? 1.25f : 1f);
            lp.leftMargin = Ui.dp(this, 3);
            lp.rightMargin = Ui.dp(this, 3);
            nav.addView(t, lp);
        }
    }

    void selectTab(int i) {
        tab = i;
        renderNav();
        content.removeAllViews();
        switch (i) {
            case 0: new HomeTab(this, content).render(); break;
            case 1: new FriendsTab(this, content).render(); break;
            case 2: new RanksTab(this, content).render(); break;
            default: new ProfileTab(this, content).render(); break;
        }
    }

    void refreshHeader() {
        if (balance == null) return;
        balance.setText(Ui.fmt(app.balance()));
        int unread = app.prefs.getInt("unread_notifications", 0);
        bell.setText(String.valueOf(Math.min(unread, 99)));
        bell.setVisibility(unread > 0 ? View.VISIBLE : View.GONE);
        gems.setText(Ui.fmt(app.wallet.optLong("gems")));
    }

    /** Re-read /config (gem packs and re-roll prices may change on the server). */
    void loadConfig() {
        app.api.get("/config", new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                app.config = r;
            }

            @Override
            public void err(Api.Error e) {
                // Keep the last config.
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ready) return;
        if (app.user == null) {
            toAuth();
            return;
        }
        app.socket.connect();
        refreshWallet();
        loadConfig();
        refreshHeader();
        selectTab(tab);
    }

    void refreshWallet() {
        app.api.get("/wallet", new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                app.mergeWallet(r.optJSONObject("wallet"));
                refreshHeader();
            }

            @Override
            public void err(Api.Error e) {
                // Keep the last known balance.
            }
        });
    }

    @Override
    public void onSocketEvent(String event, Object data) {
        super.onSocketEvent(event, data);
        if (!ready) return;
        switch (event) {
            case "wallet":
                refreshHeader();
                break;
            case "friends:changed":
                if (tab == 1 || tab == 0) selectTab(tab);
                break;
            case "room:state":
            case "room:left":
            case "room:closed":
                if (tab == 0) selectTab(0);
                break;
            default:
                break;
        }
    }

    @Override
    protected void onNotificationCountChanged() {
        refreshHeader();
    }

    void signOut() {
        app.api.post("/auth/logout", null, new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                finishSignOut();
            }

            @Override
            public void err(Api.Error e) {
                finishSignOut();
            }
        });
    }

    private void finishSignOut() {
        app.socket.disconnect();
        app.cookies.clear();
        app.user = null;
        app.prefs.edit().remove("unread_notifications").apply();
        toAuth();
    }

    View.OnClickListener open(Class<?> cls) {
        return v -> startActivity(new Intent(this, cls));
    }
}
