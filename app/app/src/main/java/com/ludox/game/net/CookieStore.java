package com.ludox.game.net;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

/**
 * Keeps the server's HttpOnly session cookie across app restarts, so a player stays
 * signed in. The same cookie is sent on the Socket.IO handshake (see GameSocket).
 */
public final class CookieStore implements CookieJar {
    private static final String KEY = "cookies";
    private final SharedPreferences prefs;
    private final List<Cookie> cookies = new ArrayList<>();

    public CookieStore(SharedPreferences prefs) {
        this.prefs = prefs;
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                HttpUrl url = HttpUrl.parse(o.getString("url"));
                Cookie c = url == null ? null : Cookie.parse(url, o.getString("cookie"));
                if (c != null) cookies.add(c);
            }
        } catch (Exception ignored) {
            // Corrupt store: start signed out.
        }
    }

    @Override
    public synchronized void saveFromResponse(HttpUrl url, List<Cookie> list) {
        for (Cookie c : list) {
            for (Iterator<Cookie> it = cookies.iterator(); it.hasNext(); ) {
                Cookie old = it.next();
                if (old.name().equals(c.name()) && old.domain().equals(c.domain()) && old.path().equals(c.path())) it.remove();
            }
            // An expired cookie (logout) just deletes the old one.
            if (c.expiresAt() > System.currentTimeMillis()) cookies.add(c);
        }
        persist();
    }

    @Override
    public synchronized List<Cookie> loadForRequest(HttpUrl url) {
        List<Cookie> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Iterator<Cookie> it = cookies.iterator(); it.hasNext(); ) {
            Cookie c = it.next();
            if (c.expiresAt() <= now) it.remove();
            else if (c.matches(url)) out.add(c);
        }
        return out;
    }

    /** "name=value; name2=value2" for a URL, for the WebSocket handshake. */
    public synchronized String headerFor(String url) {
        HttpUrl u = HttpUrl.parse(url);
        if (u == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Cookie c : loadForRequest(u)) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(c.name()).append('=').append(c.value());
        }
        return sb.toString();
    }

    public synchronized void clear() {
        cookies.clear();
        persist();
    }

    private void persist() {
        JSONArray a = new JSONArray();
        try {
            for (Cookie c : cookies) {
                if (!c.persistent()) {
                    // Session-only cookie ("remember me" off): keep it only while the app runs.
                    continue;
                }
                String scheme = c.secure() ? "https" : "http";
                a.put(new JSONObject().put("url", scheme + "://" + c.domain() + c.path()).put("cookie", c.toString()));
            }
        } catch (Exception ignored) {
            // org.json only throws on NaN/null keys; nothing to do.
        }
        prefs.edit().putString(KEY, a.toString()).apply();
    }
}
