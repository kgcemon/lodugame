package com.ludo3d.game.game;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A parsed server GameState (shared/types.ts). Read-only on the client: the server owns the rules. */
public final class GameState {
    public static final class Player {
        public String color;
        public int ci;
        public String name;
        public String kind;
        public int[] tokens = new int[4];
        public int rank; // 0 = still playing
    }

    public final List<Player> players = new ArrayList<>();
    public int current;
    /** "roll" | "move" | "over" */
    public String phase;
    /** 0 when not rolled. */
    public int dice;
    public int seq;
    public final List<Integer> ranking = new ArrayList<>();
    private final List<Integer> enterValues = new ArrayList<>();

    public static GameState parse(JSONObject o) {
        GameState g = new GameState();
        JSONArray ps = o.optJSONArray("players");
        for (int i = 0; ps != null && i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            Player pl = new Player();
            pl.color = p.optString("color");
            pl.ci = Board.colorIndex(pl.color);
            pl.name = p.optString("name");
            pl.kind = p.optString("kind");
            JSONArray t = p.optJSONArray("tokens");
            for (int k = 0; k < 4; k++) pl.tokens[k] = t != null ? t.optInt(k, -1) : -1;
            pl.rank = p.isNull("rank") ? 0 : p.optInt("rank", 0);
            g.players.add(pl);
        }
        g.current = o.optInt("current");
        g.phase = o.optString("phase", "roll");
        g.dice = o.isNull("dice") ? 0 : o.optInt("dice", 0);
        g.seq = o.optInt("seq");
        JSONArray r = o.optJSONArray("ranking");
        for (int i = 0; r != null && i < r.length(); i++) g.ranking.add(r.optInt(i));
        JSONObject cfg = o.optJSONObject("config");
        JSONArray ev = cfg != null ? cfg.optJSONArray("enterValues") : null;
        for (int i = 0; ev != null && i < ev.length(); i++) g.enterValues.add(ev.optInt(i));
        if (g.enterValues.isEmpty()) g.enterValues.add(6);
        return g;
    }

    public Player currentPlayer() {
        return players.get(current);
    }

    public int indexOfColor(String color) {
        for (int i = 0; i < players.size(); i++) if (players.get(i).color.equals(color)) return i;
        return -1;
    }

    /** Progress a token would reach with this roll, or -2 if it cannot move (engine.targetOf). */
    public int targetOf(int progress, int roll) {
        if (progress == Board.FINISH) return -2;
        if (progress < 0) return enterValues.contains(roll) ? 0 : -2;
        int to = progress + roll;
        return to <= Board.FINISH ? to : -2;
    }

    /** Tokens of the current player that can move with the rolled dice. */
    public List<Integer> movableTokens() {
        List<Integer> out = new ArrayList<>();
        if (!"move".equals(phase) || dice == 0) return out;
        int[] t = currentPlayer().tokens;
        for (int i = 0; i < 4; i++) if (targetOf(t[i], dice) != -2) out.add(i);
        return out;
    }
}
