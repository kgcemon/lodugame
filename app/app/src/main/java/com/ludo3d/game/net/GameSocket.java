package com.ludo3d.game.net;

import com.ludo3d.game.LudoApp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

import io.socket.client.AckWithTimeout;
import io.socket.client.IO;
import io.socket.client.Socket;

/**
 * The authenticated Socket.IO connection to the game server (same events as the web
 * client, see shared/protocol.ts). The server owns dice, turns, fees and payouts; this
 * class keeps the latest room / game snapshot and forwards events to listeners on the
 * main thread.
 */
public final class GameSocket {
    public interface Listener {
        void onSocketEvent(String event, Object data);
    }

    public interface AckCb {
        /** res is the server's ack: {ok:true,...} or {ok:false,error,code}. */
        void done(JSONObject res);
    }

    public static final String CONNECTION = "connection";

    private final LudoApp app;
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private Socket socket;

    /** Latest room view for this player, or null when not in a room. */
    public JSONObject room;
    /** Latest game update {state, events, deadline, serverTime} for the current room. */
    public JSONObject lastUpdate;
    /** Latest match:settled payload for the current game, or null. */
    public JSONObject settlement;
    public final List<JSONObject> chat = new ArrayList<>();
    public int unreadChat;
    public boolean chatOpen;
    /** "offline" | "connecting" | "online" | "reconnecting" */
    public String connection = "offline";

    public GameSocket(LudoApp app) {
        this.app = app;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public boolean isConnected() {
        return socket != null && socket.connected();
    }

    private void fire(String event, Object data) {
        LudoApp.MAIN.post(() -> {
            for (Listener l : listeners) l.onSocketEvent(event, data);
        });
    }

    private void setConnection(String c) {
        LudoApp.MAIN.post(() -> {
            connection = c;
            for (Listener l : listeners) l.onSocketEvent(CONNECTION, c);
        });
    }

    /** Open the socket after sign-in (no-op when already open). */
    public synchronized void connect() {
        if (socket != null) {
            if (!socket.connected()) socket.connect();
            return;
        }
        String url = app.serverUrl();
        IO.Options opts = IO.Options.builder()
                .setTransports(new String[]{"websocket", "polling"})
                .setReconnection(true)
                .setReconnectionDelay(1000)
                .setReconnectionDelayMax(5000)
                .setExtraHeaders(Collections.singletonMap("Cookie", Collections.singletonList(app.cookies.headerFor(url))))
                .setForceNew(true)
                .build();
        opts.callFactory = app.api.http;
        opts.webSocketFactory = app.api.http;
        final Socket s = IO.socket(URI.create(url), opts);
        socket = s;
        setConnection("connecting");

        s.on(Socket.EVENT_CONNECT, a -> {
            setConnection("online");
            emit("session:hello", new JSONObject(), res -> {
                JSONObject r = res.optJSONObject("room");
                if (r != null) handleRoom(r);
            });
        });
        s.on(Socket.EVENT_DISCONNECT, a -> {
            String reason = a.length > 0 ? String.valueOf(a[0]) : "";
            setConnection("io client disconnect".equals(reason) ? "offline" : "reconnecting");
        });
        s.on(Socket.EVENT_CONNECT_ERROR, a -> {
            setConnection("offline");
            String msg = a.length > 0 && a[0] instanceof Exception ? ((Exception) a[0]).getMessage() : String.valueOf(a.length > 0 ? a[0] : "");
            if (msg != null && msg.contains("unauthorized")) {
                // Session expired or revoked: stop retrying with a dead cookie.
                LudoApp.MAIN.post(this::disconnect);
                fire("unauthorized", null);
            }
        });
        s.on("room:state", a -> handleRoom(obj(a)));
        s.on("game:update", a -> {
            JSONObject u = obj(a);
            LudoApp.MAIN.post(() -> {
                lastUpdate = u;
                for (Listener l : listeners) l.onSocketEvent("game:update", u);
            });
        });
        s.on("room:closed", a -> {
            String reason = a.length > 0 ? String.valueOf(a[0]) : "Room closed";
            LudoApp.MAIN.post(() -> {
                resetRoom();
                for (Listener l : listeners) l.onSocketEvent("room:closed", reason);
            });
        });
        s.on("chat:history", a -> {
            JSONArray arr = a.length > 0 && a[0] instanceof JSONArray ? (JSONArray) a[0] : new JSONArray();
            LudoApp.MAIN.post(() -> {
                chat.clear();
                for (int i = Math.max(0, arr.length() - 100); i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m != null) chat.add(m);
                }
                for (Listener l : listeners) l.onSocketEvent("chat:history", null);
            });
        });
        s.on("chat:message", a -> {
            JSONObject m = obj(a);
            LudoApp.MAIN.post(() -> {
                chat.add(m);
                while (chat.size() > 100) chat.remove(0);
                if (!chatOpen && !m.optString("seatId").equals(mySeatId())) unreadChat++;
                for (Listener l : listeners) l.onSocketEvent("chat:message", m);
            });
        });
        s.on("emote", a -> fire("emote", obj(a)));
        s.on("match:settled", a -> {
            JSONObject m = obj(a);
            LudoApp.MAIN.post(() -> {
                settlement = m;
                for (Listener l : listeners) l.onSocketEvent("match:settled", m);
            });
        });
        s.on("wallet", a -> {
            JSONObject w = obj(a);
            LudoApp.MAIN.post(() -> {
                app.mergeWallet(w);
                for (Listener l : listeners) l.onSocketEvent("wallet", w);
            });
        });
        s.on("notify", a -> fire("notify", obj(a)));
        s.on("friends:changed", a -> fire("friends:changed", null));
        s.on("friend:request", a -> fire("friend:request", obj(a)));
        s.on("friend:accepted", a -> fire("friend:accepted", obj(a)));
        s.on("friend:rejected", a -> fire("friend:rejected", obj(a)));
        s.on("friend:cancelled", a -> fire("friend:cancelled", obj(a)));
        s.connect();
    }

    /** Close the socket (sign-out or server change). */
    public synchronized void disconnect() {
        if (socket != null) {
            socket.off();
            socket.disconnect();
            socket = null;
        }
        resetRoom();
        connection = "offline";
    }

    private void handleRoom(JSONObject r) {
        LudoApp.MAIN.post(() -> {
            JSONObject prev = room;
            room = r;
            if (prev == null || !prev.optString("code").equals(r.optString("code"))) {
                chat.clear();
                unreadChat = 0;
            }
            if ("lobby".equals(r.optString("status"))) {
                lastUpdate = null;
                settlement = null;
            }
            for (Listener l : listeners) l.onSocketEvent("room:state", r);
        });
    }

    private void resetRoom() {
        room = null;
        lastUpdate = null;
        settlement = null;
        chat.clear();
        unreadChat = 0;
        chatOpen = false;
    }

    public JSONObject mySeat() {
        if (room == null) return null;
        JSONArray seats = room.optJSONArray("seats");
        int you = room.optInt("you", -1);
        return seats != null && you >= 0 && you < seats.length() ? seats.optJSONObject(you) : null;
    }

    public String mySeatId() {
        JSONObject s = mySeat();
        return s != null ? s.optString("id") : "";
    }

    public String myColor() {
        JSONObject s = mySeat();
        return s != null ? s.optString("color", "red") : "red";
    }

    private static JSONObject obj(Object[] a) {
        return a.length > 0 && a[0] instanceof JSONObject ? (JSONObject) a[0] : new JSONObject();
    }

    /** Send an event with an ack (10 s timeout). The callback runs on the main thread. */
    public void emit(String event, JSONObject payload, AckCb cb) {
        Socket s = socket;
        if (s == null || !s.connected()) {
            if (cb != null) LudoApp.MAIN.post(() -> cb.done(fail("Not connected to the game server")));
            return;
        }
        s.emit(event, new Object[]{payload}, new AckWithTimeout(10_000) {
            @Override
            public void onSuccess(Object... args) {
                JSONObject res = args.length > 0 && args[0] instanceof JSONObject ? (JSONObject) args[0] : fail("Bad server reply");
                if (cb != null) LudoApp.MAIN.post(() -> cb.done(res));
            }

            @Override
            public void onTimeout() {
                if (cb != null) LudoApp.MAIN.post(() -> cb.done(fail("The server did not respond")));
            }
        });
    }

    /** Like emit(), but shows the server's error as a toast. */
    public void act(String event, JSONObject payload) {
        emit(event, payload, res -> {
            if (!res.optBoolean("ok")) LudoApp.toast(res.optString("error", "Something went wrong"));
        });
    }

    public static JSONObject fail(String error) {
        try {
            return new JSONObject().put("ok", false).put("error", error);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Leave the current room (refund rules are the server's). */
    public void leave(AckCb cb) {
        emit("room:leave", new JSONObject(), res -> {
            resetRoom();
            for (Listener l : listeners) l.onSocketEvent("room:left", null);
            if (cb != null) cb.done(res);
        });
    }
}
