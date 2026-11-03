package com.ludox.game.net;

import android.app.Activity;

import com.ludox.game.LudoApp;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * REST client for the game server's /api routes. The session travels in the cookie;
 * every request carries the header the server requires for state-changing calls.
 * Callbacks run on the main thread and are dropped if their activity is gone.
 */
public final class Api {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public final OkHttpClient http;
    private final LudoApp app;

    public Api(LudoApp app) {
        this.app = app;
        this.http = new OkHttpClient.Builder()
                .cookieJar(app.cookies)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    public static final class Error {
        public final int status;
        public final String message;
        public final String code;

        Error(int status, String message, String code) {
            this.status = status;
            this.message = message;
            this.code = code;
        }
    }

    /** Response handler. Override err() to handle failures yourself (default: a toast). */
    public abstract static class Cb {
        final Activity owner;

        protected Cb(Activity owner) {
            this.owner = owner;
        }

        public abstract void ok(JSONObject r);

        public void err(Error e) {
            LudoApp.toast(e.message);
        }

        public void done() {}
    }

    public void get(String path, Cb cb) {
        send("GET", path, null, cb);
    }

    public void post(String path, JSONObject body, Cb cb) {
        send("POST", path, body == null ? new JSONObject() : body, cb);
    }

    public void patch(String path, JSONObject body, Cb cb) {
        send("PATCH", path, body, cb);
    }

    public void delete(String path, Cb cb) {
        send("DELETE", path, null, cb);
    }

    public void send(String method, String path, JSONObject body, Cb cb) {
        Request.Builder rb = new Request.Builder()
                .url(app.serverUrl() + "/api" + path)
                .header("x-requested-with", "ludo3d")
                .header("accept", "application/json");
        RequestBody rbody = body == null ? (method.equals("GET") ? null : RequestBody.create(new byte[0], null)) : RequestBody.create(body.toString(), JSON);
        rb.method(method, rbody);
        http.newCall(rb.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                deliver(cb, null, new Error(0, "Cannot reach the server. Check your connection.", null));
            }

            @Override
            public void onResponse(Call call, Response res) {
                JSONObject data = new JSONObject();
                try (ResponseBody b = res.body()) {
                    String text = b != null ? b.string() : "";
                    if (!text.isEmpty()) data = new JSONObject(text);
                } catch (Exception ignored) {
                    // Non-JSON error page.
                }
                if (!res.isSuccessful() || !data.optBoolean("ok", true)) {
                    String msg = data.optString("error", "");
                    if (msg.isEmpty()) msg = "Request failed (" + res.code() + ")";
                    deliver(cb, null, new Error(res.code(), msg, data.has("code") ? data.optString("code") : null));
                } else {
                    deliver(cb, data, null);
                }
            }
        });
    }

    private static void deliver(Cb cb, JSONObject data, Error e) {
        LudoApp.MAIN.post(() -> {
            if (cb.owner != null && (cb.owner.isFinishing() || cb.owner.isDestroyed())) return;
            try {
                if (e != null) cb.err(e);
                else cb.ok(data);
            } finally {
                cb.done();
            }
        });
    }
}
