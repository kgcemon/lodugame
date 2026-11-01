package com.ludo3d.game;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.ludo3d.game.net.Api;
import com.ludo3d.game.net.CookieStore;
import com.ludo3d.game.net.GameSocket;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * App-wide singletons: the REST client, the game socket and the signed-in session
 * (user, wallet, public config). Everything else reads them from here.
 */
public class LudoApp extends Application {
    private static LudoApp instance;

    public static final Handler MAIN = new Handler(Looper.getMainLooper());

    public SharedPreferences prefs;
    public CookieStore cookies;
    public Api api;
    public GameSocket socket;

    /** Signed-in user (/auth/me "user"), or null. */
    public JSONObject user;
    /** Latest wallet numbers (from /auth/me, /wallet or the socket). */
    public JSONObject wallet = new JSONObject();
    /** Public config (/config): modes, bonuses, payout multiplier. */
    public JSONObject config;
    public boolean requireEmailVerification;

    public static LudoApp get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("ludo", MODE_PRIVATE);
        cookies = new CookieStore(prefs);
        api = new Api(this);
        socket = new GameSocket(this);
    }

    public String serverUrl() {
        // Release builds always use the built-in server; only debug builds can be pointed elsewhere.
        if (!BuildConfig.DEBUG) return BuildConfig.DEFAULT_SERVER_URL.replaceAll("/+$", "");
        String s = prefs.getString("server_url", BuildConfig.DEFAULT_SERVER_URL);
        return s.replaceAll("/+$", "");
    }

    public void setServerUrl(String url) {
        String clean = url.trim().replaceAll("/+$", "");
        if (clean.equals(serverUrl())) return;
        prefs.edit().putString("server_url", clean).apply();
        // Sessions belong to one server.
        cookies.clear();
        socket.disconnect();
        user = null;
    }

    public long balance() {
        return wallet.optLong("balance", 0);
    }

    public double payoutMultiplier() {
        return config != null ? config.optDouble("payoutMultiplier", 2) : 2;
    }

    public JSONArray modes() {
        JSONArray m = config != null ? config.optJSONArray("modes") : null;
        return m != null ? m : new JSONArray();
    }

    /** Merge wallet fields (the socket sends balance/totals without the daily bonus block). */
    public void mergeWallet(JSONObject w) {
        if (w == null) return;
        try {
            JSONObject next = new JSONObject(wallet.toString());
            for (java.util.Iterator<String> it = w.keys(); it.hasNext(); ) {
                String k = it.next();
                next.put(k, w.get(k));
            }
            wallet = next;
        } catch (Exception ignored) {
            wallet = w;
        }
    }

    public static void toast(String text) {
        MAIN.post(() -> Toast.makeText(instance, text, Toast.LENGTH_SHORT).show());
    }
}
