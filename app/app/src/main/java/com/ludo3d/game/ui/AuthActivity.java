package com.ludo3d.game.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludo3d.game.net.Api;

import org.json.JSONObject;

/** Sign in, create an account, or request a password reset (the reset link opens the website). */
public class AuthActivity extends BaseActivity {
    private LinearLayout col;
    private String mode = "login"; // login | register | forgot

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        col = Ui.scrollPage(this, root);
        render();
    }

    private void render() {
        col.removeAllViews();
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView logo = Ui.text(this, "🎲", 54, Ui.INK);
        logo.setGravity(Gravity.CENTER);
        logo.setPadding(0, Ui.dp(this, 24), 0, 0);
        // Developers only: long-press the logo in a debug build to point the app at a test server.
        if (com.ludo3d.game.BuildConfig.DEBUG) {
            logo.setOnLongClickListener(v -> {
                editServer();
                return true;
            });
        }
        col.addView(logo);
        TextView name = Ui.title(this, "Ludo 3D");
        name.setGravity(Gravity.CENTER);
        col.addView(name);
        TextView sub = Ui.muted(this, mode.equals("register") ? "Create your account and get free welcome coins"
                : mode.equals("forgot") ? "We'll email you a reset link" : "Welcome back! Sign in to play");
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 20));
        col.addView(sub);

        LinearLayout card = Ui.card(this);
        col.addView(card);
        TextView error = Ui.text(this, "", 14, Ui.DANGER);
        error.setVisibility(android.view.View.GONE);

        EditText fullName = Ui.input(this, "Full name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        EditText email = Ui.input(this, "Email", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText pass = Ui.input(this, "Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText confirm = Ui.input(this, "Confirm password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox remember = new CheckBox(this);
        remember.setText("Keep me signed in");
        remember.setTextColor(Ui.MUTED);
        remember.setChecked(true);

        if (mode.equals("register")) card.addView(fullName);
        card.addView(email);
        if (!mode.equals("forgot")) card.addView(pass);
        if (mode.equals("register")) {
            card.addView(confirm);
            card.addView(Ui.muted(this, "At least 8 characters, with letters and numbers."));
        }
        if (mode.equals("login")) card.addView(remember);
        card.addView(error);

        String label = mode.equals("register") ? "Create account" : mode.equals("forgot") ? "Send reset link" : "Sign in";
        TextView submit = Ui.button(this, label, "primary", null);
        Ui.margins(submit, this, 0, 14, 0, 0);
        card.addView(submit);
        submit.setOnClickListener(v -> {
            error.setVisibility(android.view.View.GONE);
            Ui.setEnabled(submit, false);
            try {
                JSONObject body = new JSONObject().put("email", email.getText().toString().trim());
                String path;
                if (mode.equals("register")) {
                    path = "/auth/register";
                    body.put("fullName", fullName.getText().toString().trim())
                            .put("password", pass.getText().toString())
                            .put("confirmPassword", confirm.getText().toString());
                } else if (mode.equals("forgot")) {
                    path = "/auth/forgot-password";
                } else {
                    path = "/auth/login";
                    body.put("password", pass.getText().toString()).put("remember", remember.isChecked());
                }
                app.api.post(path, body, new Api.Cb(this) {
                    @Override
                    public void ok(JSONObject r) {
                        if (mode.equals("forgot")) {
                            alert("Check your email", r.optString("message", "If an account exists for that email, a reset link is on its way."));
                            mode = "login";
                            render();
                            return;
                        }
                        if (mode.equals("register") && r.optLong("welcomeBonus") > 0) {
                            com.ludo3d.game.LudoApp.toast("Welcome! You received " + Ui.fmt(r.optLong("welcomeBonus")) + " coins");
                        }
                        Intent i = new Intent(AuthActivity.this, MainActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                    }

                    @Override
                    public void err(Api.Error e) {
                        error.setText(e.message);
                        error.setVisibility(android.view.View.VISIBLE);
                    }

                    @Override
                    public void done() {
                        Ui.setEnabled(submit, true);
                    }
                });
            } catch (Exception ignored) {
                Ui.setEnabled(submit, true);
            }
        });

        LinearLayout links = Ui.vbox(this);
        links.setGravity(Gravity.CENTER_HORIZONTAL);
        links.setPadding(0, Ui.dp(this, 8), 0, 0);
        if (mode.equals("login")) {
            links.addView(link("No account yet? Create one", "register"));
            links.addView(link("Forgot password?", "forgot"));
        } else {
            links.addView(link("Already have an account? Sign in", "login"));
        }
        col.addView(links);
    }

    private TextView link(String text, String target) {
        TextView t = Ui.text(this, text, 14, Ui.GOLD);
        t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        t.setOnClickListener(v -> {
            mode = target;
            render();
        });
        return t;
    }

    private void editServer() {
        LinearLayout box = Ui.vbox(this);
        box.addView(Ui.muted(this, "Address of the Ludo 3D game server. Emulator + npm run dev on your PC: http://10.0.2.2:3001"));
        EditText url = Ui.input(this, "https://ludo.your-domain.example", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(app.serverUrl());
        Ui.margins(url, this, 0, 12, 0, 0);
        box.addView(url);
        dialog("Game server", box, "Save", () -> {
            String u = url.getText().toString().trim();
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                com.ludo3d.game.LudoApp.toast("The address must start with http:// or https://");
                return;
            }
            app.setServerUrl(u);
            render();
        });
    }
}
