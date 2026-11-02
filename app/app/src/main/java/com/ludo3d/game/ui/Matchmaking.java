package com.ludo3d.game.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.audio.Sfx;
import com.ludo3d.game.game.Board;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The "searching for players" screen for quick-play rooms: the LUDO 3D logo, the mode
 * and entry fee, you VS the open seats (whose avatars shuffle until someone joins), a
 * search timer and a Cancel button. The server starts the match by itself.
 */
final class Matchmaking {
    private static final String[] FACES = {"😎", "🤠", "🧑", "👩", "👨", "👱", "🧔", "👸", "🤴", "🦸", "🧑‍🚀", "🥷", "🧕", "👲"};

    /** When the current search began, kept across screen rotations and app switches. */
    private static String searchCode;
    private static long searchStart;

    private final RoomActivity a;
    private final LudoApp app = LudoApp.get();
    private final Random rng = new Random();
    private final List<Slot> slots = new ArrayList<>();
    private final List<ValueAnimator> loops = new ArrayList<>();
    private TextView timer, searching, title;
    private int lastFilled = -1;
    /** When bots fill the empty seats (elapsedRealtime), or 0 if the server hasn't said. */
    private long deadline;

    /** One avatar frame: shows a player, or shuffles faces while the seat is open. */
    private final class Slot {
        final FrameLayout frame;
        final TextView face;
        final TextView name;
        final View ring;
        boolean filled;

        Slot(int sizeDp) {
            frame = new FrameLayout(a);
            ring = new View(a);
            frame.addView(ring, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            face = Ui.text(a, "?", sizeDp * 0.42f, Color.WHITE);
            face.setGravity(Gravity.CENTER);
            face.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            face.setShadowLayer(Ui.dp(a, 3), 0, Ui.dp(a, 2), 0x66000000);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            int in = Ui.dp(a, 7);
            lp.setMargins(in, in, in, in + Ui.dp(a, 5));
            frame.addView(face, lp);
            name = Fx.GoldText.label(a, "???", 14, 0xff3b0d8c);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            setOpen();
        }

        void setOpen() {
            filled = false;
            Fx.bevel(ring, Fx.PURPLE_DARK, 18, 5, false);
            name.setText("???");
            shuffle();
        }

        void shuffle() {
            if (filled) return;
            face.setText(FACES[rng.nextInt(FACES.length)]);
            face.setBackground(Ui.round(Color.HSVToColor(new float[]{rng.nextInt(360), 0.55f, 0.85f}), Ui.dp(a, 14)));
        }

        void setPlayer(JSONObject seat, boolean me) {
            boolean was = filled;
            filled = true;
            boolean bot = "ai".equals(seat.optString("kind"));
            int col = Board.colorArgb(seat.optString("color"));
            int[] frameColors = {Fx.blend(col, Color.WHITE, 0.35f), col, Fx.blend(col, Color.BLACK, 0.45f)};
            Fx.bevel(ring, frameColors, 18, 5, false);
            String n = seat.optString("name");
            face.setText(bot ? "🤖" : n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase());
            face.setBackground(Ui.round(bot ? Fx.blend(col, Color.BLACK, 0.2f) : Color.HSVToColor(new float[]{seat.optInt("avatarHue", 210), 0.6f, 0.8f}), Ui.dp(a, 14)));
            name.setText(me ? n + " (you)" : n);
            if (!was) {
                // Pop in when the seat is taken.
                frame.setScaleX(0.6f);
                frame.setScaleY(0.6f);
                frame.animate().scaleX(1).scaleY(1).setDuration(380).setInterpolator(new OvershootInterpolator(2f)).start();
            }
        }

        View column(int sizeDp) {
            LinearLayout c = Ui.vbox(a);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            c.addView(frame, new LinearLayout.LayoutParams(Ui.dp(a, sizeDp), Ui.dp(a, sizeDp + 5)));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(Ui.dp(a, sizeDp + 30), ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = Ui.dp(a, 4);
            c.addView(name, nlp);
            return c;
        }
    }

    Matchmaking(RoomActivity a) {
        this.a = a;
    }

    /** Build the screen into the activity root. */
    void build(JSONObject room) {
        if (!room.optString("code").equals(searchCode)) {
            searchCode = room.optString("code");
            searchStart = SystemClock.elapsedRealtime();
        }
        int maxPlayers = room.optInt("maxPlayers", 2);
        JSONObject mode = room.optJSONObject("mode");
        long fee = room.optLong("entryFee");

        LinearLayout col = Ui.vbox(a);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Ui.dp(a, 18), Ui.dp(a, 10), Ui.dp(a, 18), Ui.dp(a, 18));

        // Sound toggle, top right.
        FrameLayout topRow = new FrameLayout(a);
        Fx.Tile sound = Fx.badge(a, Sfx.get().isEnabled() ? "🔊" : "🔇", Fx.PURPLE, 44, null);
        sound.setOnClickListener(v -> {
            Sfx.get().setEnabled(!Sfx.get().isEnabled());
            ((TextView) sound.getChildAt(0)).setText(Sfx.get().isEnabled() ? "🔊" : "🔇");
        });
        topRow.addView(sound, new FrameLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 48), Gravity.END));
        col.addView(topRow, Ui.lpMatchWrap());

        // Logo: crown, gold LUDO 3D, a little board with the die — floating gently.
        LinearLayout logo = Ui.vbox(a);
        logo.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView crown = Ui.text(a, "👑", 30, Color.WHITE);
        crown.setGravity(Gravity.CENTER);
        logo.addView(crown);
        LinearLayout word = Ui.hbox(a);
        word.setGravity(Gravity.CENTER);
        word.addView(new Fx.GoldText(a, "LUDO", 46));
        word.addView(new Fx.GoldText(a, "3D", 20, new int[]{0xffffffff, 0xffe0d4ff, 0xffb69cff}, 0xff3b0d8c));
        logo.addView(word);
        FrameLayout stage = new FrameLayout(a);
        stage.addView(new Fx.MiniBoard(a), new FrameLayout.LayoutParams(Ui.dp(a, 170), Ui.dp(a, 60), Gravity.CENTER));
        View die = new Fx.Die(a);
        stage.addView(die, new FrameLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40), Gravity.CENTER));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(a, 180), Ui.dp(a, 62));
        slp.topMargin = -Ui.dp(a, 12);
        logo.addView(stage, slp);
        col.addView(logo);
        loop(ObjectAnimator.ofFloat(logo, "translationY", 0, -Ui.dp(a, 6)), 1600, true);
        loop(ObjectAnimator.ofFloat(die, "rotation", -12, 12), 700, true);

        title = new Fx.GoldText(a, "ONLINE MULTIPLAYER", 24);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = Ui.lpMatchWrap();
        tlp.topMargin = Ui.dp(a, 6);
        col.addView(title, tlp);

        // Mode | Entry amount table.
        LinearLayout table = Ui.vbox(a);
        int depth = Fx.bevel(table, Fx.RED, 16, 5, false);
        table.setPadding(0, 0, 0, depth);
        LinearLayout head = Ui.hbox(a);
        head.addView(cell("Game Mode", true), Ui.weight(1));
        head.addView(cell("Entry Amount", true), Ui.weight(1));
        table.addView(head);
        LinearLayout values = Ui.hbox(a);
        values.setBackground(Ui.round(0x33000000, 0));
        values.addView(cell("🏆 " + (mode != null ? mode.optString("name") : "Quick"), false), Ui.weight(1));
        values.addView(cell(fee > 0 ? "🪙 " + Ui.fmt(fee) : "FREE", false), Ui.weight(1));
        table.addView(values);
        LinearLayout.LayoutParams tblp = Ui.lpMatchWrap();
        tblp.topMargin = Ui.dp(a, 10);
        tblp.leftMargin = Ui.dp(a, 12);
        tblp.rightMargin = Ui.dp(a, 12);
        col.addView(table, tblp);

        // You VS the others.
        FrameLayout arena = new FrameLayout(a);
        LinearLayout row = Ui.hbox(a);
        row.setGravity(Gravity.CENTER);
        int size = maxPlayers <= 2 ? 116 : 86;
        Slot mine = new Slot(size);
        slots.add(mine);
        row.addView(mine.column(size));
        TextView vs = new Fx.GoldText(a, "VS", 40, new int[]{0xffffffff, 0xffffe082, 0xffff8f00}, 0xff7a1d00);
        vs.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(Ui.dp(a, 76), ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(vs, vlp);
        loop(ObjectAnimator.ofFloat(vs, "scaleX", 1f, 1.18f), 520, true);
        loop(ObjectAnimator.ofFloat(vs, "scaleY", 1f, 1.18f), 520, true);
        if (maxPlayers <= 2) {
            Slot other = new Slot(size);
            slots.add(other);
            row.addView(other.column(size));
        } else {
            LinearLayout grid = Ui.vbox(a);
            LinearLayout r1 = Ui.hbox(a), r2 = Ui.hbox(a);
            for (int i = 1; i < maxPlayers; i++) {
                Slot s = new Slot(size - 20);
                slots.add(s);
                (i <= 2 ? r1 : r2).addView(s.column(size - 20));
                if (i == 1) r1.addView(Ui.gap(a, 6));
            }
            grid.addView(r1);
            grid.addView(r2);
            row.addView(grid);
        }
        arena.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        LinearLayout.LayoutParams alp = Ui.lpMatchWrap();
        alp.topMargin = Ui.dp(a, 22);
        col.addView(arena, alp);

        // Search timer.
        LinearLayout clock = Ui.hbox(a);
        clock.setGravity(Gravity.CENTER);
        int cd = Fx.bevel(clock, Fx.PURPLE_DARK, 22, 4, false);
        clock.setPadding(Ui.dp(a, 10), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 4) + cd);
        TextView watch = Ui.text(a, "⏱️", 22, Color.WHITE);
        clock.addView(watch);
        timer = Fx.GoldText.label(a, "00:00", 22, 0xff3b0d8c);
        timer.setPadding(Ui.dp(a, 8), 0, 0, 0);
        clock.addView(timer);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(a, 22);
        col.addView(clock, clp);
        loop(ObjectAnimator.ofFloat(watch, "rotation", -14, 14), 500, true);

        // "Searching for players..." with a wiggling magnifier.
        LinearLayout searchRow = Ui.hbox(a);
        searchRow.setGravity(Gravity.CENTER);
        TextView glass = Ui.text(a, "🔍", 22, Color.WHITE);
        searchRow.addView(glass);
        searching = new Fx.GoldText(a, "SEARCHING FOR PLAYERS...", 18);
        searchRow.addView(searching);
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srlp.topMargin = Ui.dp(a, 26);
        col.addView(searchRow, srlp);
        loop(ObjectAnimator.ofFloat(glass, "translationX", -Ui.dp(a, 5), Ui.dp(a, 5)), 600, true);

        TextView hint = Ui.muted(a, "If no one else joins before the timer ends, bots take the empty seats and the match starts.");
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(Ui.dp(a, 10), Ui.dp(a, 8), Ui.dp(a, 10), 0);
        col.addView(hint);

        View spacer = new View(a);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        TextView cancel = Ui.button(a, fee > 0 ? "✕  CANCEL & REFUND" : "✕  CANCEL", "danger", v -> a.leave());
        LinearLayout.LayoutParams cnl = Ui.lpMatchWrap();
        cnl.topMargin = Ui.dp(a, 16);
        col.addView(cancel, cnl);

        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.setFillViewport(true);
        sv.addView(col, new android.widget.ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        a.root.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Shuffle open seats, tick the timer, animate the dots.
        ValueAnimator ticker = ValueAnimator.ofInt(0, 1000);
        ticker.setDuration(1000);
        ticker.setRepeatCount(ValueAnimator.INFINITE);
        ticker.setInterpolator(new LinearInterpolator());
        final long[] lastShuffle = {0};
        final long[] lastMusic = {-10_000};
        final long[] lastTickSec = {-1};
        ticker.addUpdateListener(va -> {
            long now = SystemClock.elapsedRealtime();
            boolean searchingNow = lastFilled < slots.size();
            if (now - lastShuffle[0] > 110) {
                lastShuffle[0] = now;
                for (Slot s : slots) s.shuffle();
            }
            // Gentle search music, looped every two seconds while we wait.
            if (searchingNow && now - lastMusic[0] >= 2000) {
                lastMusic[0] = now;
                Sfx.get().play(Sfx.S.SEARCH, 0.55f, 1f);
            }
            long secs;
            if (deadline > 0) {
                // Count down to the moment bots fill the empty seats.
                secs = Math.max(0, (deadline - now + 999) / 1000);
                if (searchingNow && secs <= 10 && secs > 0 && secs != lastTickSec[0]) {
                    lastTickSec[0] = secs;
                    Sfx.get().play(Sfx.S.TICK, 0.8f, secs <= 3 ? 1.25f : 1f);
                    timer.setScaleX(1.25f);
                    timer.setScaleY(1.25f);
                    timer.animate().scaleX(1).scaleY(1).setDuration(300).start();
                }
            } else {
                secs = (now - searchStart) / 1000;
            }
            timer.setText(String.format(java.util.Locale.US, "%02d:%02d", secs / 60, secs % 60));
            if (searchingNow) {
                if (deadline > 0 && secs == 0) {
                    searching.setText("ADDING BOTS...");
                } else {
                    int dots = (int) ((now / 400) % 4);
                    searching.setText("SEARCHING FOR PLAYERS" + "...".substring(0, dots) + "   ".substring(dots));
                }
            }
        });
        ticker.start();
        loops.add(ticker);

        update(room);
    }

    /** Fill seats from the latest room state. */
    void update(JSONObject room) {
        JSONArray seats = room.optJSONArray("seats");
        if (seats == null) seats = new JSONArray();
        int you = room.optInt("you", -1);
        // Your seat goes first, then everyone else in seat order.
        List<JSONObject> order = new ArrayList<>();
        if (you >= 0 && you < seats.length()) order.add(seats.optJSONObject(you));
        for (int i = 0; i < seats.length(); i++) if (i != you) order.add(seats.optJSONObject(i));
        for (int i = 0; i < slots.size(); i++) {
            if (i < order.size() && order.get(i) != null) slots.get(i).setPlayer(order.get(i), i == 0);
            else if (slots.get(i).filled) slots.get(i).setOpen();
        }
        // The server says how long until bots fill the empty seats.
        if (!room.isNull("waitMsLeft") && room.has("waitMsLeft")) {
            deadline = SystemClock.elapsedRealtime() + room.optLong("waitMsLeft");
        }
        int filled = Math.min(order.size(), slots.size());
        if (lastFilled >= 0 && filled > lastFilled) {
            if (filled >= slots.size()) {
                // The search music and countdown end the moment the table is full.
                Sfx.get().stop(Sfx.S.SEARCH);
                Sfx.get().stop(Sfx.S.TICK);
            }
            Sfx.get().play(filled >= slots.size() ? Sfx.S.MATCH_FOUND : Sfx.S.EXTRA);
        }
        if (filled >= slots.size()) {
            searching.setText("MATCH FOUND! GET READY...");
            title.setText("MATCH FOUND!");
        } else if (lastFilled >= slots.size()) {
            title.setText("ONLINE MULTIPLAYER");
        }
        lastFilled = filled;
    }

    void stop() {
        for (ValueAnimator v : loops) v.cancel();
        loops.clear();
        Sfx.get().stop(Sfx.S.SEARCH);
        Sfx.get().stop(Sfx.S.TICK);
    }

    private void loop(ObjectAnimator anim, long ms, boolean reverse) {
        anim.setDuration(ms);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setRepeatMode(reverse ? ValueAnimator.REVERSE : ValueAnimator.RESTART);
        anim.start();
        loops.add(anim);
    }

    private TextView cell(String text, boolean header) {
        TextView t = header ? Fx.GoldText.label(a, text, 16, 0xff7a0d16) : new Fx.GoldText(a, text, 18);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6), Ui.dp(a, 8));
        return t;
    }
}
