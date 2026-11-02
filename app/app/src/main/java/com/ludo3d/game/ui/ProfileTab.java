package com.ludo3d.game.ui;

import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.audio.Sfx;
import com.ludo3d.game.net.Api;

import org.json.JSONObject;

/** Profile: edit name / username / avatar colour, wallet & history links, sign out. */
final class ProfileTab {
    private final MainActivity a;
    private final LudoApp app;
    private final LinearLayout col;

    ProfileTab(MainActivity a, FrameLayout parent) {
        this.a = a;
        this.app = LudoApp.get();
        this.col = Ui.scrollPage(a, parent);
    }

    void render() {
        JSONObject u = app.user;
        LinearLayout head = Ui.vbox(a);
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView av = Ui.avatar(a, u.optString("username"), u.optInt("avatarHue", 210), 84);
        head.addView(av);
        TextView name = Ui.title(a, u.optString("fullName"));
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, Ui.dp(a, 10), 0, 0);
        head.addView(name);
        TextView sub = Ui.muted(a, "@" + u.optString("username") + " · ID " + u.optString("publicId"));
        sub.setGravity(Gravity.CENTER);
        head.addView(sub);
        TextView mail = Ui.muted(a, u.optString("email") + (u.optBoolean("emailVerified") ? " ✓" : " (not verified)"));
        mail.setGravity(Gravity.CENTER);
        head.addView(mail);
        Ui.margins(head, a, 0, 4, 0, 12);
        col.addView(head);

        LinearLayout links = Ui.card(a);
        links.addView(link("🪙  Wallet & transactions", () -> a.open(WalletActivity.class).onClick(null)));
        links.addView(link("📜  Match history", () -> a.open(HistoryActivity.class).onClick(null)));
        links.addView(link("🔔  Notifications", () -> a.open(NotificationsActivity.class).onClick(null)));
        links.addView(link("🌐  My public profile", () -> a.startActivity(new android.content.Intent(a, PlayerActivity.class).putExtra("publicId", u.optString("publicId")))));
        col.addView(links);

        col.addView(Ui.heading(a, "Edit profile"));
        LinearLayout edit = Ui.card(a);
        edit.addView(Ui.label(a, "Full name"));
        EditText fullName = Ui.input(a, "Full name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        fullName.setText(u.optString("fullName"));
        edit.addView(fullName);
        edit.addView(Ui.label(a, "Username (letters, numbers, _ · 3–20)"));
        EditText username = Ui.input(a, "Username", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        username.setText(u.optString("username"));
        edit.addView(username);
        edit.addView(Ui.label(a, "Avatar colour"));
        final int[] hue = {u.optInt("avatarHue", 210)};
        SeekBar bar = new SeekBar(a);
        bar.setMax(359);
        bar.setProgress(hue[0]);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                hue[0] = p;
                ((android.graphics.drawable.GradientDrawable) av.getBackground()).setColor(Color.HSVToColor(new float[]{p, 0.62f, 0.78f}));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        edit.addView(bar);
        TextView save = Ui.button(a, "Save changes", "primary", v -> {
            JSONObject body = new JSONObject();
            try {
                body.put("fullName", fullName.getText().toString().trim())
                        .put("username", username.getText().toString().trim())
                        .put("avatarHue", hue[0]);
            } catch (Exception ignored) {
                return;
            }
            app.api.patch("/profile", body, new Api.Cb(a) {
                @Override
                public void ok(JSONObject r) {
                    reloadUser("Profile saved");
                }
            });
        });
        Ui.margins(save, a, 0, 12, 0, 0);
        edit.addView(save);
        col.addView(edit);

        col.addView(Ui.heading(a, "Settings"));
        LinearLayout prefsCard = Ui.card(a);
        android.widget.Switch sound = new android.widget.Switch(a);
        sound.setText("🔊  Sound effects");
        sound.setTextColor(Ui.INK);
        sound.setTextSize(15);
        sound.setChecked(Sfx.get().isEnabled());
        sound.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 8));
        sound.setOnCheckedChangeListener((b, on) -> {
            Sfx.get().setEnabled(on);
            Sfx.get().play(Sfx.S.CLICK);
        });
        prefsCard.addView(sound);
        col.addView(prefsCard);

        col.addView(Ui.heading(a, "Security"));
        LinearLayout sec = Ui.card(a);
        if (!u.optBoolean("emailVerified")) {
            sec.addView(link("✉️  Resend verification email", () -> app.api.post("/auth/resend-verification", null, new Api.Cb(a) {
                @Override
                public void ok(JSONObject r) {
                    LudoApp.toast("Verification email sent");
                }
            })));
        }
        sec.addView(link("🚪  Sign out", a::signOut));
        col.addView(sec);

        TextView server = Ui.muted(a, "LudoX " + com.ludo3d.game.BuildConfig.VERSION_NAME);
        server.setGravity(Gravity.CENTER);
        server.setPadding(0, Ui.dp(a, 12), 0, 0);
        col.addView(server);
    }

    private TextView link(String text, Runnable r) {
        TextView t = Ui.text(a, text, 15, Ui.INK);
        t.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 12));
        t.setOnClickListener(v -> r.run());
        return t;
    }

    private void reloadUser(String message) {
        app.api.get("/auth/me", new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                JSONObject user = r.optJSONObject("user");
                if (user != null) app.user = user;
                LudoApp.toast(message);
                a.recreate();
            }
        });
    }
}
