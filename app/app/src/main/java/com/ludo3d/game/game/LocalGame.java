package com.ludo3d.game.game;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * An offline pass-and-play match on one phone, ported from shared/engine.ts with the
 * default rules. Every action returns an update shaped like the server's game:update
 * ({state, events}) so GameActivity animates it exactly like an online match.
 */
public final class LocalGame {
    private static final int MAX_SIXES = 3;
    private static final SecureRandom RNG = new SecureRandom();

    private final String[] colors;
    private final String[] names;
    private final int[][] tokens;
    private final int[] rank;
    private final List<Integer> ranking = new ArrayList<>();
    private int current;
    private String phase = "roll";
    private int dice;
    private int sixStreak;
    private int turnNo = 1;
    private int seq;

    /** Seat colours by player count, keeping opponents across from each other (engine.colorsForCount). */
    public static String[] colorsForCount(int count) {
        if (count == 2) return new String[]{"red", "yellow"};
        if (count == 3) return new String[]{"red", "green", "yellow"};
        return Board.COLORS.clone();
    }

    public LocalGame(String[] names) {
        int n = Math.max(2, Math.min(4, names.length));
        this.colors = colorsForCount(n);
        this.names = new String[n];
        System.arraycopy(names, 0, this.names, 0, n);
        this.tokens = new int[n][4];
        for (int[] t : tokens) java.util.Arrays.fill(t, -1);
        this.rank = new int[n];
    }

    public String[] names() {
        return names.clone();
    }

    public boolean isOver() {
        return "over".equals(phase);
    }

    /** The opening update (no events, so the board snaps to it). */
    public JSONObject snapshot() {
        return update(new JSONArray());
    }

    /** Roll for the current player; null if it isn't time to roll. */
    public JSONObject roll() {
        if (!"roll".equals(phase)) return null;
        JSONArray events = new JSONArray();
        int pi = current;
        int v = 1 + RNG.nextInt(6);
        events.put(ev("rolled", "player", pi, "value", v));
        dice = v;
        sixStreak = v == 6 ? sixStreak + 1 : 0;
        if (v == 6 && sixStreak >= MAX_SIXES) {
            events.put(ev("forfeitSixes", "player", pi));
            passTurn(events);
        } else if (movable(v).isEmpty()) {
            events.put(ev("noMoves", "player", pi));
            if (v == 6) {
                dice = 0;
                phase = "roll";
                events.put(ev("extraTurn", "player", pi, "reason", "six"));
            } else {
                passTurn(events);
            }
        } else {
            phase = "move";
        }
        seq++;
        return update(events);
    }

    /** Move a token of the current player; null if that move isn't allowed. */
    public JSONObject move(int ti) {
        if (!"move".equals(phase) || dice == 0 || ti < 0 || ti > 3) return null;
        int pi = current;
        int from = tokens[pi][ti];
        int to = targetOf(from, dice);
        if (to < 0) return null;
        JSONArray events = new JSONArray();

        // A lone opponent token on the landing square goes home, unless it is a safe square.
        // Two or more of one player's tokens there make a pair (jora) that can't be captured.
        List<int[]> victims = new ArrayList<>();
        int at = to < Board.HOME_START ? Board.absIndex(ci(pi), to) : -1;
        if (at >= 0 && !Board.isSafe(at)) {
            for (int oi = 0; oi < colors.length; oi++) {
                if (oi == pi) continue;
                int found = -1, count = 0;
                for (int k = 0; k < 4; k++) {
                    if (Board.absIndex(ci(oi), tokens[oi][k]) == at) {
                        found = k;
                        count++;
                    }
                }
                if (count == 1) victims.add(new int[]{oi, found});
            }
        }
        tokens[pi][ti] = to;
        events.put(ev("moved", "player", pi, "token", ti, "from", from, "to", to));
        for (int[] v : victims) {
            int vf = tokens[v[0]][v[1]];
            tokens[v[0]][v[1]] = -1;
            events.put(ev("captured", "player", v[0], "token", v[1], "by", pi, "from", vf));
        }
        boolean reachedHome = to == Board.FINISH;
        if (reachedHome) events.put(ev("tokenHome", "player", pi, "token", ti));

        boolean allHome = true;
        for (int t : tokens[pi]) allHome &= t == Board.FINISH;
        if (allHome) {
            // First one home wins (playUntil: 'first').
            ranking.add(pi);
            rank[pi] = ranking.size();
            events.put(ev("playerFinished", "player", pi, "rank", rank[pi]));
            finishGame(events);
            seq++;
            return update(events);
        }

        String reason = !victims.isEmpty() ? "capture" : reachedHome ? "home" : dice == 6 ? "six" : null;
        if (reason != null) {
            dice = 0;
            phase = "roll";
            events.put(ev("extraTurn", "player", pi, "reason", reason));
        } else {
            passTurn(events);
        }
        seq++;
        return update(events);
    }

    private int ci(int player) {
        return Board.colorIndex(colors[player]);
    }

    private static int targetOf(int progress, int roll) {
        if (progress == Board.FINISH) return -2;
        if (progress < 0) return roll == 6 ? 0 : -2;
        int to = progress + roll;
        return to <= Board.FINISH ? to : -2;
    }

    private List<Integer> movable(int roll) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) if (targetOf(tokens[current][i], roll) >= 0) out.add(i);
        return out;
    }

    private void passTurn(JSONArray events) {
        sixStreak = 0;
        dice = 0;
        phase = "roll";
        int n = colors.length, next = current;
        for (int i = 0; i < n; i++) {
            next = (next + 1) % n;
            if (rank[next] == 0) break;
        }
        current = next;
        turnNo++;
        events.put(ev("turn", "player", next));
    }

    private void finishGame(JSONArray events) {
        // Rank everyone still playing by how far their tokens have travelled.
        List<int[]> rest = new ArrayList<>();
        for (int i = 0; i < colors.length; i++) {
            if (rank[i] != 0) continue;
            int score = 0;
            for (int t : tokens[i]) score += Math.max(t, 0) + (t >= 0 ? 1 : 0);
            rest.add(new int[]{i, score});
        }
        rest.sort((a, b) -> b[1] - a[1]);
        for (int[] r : rest) {
            ranking.add(r[0]);
            rank[r[0]] = ranking.size();
        }
        phase = "over";
        dice = 0;
        JSONArray order = new JSONArray();
        for (int i : ranking) order.put(i);
        events.put(ev("gameOver", "ranking", order));
    }

    private JSONObject update(JSONArray events) {
        try {
            JSONArray players = new JSONArray();
            for (int i = 0; i < colors.length; i++) {
                JSONArray t = new JSONArray();
                for (int v : tokens[i]) t.put(v);
                players.put(new JSONObject().put("color", colors[i]).put("name", names[i]).put("kind", "human")
                        .put("tokens", t).put("rank", rank[i] == 0 ? JSONObject.NULL : rank[i]));
            }
            JSONArray r = new JSONArray();
            for (int i : ranking) r.put(i);
            JSONObject state = new JSONObject()
                    .put("config", new JSONObject().put("enterValues", new JSONArray().put(6)))
                    .put("players", players)
                    .put("current", current)
                    .put("phase", phase)
                    .put("dice", dice == 0 ? JSONObject.NULL : dice)
                    .put("sixStreak", sixStreak)
                    .put("ranking", r)
                    .put("turnNo", turnNo)
                    .put("seq", seq);
            return new JSONObject().put("state", state).put("events", events).put("deadline", JSONObject.NULL);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject ev(String type, Object... kv) {
        try {
            JSONObject o = new JSONObject().put("type", type);
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
            return o;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
