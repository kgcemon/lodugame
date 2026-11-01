package com.ludo3d.game.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludo3d.game.net.Api;

import org.json.JSONObject;

/**
 * Signed-in home with four tabs (Home, Friends, Ranks, Profile). On launch it restores
 * the session from the saved cookie, or sends the player to sign-in.
 */
public class MainActivity extends BaseActivity {
    private FrameLayout content;
    private TextView balance;
    private TextView bell;
    private final TextView[] tabs = new TextView[4];
    private int tab = 0;
    private boolean ready;
    PlayFlow play;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        play = new PlayFlow(this);
        root.addView(Ui.spinner(this), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loadSession();
    }

    private void loadSession() {
        app.api.get("/auth/me", new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                JSONObject user = r.optJSONObject("user");
                if (user == null) {
                    toAuth();
                    return;
                }
                app.user = user;
                app.mergeWallet(r.optJSONObject("wallet"));
                app.requireEmailVerification = r.optBoolean("requireEmailVerification");
                app.api.get("/config", new Api.Cb(MainActivity.this) {
                    @Override
                    public void ok(JSONObject c) {
                        app.config = c;
                        app.socket.connect();
                        ready = true;
                        buildShell();
                    }

                    @Override
                    public void err(Api.Error e) {
                        showRetry(e.message);
                    }
                });
            }

            @Override
            public void err(Api.Error e) {
                if (e.status == 401) toAuth();
                else showRetry(e.message);
            }
        });
    }

    private void toAuth() {
        startActivity(new Intent(this, AuthActivity.class));
        finish();
    }

    private void showRetry(String message) {
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
        root.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void buildShell() {
        root.removeAllViews();
        LinearLayout outer = Ui.vbox(this);

        LinearLayout top = Ui.hbox(this);
        top.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 8));
        TextView av = Ui.avatar(this, app.user.optString("username"), app.user.optInt("avatarHue", 210), 36);
        av.setOnClickListener(v -> selectTab(3));
        top.addView(av);
        LinearLayout who = Ui.vbox(this);
        who.setPadding(Ui.dp(this, 10), 0, 0, 0);
        who.addView(Ui.bold(Ui.text(this, app.user.optString("username"), 15, Ui.INK)));
        who.addView(Ui.text(this, "ID " + app.user.optString("publicId"), 12, Ui.MUTED));
        top.addView(who, Ui.weight(1));
        balance = Ui.text(this, "", 15, Ui.GOLD);
        Ui.bold(balance);
        balance.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        balance.setBackground(Ui.roundStroke(0x22ffb547, Ui.dp(this, 999), Ui.dp(this, 1), 0x55ffb547));
        balance.setOnClickListener(v -> startActivity(new Intent(this, WalletActivity.class)));
        top.addView(balance);
        bell = Ui.text(this, "🔔", 20, Ui.INK);
        bell.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        bell.setOnClickListener(v -> startActivity(new Intent(this, NotificationsActivity.class)));
        top.addView(bell);
        outer.addView(top);

        content = new FrameLayout(this);
        outer.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout nav = Ui.hbox(this);
        nav.setBackgroundColor(0xff10143a);
        nav.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        String[][] items = {{"🏠", "Home"}, {"👥", "Friends"}, {"🏆", "Ranks"}, {"👤", "Profile"}};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView t = Ui.text(this, items[i][0] + "\n" + items[i][1], 12, Ui.MUTED);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(v -> selectTab(idx));
            tabs[i] = t;
            nav.addView(t, Ui.weight(1));
        }
        outer.addView(nav);
        root.addView(outer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        refreshHeader();
        selectTab(tab);
    }

    void selectTab(int i) {
        tab = i;
        for (int k = 0; k < 4; k++) tabs[k].setTextColor(k == i ? Ui.GOLD : Ui.MUTED);
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
        balance.setText(Ui.coins(app.balance()));
        int unread = app.prefs.getInt("unread_notifications", 0);
        bell.setText(unread > 0 ? "🔔" + Math.min(unread, 99) : "🔔");
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
