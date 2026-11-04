package com.ludox.game.net;

import com.ludox.game.LudoApp;

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
    /** When this phone lost the connection (elapsedRealtime, counts while asleep), 0 once the server has the match back. */
    public volatile long offlineAt;
    /** Connected again, waiting for the server to say whether the match is still mine. */
    public volatile boolean restoring;
    /** A result from the server that no screen has shown yet (see BaseActivity). */
    public JSONObject pendingResult;
    /** The match type "Play again" asked for, for the next screen to open. */
    public JSONObject playAgain;
    /** The coins the ledger last credited me for winning this room's match. */
    public JSONObject matchReward;
    /** The confirmed match's Match Found introduction while it plays, and when it ends on this phone's clock. */
    private JSONObject intro;
    public long introEndsAt;

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
            restoring = true;
            setConnection("online");
            sayHello(s);
        });
        s.on(Socket.EVENT_DISCONNECT, a -> {
            String reason = a.length > 0 ? String.valueOf(a[0]) : "";
            markOffline();
            setConnection("io client disconnect".equals(reason) ? "offline" : "reconnecting");
        });
        s.on(Socket.EVENT_CONNECT_ERROR, a -> {
            markOffline();
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
        s.on("reward", a -> {
            JSONObject r = obj(a);
            LudoApp.MAIN.post(() -> {
                if ("match_win".equals(r.optString("source"))) matchReward = r;
                for (Listener l : listeners) l.onSocketEvent("reward", r);
            });
        });
        s.on("gift:new", a -> fire("gift:new", obj(a)));
        s.on("gift:update", a -> fire("gift:update", obj(a)));
        s.on("session:replaced", a -> LudoApp.MAIN.post(() -> {
            // This account opened the match on a newer connection (another phone).
            resetRoom();
            for (Listener l : listeners) l.onSocketEvent("session:replaced", null);
        }));
        s.on("friends:changed", a -> {
            // A new friend in my room can talk with me straight away (mid-match too).
            if (room != null) com.ludox.game.audio.VoiceChat.get().refreshFriends();
            fire("friends:changed", null);
        });
        s.on("friend:request", a -> fire("friend:request", obj(a)));
        s.on("friend:accepted", a -> {
            if (room != null) com.ludox.game.audio.VoiceChat.get().refreshFriends();
            fire("friend:accepted", obj(a));
        });
        s.on("friend:rejected", a -> fire("friend:rejected", obj(a)));
        s.on("friend:cancelled", a -> fire("friend:cancelled", obj(a)));
        s.on("friend:online", a -> fire("friend:online", obj(a)));
        // A friend's voice: straight to the voice engine (it has its own threads).
        s.on("voice:frame", a -> {
            if (a.length >= 2 && a[1] instanceof byte[]) com.ludox.game.audio.VoiceChat.get().onPacket(String.valueOf(a[0]), (byte[]) a[1]);
        });
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
        offlineAt = 0;
        restoring = false;
    }

    /**
     * The server decides what I get back: my seat, or the result of a match I'm out of. Until it
     * has answered my seat is still counting down, so an unanswered hello is sent again.
     */
    private void sayHello(Socket s) {
        emit("session:hello", helloPayload(), res -> {
            if (!res.optBoolean("ok")) {
                if (s == socket && s.connected()) LudoApp.MAIN.postDelayed(() -> {
                    if (s == socket && s.connected()) sayHello(s);
                }, 1000);
                return;
            }
            restoring = false;
            offlineAt = 0;
            JSONObject r = res.optJSONObject("room");
            JSONObject result = res.optJSONObject("result");
            if (r != null) {
                handleRoom(r);
                // Friend changes sent while the connection was down were missed.
                com.ludox.game.audio.VoiceChat.get().refreshFriends();
                return;
            }
            boolean hadRoom = room != null;
            if (hadRoom) resetRoom();
            if (result != null) {
                pendingResult = result;
                for (Listener l : listeners) l.onSocketEvent("match:result", result);
            } else if (hadRoom) {
                for (Listener l : listeners) l.onSocketEvent("room:gone", null);
            }
        });
    }

    private void markOffline() {
        restoring = false;
        if (offlineAt == 0) offlineAt = android.os.SystemClock.elapsedRealtime();
    }

    private static JSONObject helloPayload() {
        try {
            return new JSONObject().put("results", true);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void handleRoom(JSONObject r) {
        LudoApp.MAIN.post(() -> {
            JSONObject prev = room;
            room = r;
            trackIntro(r);
            if (prev == null || !prev.optString("code").equals(r.optString("code"))) {
                chat.clear();
                unreadChat = 0;
                com.ludox.game.audio.VoiceChat.get().refreshFriends();
            }
            com.ludox.game.audio.VoiceChat.get().syncRoom();
            if ("lobby".equals(r.optString("status"))) {
                lastUpdate = null;
                settlement = null;
                matchReward = null;
            }
            for (Listener l : listeners) l.onSocketEvent("room:state", r);
        });
    }

    /** Timed from when it first reached this phone: later room updates about the same match don't restart it. */
    private void trackIntro(JSONObject r) {
        JSONObject next = r.optJSONObject("intro");
        if (next == null) {
            intro = null;
            introEndsAt = 0;
        } else if (intro == null || !intro.optString("matchId").equals(next.optString("matchId"))) {
            intro = next;
            introEndsAt = android.os.SystemClock.elapsedRealtime() + next.optLong("msLeft");
        }
    }

    /** The introduction to show right now, or null. */
    public JSONObject activeIntro() {
        return intro != null && android.os.SystemClock.elapsedRealtime() < introEndsAt ? intro : null;
    }

    private void resetRoom() {
        com.ludox.game.audio.VoiceChat.get().stopAll();
        room = null;
        intro = null;
        introEndsAt = 0;
        lastUpdate = null;
        settlement = null;
        matchReward = null;
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

    /** Fire-and-forget event (binary allowed, no ack), e.g. voice packets. Safe from any thread. */
    public void emitRaw(String event, Object... args) {
        Socket s = socket;
        if (s != null && s.connected()) s.emit(event, args);
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
        emit("room:leave", helloPayload(), res -> {
            resetRoom();
            for (Listener l : listeners) l.onSocketEvent("room:left", null);
            if (cb != null) cb.done(res);
        });
    }

    /**
     * Forfeit the running match. The room is only let go once the server has confirmed it: if the
     * reply never comes, the next session:hello brings the result (asking twice is harmless).
     */
    public void leaveMatch(AckCb cb) {
        emit("room:leave", helloPayload(), res -> {
            if (res.optBoolean("ok")) resetRoom();
            cb.done(res);
        });
    }

    /** The result screen has been shown: the server need not offer it again. */
    public void resultSeen(String matchId) {
        try {
            emit("match:resultSeen", new JSONObject().put("matchId", matchId), null);
        } catch (Exception ignored) {
            // not reachable
        }
    }
}
