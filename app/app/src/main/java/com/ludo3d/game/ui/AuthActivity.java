package com.ludo3d.game.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.ludo3d.game.BuildConfig;
import com.ludo3d.game.LudoApp;
import com.ludo3d.game.R;
import com.ludo3d.game.net.Api;

import org.json.JSONObject;

/**
 * Sign in with Google — the only way into online play. Google's account picker (Credential
 * Manager) hands back an ID token, which the server checks with Google before it opens (or
 * creates) the player's account. Offline pass-and-play needs no account.
 */
public class AuthActivity extends BaseActivity {
    private LinearLayout col;
    private TextView googleLabel;
    private View googleButton;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root.setBackground(Fx.background(this));
        col = Ui.scrollPage(this, root);
        render();
    }

    private void render() {
        col.removeAllViews();
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Ui.dp(this, 24), Ui.dp(this, 48), Ui.dp(this, 24), Ui.dp(this, 24));

        // The artwork is a rounded tile inside a dark square: cut the tile out with rounded
        // corners (a rounded bitmap, which also works where views draw in software).
        ImageView logo = new ImageView(this);
        android.graphics.Bitmap full = android.graphics.BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher_foreground);
        // Where the tile starts: the first opaque pixel along the middle row (the icon is
        // square and centred), plus a hair to lose the anti-aliased rim.
        int inset = 0, mid = full.getHeight() / 2;
        while (inset < full.getWidth() / 3 && android.graphics.Color.alpha(full.getPixel(inset, mid)) < 200) inset++;
        inset += Math.round(full.getWidth() * 0.01f);
        android.graphics.Bitmap tile = android.graphics.Bitmap.createBitmap(full, inset, inset, full.getWidth() - 2 * inset, full.getHeight() - 2 * inset);
        androidx.core.graphics.drawable.RoundedBitmapDrawable rounded =
                androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(getResources(), tile);
        rounded.setCornerRadius(tile.getWidth() * 0.24f);
        rounded.setAntiAlias(true);
        logo.setImageDrawable(rounded);
        // Developers only: long-press the logo in a debug build to point the app at a test server.
        if (BuildConfig.DEBUG) {
            logo.setOnLongClickListener(v -> {
                editServer();
                return true;
            });
        }
        col.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 210), Ui.dp(this, 210)));

        TextView sub = Ui.muted(this, "Sign in with your Google account to play online.\nYour coins, diamonds and dice stay safe on any phone.");
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 28));
        col.addView(sub, Ui.lpMatchWrap());

        googleButton = googleButton();
        col.addView(googleButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)));

        TextView terms = Ui.muted(this, "New players get free welcome coins.");
        terms.setGravity(Gravity.CENTER);
        terms.setPadding(0, Ui.dp(this, 10), 0, 0);
        col.addView(terms, Ui.lpMatchWrap());

        // No account or no internet needed to pass one phone around.
        TextView offline = Ui.button(this, "📱 Play offline", "ok", v -> OfflineSetup.show(this));
        LinearLayout.LayoutParams olp = Ui.lpMatchWrap();
        olp.topMargin = Ui.dp(this, 36);
        col.addView(offline, olp);
    }

    /** Google's own look: a white pill with the colour "G" and dark text. */
    private View googleButton() {
        LinearLayout b = Ui.hbox(this);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.roundStroke(0xffffffff, Ui.dp(this, 28), Ui.dp(this, 1), 0xffdadce0));
        b.setElevation(Ui.dp(this, 4));
        ImageView g = new ImageView(this);
        g.setImageResource(R.drawable.ic_google_g);
        b.addView(g, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        googleLabel = Ui.bold(Ui.text(this, "Sign in with Google", 17, 0xff1f1f1f));
        googleLabel.setPadding(Ui.dp(this, 12), 0, 0, 0);
        b.addView(googleLabel);
        b.setClickable(true);
        b.setFocusable(true);
        b.setContentDescription("Sign in with Google");
        b.setOnClickListener(v -> signIn());
        return b;
    }

    private void setBusy(boolean on) {
        busy = on;
        googleLabel.setText(on ? "Signing in…" : "Sign in with Google");
        googleButton.setAlpha(on ? 0.7f : 1f);
    }

    private void signIn() {
        if (busy) return;
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isEmpty()) {
            alert("Google sign-in isn't set up yet", "This build has no Google client id. Add it in app/app/build.gradle (GOOGLE_WEB_CLIENT_ID).");
            return;
        }
        setBusy(true);
        GetSignInWithGoogleOption google = new GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder().addCredentialOption(google).build();
        CredentialManager.create(this).getCredentialAsync(this, request, new CancellationSignal(), ContextCompat.getMainExecutor(this),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        Credential c = response.getCredential();
                        if (c instanceof CustomCredential && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(c.getType())) {
                            try {
                                sendToServer(GoogleIdTokenCredential.createFrom(c.getData()).getIdToken());
                                return;
                            } catch (Exception e) {
                                android.util.Log.w("Auth", "bad Google credential", e);
                            }
                        }
                        setBusy(false);
                        alert("Couldn't sign in", "Google didn't return an account. Please try again.");
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        setBusy(false);
                        // Closing the account picker is not an error.
                        if (e instanceof GetCredentialCancellationException) return;
                        android.util.Log.w("Auth", "Google sign-in failed", e);
                        alert("Couldn't sign in", e instanceof NoCredentialException
                                ? "Add a Google account to this phone (Settings → Accounts), then try again."
                                : "Google sign-in didn't work. Check your connection and try again.");
                    }
                });
    }

    private void sendToServer(String idToken) {
        JSONObject body = PlayFlow.obj("idToken", idToken);
        app.api.post("/auth/google", body, new Api.Cb(this) {
            @Override
            public void ok(JSONObject r) {
                if (r.optBoolean("created") && r.optLong("welcomeBonus") > 0) {
                    LudoApp.toast("Welcome! You received " + Ui.fmt(r.optLong("welcomeBonus")) + " coins");
                }
                Intent i = new Intent(AuthActivity.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
            }

            @Override
            public void err(Api.Error e) {
                setBusy(false);
                alert("Couldn't sign in", e.message);
            }
        });
    }

    private void editServer() {
        LinearLayout box = Ui.vbox(this);
        box.addView(Ui.muted(this, "Address of the LudoX game server. Emulator + npm run dev on your PC: http://10.0.2.2:3001"));
        EditText url = Ui.input(this, "https://ludo.your-domain.example", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(app.serverUrl());
        Ui.margins(url, this, 0, 12, 0, 0);
        box.addView(url);
        dialog("Game server", box, "Save", () -> {
            String u = url.getText().toString().trim();
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                LudoApp.toast("The address must start with http:// or https://");
                return;
            }
            app.setServerUrl(u);
            render();
        });
    }
}
