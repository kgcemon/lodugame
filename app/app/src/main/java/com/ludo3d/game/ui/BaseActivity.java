package com.ludo3d.game.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.audio.Sfx;
import com.ludo3d.game.net.GameSocket;

import org.json.JSONObject;

/**
 * Shared behaviour for every screen: listens to the game socket while visible, opens
 * the game when a match starts, shows live notifications, and returns to sign-in when
 * the session ends.
 */
public abstract class BaseActivity extends AppCompatActivity implements GameSocket.Listener {
    protected LudoApp app;
    protected FrameLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = LudoApp.get();
        root = new FrameLayout(this);
        root.setBackground(Fx.background(this));
        // Draw edge to edge on every Android version (15+ always does) and pad the content clear of
        // the status/navigation bars, display cutout and keyboard ourselves.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        app.socket.addListener(this);
        // A match may have started (or be running after a reconnect) while this screen was hidden.
        if (!(this instanceof GameActivity) && app.socket.lastUpdate != null && app.socket.room != null
                && "playing".equals(app.socket.room.optString("status"))) {
            openGame();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        app.socket.removeListener(this);
    }

    @Override
    public void onSocketEvent(String event, Object data) {
        switch (event) {
            case "game:update":
                if (!(this instanceof GameActivity)) openGame();
                break;
            case "notify":
                showNotification((JSONObject) data);
                break;
            case "room:closed":
                if (!(this instanceof GameActivity) && !(this instanceof RoomActivity)) LudoApp.toast(String.valueOf(data));
                break;
            case "unauthorized":
                app.user = null;
                Intent i = new Intent(this, AuthActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                break;
            default:
                break;
        }
    }

    protected void openGame() {
        startActivity(new Intent(this, GameActivity.class));
        if (this instanceof RoomActivity) finish();
    }

    protected void openRoom() {
        startActivity(new Intent(this, RoomActivity.class));
    }

    protected void showNotification(JSONObject n) {
        JSONObject p = n.optJSONObject("payload");
        if (p == null) p = new JSONObject();
        String text = Notifications.describe(n.optString("type"), p);
        if ("room_invite".equals(n.optString("type")) && !(this instanceof GameActivity)) {
            InviteToast.show(this, p);
        } else if (text != null && !(this instanceof GameActivity)) {
            LudoApp.toast(text);
            Sfx.get().play(Sfx.S.MESSAGE, 0.8f, 1f);
        }
        app.prefs.edit().putInt("unread_notifications", app.prefs.getInt("unread_notifications", 0) + 1).apply();
        onNotificationCountChanged();
    }

    protected void onNotificationCountChanged() {}

    /** A standard top bar with a back arrow and a title; returns the content column below it. */
    protected LinearLayout pageWithBack(String title) {
        LinearLayout outer = Ui.vbox(this);
        // A purple 3D header bar with a round back button and the title in gold.
        LinearLayout bar = Ui.hbox(this);
        bar.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 10));
        android.graphics.drawable.GradientDrawable barBg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xff8b5cf6, Fx.BAR});
        float r = Ui.dp(this, 22);
        barBg.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        barBg.setStroke(Ui.dp(this, 2), Fx.BAR_EDGE);
        bar.setBackground(barBg);
        bar.setElevation(Ui.dp(this, 6));
        bar.addView(Fx.badge(this, "◀", Fx.PURPLE, 44, v -> finish()), new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 48)));
        TextView t = Ui.title(this, title);
        t.setTextSize(22);
        t.setPadding(Ui.dp(this, 12), 0, 0, 0);
        bar.addView(t);
        outer.addView(bar, Ui.lpMatchWrap());
        FrameLayout body = new FrameLayout(this);
        outer.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(outer);
        return Ui.scrollPage(this, body);
    }

    /** A 3D popup with content, Cancel and a confirm button. */
    protected Popup dialog(String title, View content, String positive, Runnable onPositive) {
        if (positive == null) return Popup.show(this, title, content, Popup.plain("Close", null));
        return Popup.show(this, title, content, Popup.plain("Cancel", null), Popup.ok(positive, onPositive));
    }

    protected void alert(String title, String message) {
        Popup.message(this, title, message);
    }

    protected View empty(String title, String sub) {
        LinearLayout l = Ui.vbox(this);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 24));
        TextView t = Ui.bold(Ui.text(this, title, 16, Ui.INK));
        t.setGravity(Gravity.CENTER);
        l.addView(t);
        if (sub != null) {
            TextView s = Ui.muted(this, sub);
            s.setGravity(Gravity.CENTER);
            l.addView(s);
        }
        return l;
    }
}
