package com.ludox.game.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.TextViewCompat;

import com.ludox.game.audio.Sfx;
import com.ludox.game.game.Board;
import com.ludox.game.gl.ConfigChooser;
import com.ludox.game.gl.TreasureRenderer;
import com.ludox.game.gl.TreasureRenderer.Timeline;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MATCH FOUND: who I'm about to play, then everyone's entry fee flying into the royal treasure
 * box, then MATCH READY and the board. By the time this shows, the server has charged the fees and
 * recorded the pool; every figure here is the server's and nothing on this screen changes them.
 * There is nothing to tap: the match is already on and the server starts the first turn.
 *
 * Text, cards and sounds run off the same clock as the 3D scene ({@link TreasureRenderer.Timeline}).
 */
final class MatchIntroScreen {
    private final BaseActivity a;
    private final JSONObject intro;
    private final Runnable done;
    private final long durationMs, endsAt;
    private final long pool, fee, reward;
    private final int you;
    private final List<JSONObject> players = new ArrayList<>();
    /** Indexes into players of those whose coins fly, in seat order. */
    private final List<Integer> payers = new ArrayList<>();
    private final boolean compact;

    private final FrameLayout host;
    private GLSurfaceView gl;
    private TreasureRenderer renderer;
    private final Timeline line;
    private final boolean hasCoins;

    private LinearLayout content, cardsBox;
    private View stage, fader;
    private final List<View> cards = new ArrayList<>();
    private TextView title, sub, versus;
    private LinearLayout flow, ready;
    private final List<View> rows = new ArrayList<>();
    private TextView poolLine;
    private boolean laidOut;
    private float cardsDrop;

    private final float startFraction;
    private int coinsHeard;
    private boolean lockHeard, finished;
    private long lastCoinSound;
    private String shownTitle = "", shownPool = "";

    private final Choreographer.FrameCallback tick = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long nanos) {
            if (finished) return;
            frame();
            if (!finished) Choreographer.getInstance().postFrameCallback(this);
        }
    };

    MatchIntroScreen(BaseActivity a, JSONObject intro, int you, long endsAt, Runnable done) {
        this.a = a;
        this.intro = intro;
        this.you = you;
        this.endsAt = endsAt;
        this.done = done;
        durationMs = Math.max(1, intro.optLong("durationMs", 6000));
        pool = intro.optLong("pool");
        fee = intro.optLong("entryFee");
        reward = intro.optLong("winnerReward");
        JSONArray ps = intro.optJSONArray("players");
        for (int i = 0; ps != null && i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p == null) continue;
            players.add(p);
            if (p.optLong("contribution") > 0) payers.add(players.size() - 1);
        }
        hasCoins = pool > 0 && !payers.isEmpty();
        compact = a.getResources().getConfiguration().screenHeightDp < 680;
        startFraction = fraction();
        host = new FrameLayout(a);
        renderer = new TreasureRenderer(hasCoins ? payers.size() : 0, durationMs, endsAt, Effects.level());
        line = renderer.timeline();
        coinsHeard = landedTotal(startFraction);
        lockHeard = startFraction >= Timeline.LOCK_END;
        build();
    }

    private float fraction() {
        float f = 1f - (endsAt - SystemClock.elapsedRealtime()) / (float) durationMs;
        return Math.max(0f, Math.min(1f, f));
    }

    private int landedTotal(float f) {
        int n = 0;
        for (int i = 0; i < line.contributors; i++) n += line.landed(i, f);
        return n;
    }

    // ================================================================= layout

    private void build() {
        gl = new GLSurfaceView(a);
        gl.setEGLContextClientVersion(2);
        gl.setEGLConfigChooser(new ConfigChooser());
        gl.setRenderer(renderer);
        gl.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        host.addView(gl, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content = Ui.vbox(a);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        int pad = Ui.dp(a, 14);
        content.setPadding(pad, Ui.dp(a, compact ? 8 : 18), pad, Ui.dp(a, compact ? 10 : 18));

        title = new Fx.GoldText(a, "MATCH FOUND!", compact ? 32 : 40);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        content.addView(title, Ui.lpMatchWrap());

        sub = Ui.bold(Ui.text(a, subtitle(), compact ? 14 : 16, 0xfffff3c4));
        sub.setGravity(Gravity.CENTER);
        sub.setShadowLayer(Ui.dp(a, 3), 0, Ui.dp(a, 1), 0xcc000000);
        content.addView(sub, Ui.lpMatchWrap());

        versus = Ui.bold(Ui.text(a, versusLine(), compact ? 18 : 22, Color.WHITE));
        versus.setGravity(Gravity.CENTER);
        versus.setSingleLine(true);
        versus.setEllipsize(TextUtils.TruncateAt.END);
        versus.setShadowLayer(Ui.dp(a, 3), 0, Ui.dp(a, 1), 0xcc000000);
        if (players.size() > 2) versus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        else TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(versus, 12, compact ? 18 : 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, compact ? 26 : 32));
        vl.topMargin = Ui.dp(a, 2);
        content.addView(versus, vl);

        cardsBox = buildCards();
        LinearLayout.LayoutParams cl = Ui.lpMatchWrap();
        cl.topMargin = Ui.dp(a, compact ? 6 : 12);
        content.addView(cardsBox, cl);

        // The chest stands here: the scene behind shows through.
        stage = new View(a);
        content.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content.addView(buildInfo(), Ui.lpMatchWrap());

        host.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fader = new View(a);
        fader.setBackgroundColor(0xff14052f);
        fader.setAlpha(0f);
        host.addView(fader, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Taps don't reach the screen underneath.
        host.setClickable(true);
        host.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> measured());
    }

    private boolean botOnly() {
        for (int i = 0; i < players.size(); i++) if (i != you && !"ai".equals(players.get(i).optString("kind"))) return false;
        return true;
    }

    private String subtitle() {
        if (botOnly()) return "Nobody else joined, so bots take the empty seats.";
        return players.size() > 2 ? "Your opponents have been found!" : "Your opponent has been found!";
    }

    private static String shortName(JSONObject p) {
        return p.optString("name").replace(" (bot)", "").toUpperCase(Locale.getDefault());
    }

    private String versusLine() {
        StringBuilder s = new StringBuilder();
        if (intro.optInt("winners", 1) == 2 && players.size() == 4) {
            return shortName(players.get(0)) + " & " + shortName(players.get(2)) + " VS " + shortName(players.get(1)) + " & " + shortName(players.get(3));
        }
        for (int i = 0; i < players.size(); i++) {
            if (i > 0) s.append(" VS ");
            s.append(shortName(players.get(i)));
        }
        return s.toString();
    }

    private LinearLayout buildCards() {
        LinearLayout box = Ui.vbox(a);
        box.setClipChildren(false);
        int n = players.size();
        if (n <= 2) {
            LinearLayout row = Ui.hbox(a);
            row.setGravity(Gravity.CENTER);
            row.setClipChildren(false);
            for (int i = 0; i < n; i++) {
                View c = card(players.get(i), i == you, compact ? 56 : 72);
                cards.add(c);
                if (i == 1) {
                    TextView vs = new Fx.GoldText(a, "VS", compact ? 22 : 28, new int[]{0xffffffff, 0xffffe082, 0xffff8f00}, 0xff7a1d00);
                    vs.setGravity(Gravity.CENTER);
                    row.addView(vs, new LinearLayout.LayoutParams(Ui.dp(a, 44), ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                row.addView(c, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
            box.addView(row, Ui.lpMatchWrap());
        } else {
            for (int r = 0; r < 2; r++) {
                LinearLayout row = Ui.hbox(a);
                row.setClipChildren(false);
                for (int i = r * 2; i < Math.min(n, r * 2 + 2); i++) {
                    View c = card(players.get(i), i == you, compact ? 34 : 44);
                    cards.add(c);
                    LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                    l.leftMargin = l.rightMargin = Ui.dp(a, 4);
                    row.addView(c, l);
                }
                LinearLayout.LayoutParams rl = Ui.lpMatchWrap();
                rl.topMargin = r == 0 ? 0 : Ui.dp(a, 6);
                box.addView(row, rl);
            }
        }
        return box;
    }

    private View card(JSONObject p, boolean me, int avatarDp) {
        boolean bot = "ai".equals(p.optString("kind"));
        int col = Board.colorArgb(p.optString("color"));
        LinearLayout c = Ui.vbox(a);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xf0391780, 0xf01b0a45});
        bg.setCornerRadius(Ui.dp(a, 18));
        bg.setStroke(Ui.dp(a, 2), 0xffffc94a);
        c.setBackground(bg);
        c.setPadding(Ui.dp(a, 8), Ui.dp(a, compact ? 6 : 10), Ui.dp(a, 8), Ui.dp(a, compact ? 6 : 10));

        String name = p.optString("name");
        View face;
        if (bot) face = Ui.avatarColor(a, "🤖", col, avatarDp);
        else face = Ui.avatar(a, p, name, avatarDp);
        // A ring in the player's colour.
        FrameLayout ring = new FrameLayout(a);
        GradientDrawable r = new GradientDrawable();
        r.setShape(GradientDrawable.OVAL);
        r.setStroke(Ui.dp(a, 3), col);
        ring.setForeground(r);
        ring.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
        ring.addView(face, new FrameLayout.LayoutParams(Ui.dp(a, avatarDp), Ui.dp(a, avatarDp)));
        c.addView(ring, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView n = Ui.bold(Ui.text(a, (bot ? "" : Flags.of(p) + " ") + name, compact ? 15 : 18, Color.WHITE));
        n.setGravity(Gravity.CENTER);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        n.setPadding(0, Ui.dp(a, 4), 0, 0);
        c.addView(n, Ui.lpMatchWrap());

        LinearLayout tags = Ui.hbox(a);
        tags.setGravity(Gravity.CENTER);
        TextView colour = Ui.bold(Ui.text(a, capital(p.optString("color")), 11, Color.WHITE));
        colour.setBackground(Ui.round(Fx.blend(col, Color.BLACK, 0.15f), Ui.dp(a, 999)));
        colour.setPadding(Ui.dp(a, 8), Ui.dp(a, 1), Ui.dp(a, 8), Ui.dp(a, 1));
        tags.addView(colour);
        if (me) tags.addView(tag("YOU", 0xffffc94a, 0xff2a1200));
        if (bot) tags.addView(tag("BOT", 0xff8f9bd8, 0xff10122c));
        if (!p.isNull("rank") && p.optInt("rank") > 0) tags.addView(tag("#" + p.optInt("rank"), 0x33ffffff, 0xfffff3c4));
        LinearLayout.LayoutParams tl = Ui.lpMatchWrap();
        tl.topMargin = Ui.dp(a, 3);
        c.addView(tags, tl);
        return c;
    }

    private TextView tag(String s, int bg, int fg) {
        TextView t = Ui.bold(Ui.text(a, s, 11, fg));
        t.setBackground(Ui.round(bg, Ui.dp(a, 999)));
        t.setPadding(Ui.dp(a, 7), Ui.dp(a, 1), Ui.dp(a, 7), Ui.dp(a, 1));
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.leftMargin = Ui.dp(a, 4);
        t.setLayoutParams(l);
        return t;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private static String coins(long n) {
        return Ui.fmt(n) + (n == 1 ? " COIN" : " COINS");
    }

    /** Below the chest: contributions and the pool, then MATCH READY's summary in the same space. */
    private View buildInfo() {
        FrameLayout info = new FrameLayout(a);
        flow = Ui.vbox(a);
        flow.setGravity(Gravity.CENTER_HORIZONTAL);
        for (int i : payers) {
            JSONObject p = players.get(i);
            TextView row = Ui.bold(Ui.text(a, shortName(p) + " CONTRIBUTION: " + coins(p.optLong("contribution")), compact ? 13 : 15, 0xfffff3c4));
            row.setGravity(Gravity.CENTER);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.setPadding(0, Ui.dp(a, 1), 0, Ui.dp(a, 1));
            row.setShadowLayer(Ui.dp(a, 2), 0, Ui.dp(a, 1), 0xcc000000);
            rows.add(row);
            flow.addView(row, Ui.lpMatchWrap());
        }
        poolLine = new Fx.GoldText(a, "", compact ? 20 : 24);
        poolLine.setGravity(Gravity.CENTER);
        poolLine.setSingleLine(true);
        LinearLayout.LayoutParams pl = Ui.lpMatchWrap();
        pl.topMargin = Ui.dp(a, 4);
        flow.addView(poolLine, pl);
        info.addView(flow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        ready = Ui.vbox(a);
        ready.setGravity(Gravity.CENTER_HORIZONTAL);
        if (hasCoins) {
            TextView prize = new Fx.GoldText(a, "TOTAL MATCH POOL: " + coins(pool), compact ? 20 : 24);
            prize.setGravity(Gravity.CENTER);
            prize.setSingleLine(true);
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(prize, 12, compact ? 20 : 24, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            ready.addView(prize, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, compact ? 30 : 36)));
            ready.addView(summary("Entry Fee: " + Ui.fmt(fee) + (fee == 1 ? " Coin Each" : " Coins Each")));
            ready.addView(summary("Winner's Total Reward: " + Ui.fmt(reward) + (reward == 1 ? " Coin" : " Coins")));
        } else {
            ready.addView(summary("Free match: no coins at stake."));
        }
        ready.setAlpha(0f);
        info.addView(ready, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        info.setMinimumHeight(Ui.dp(a, compact ? 60 : 84));
        return info;
    }

    private TextView summary(String s) {
        TextView t = Ui.bold(Ui.text(a, s, compact ? 14 : 16, Color.WHITE));
        t.setGravity(Gravity.CENTER);
        t.setShadowLayer(Ui.dp(a, 2), 0, Ui.dp(a, 1), 0xcc000000);
        return t;
    }

    /** Add the screen (once) and start. */
    void show(ViewGroup parent) {
        parent.addView(host, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (startFraction < 0.15f) Sfx.get().play(Sfx.S.MATCH_FOUND);
        frame();
        Choreographer.getInstance().postFrameCallback(tick);
    }

    /** Tell the 3D scene where the cards and the empty stage ended up (positions relative to the surface). */
    private void measured() {
        if (host.getWidth() == 0) return;
        float[][] anchors = new float[payers.size()][2];
        for (int i = 0; i < payers.size(); i++) {
            View c = cards.get(payers.get(i));
            float x = c.getWidth() / 2f, y = c.getHeight();
            for (View v = c; v != host && v != null; v = (View) v.getParent()) {
                x += v.getLeft();
                y += v.getTop();
            }
            anchors[i][0] = x;
            anchors[i][1] = y;
        }
        float boxY = stage.getTop() + stage.getHeight() * 0.52f;
        renderer.setLayout(anchors, boxY, stage.getHeight());
        // Before the chest comes in the cards stand in the middle of the screen; they rise to make room.
        cardsDrop = (content.getHeight() - cardsBox.getHeight()) / 2f - cardsBox.getTop();
        if (cardsDrop < 0) cardsDrop = 0;
        laidOut = true;
    }

    // ================================================================= animation

    private static float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static float smooth(float v) {
        v = clamp01(v);
        return v * v * (3 - 2 * v);
    }

    private static float back(float v) {
        v = clamp01(v);
        float s = 1.4f, u = v - 1;
        return u * u * ((s + 1) * u + s) + 1;
    }

    private void frame() {
        float f = fraction();
        // The free version has no chest: only the cards and READY, and it spreads them over its shorter time.
        float readyAt = hasCoins ? Timeline.READY : 0.62f;
        float ms = f * durationMs;

        // Player cards slide in from opposite sides, one a moment after the other.
        float w = host.getWidth();
        for (int i = 0; i < cards.size(); i++) {
            View c = cards.get(i);
            float in = back((ms - 80 - i * 110) / 520f);
            float side = i % 2 == 0 ? -1 : 1;
            c.setTranslationX(side * (1 - in) * w);
            c.setAlpha(clamp01(in * 2));
        }
        if (laidOut) {
            float rise = hasCoins ? smooth((f - Timeline.CARDS_RISE) / (Timeline.BOX_IN - Timeline.CARDS_RISE)) : 0f;
            cardsBox.setTranslationY(cardsDrop * (1 - rise));
            float big = 1f + 0.04f * (1 - rise);
            cardsBox.setScaleX(big);
            cardsBox.setScaleY(big);
        }
        title.setAlpha(clamp01(ms / 300f));
        float pop = back(ms / 420f);
        title.setScaleX(0.5f + 0.5f * pop);
        title.setScaleY(0.5f + 0.5f * pop);
        sub.setAlpha(clamp01((ms - 300) / 300f));
        versus.setAlpha(clamp01((ms - 500) / 300f));

        if (hasCoins) animateCoins(f);

        // MATCH READY.
        boolean isReady = f >= readyAt;
        setTitle(isReady ? "MATCH READY!" : botOnly() ? "BOT MATCH!" : "MATCH FOUND!");
        float rd = smooth((f - readyAt) / 0.04f);
        ready.setAlpha(rd);
        flow.setAlpha((hasCoins ? clamp01((f - Timeline.COINS_START + 0.03f) / 0.03f) : 0) * (1 - rd));
        if (isReady) sub.setAlpha(1 - rd);

        fader.setAlpha(smooth((f - Timeline.FADE) / (1f - Timeline.FADE)));
        if (f >= 1f) finish();
    }

    private void setTitle(String s) {
        if (!s.equals(shownTitle)) {
            shownTitle = s;
            title.setText(s);
        }
    }

    private void animateCoins(float f) {
        long inBox = 0;
        for (int i = 0; i < payers.size(); i++) {
            JSONObject p = players.get(payers.get(i));
            int landed = line.landed(i, f);
            inBox += landed >= line.coinsEach ? p.optLong("contribution") : p.optLong("contribution") * landed / line.coinsEach;
            // Whose turn it is: their card leans forward, their line lights up.
            float slot = line.slotStart(i), end = line.slotEnd(i);
            float active = f >= slot && f < end ? (float) Math.sin((f - slot) / (end - slot) * Math.PI) : 0f;
            View c = cards.get(payers.get(i));
            c.setScaleX(1f + 0.07f * active);
            c.setScaleY(1f + 0.07f * active);
            View row = rows.get(i);
            row.setAlpha(f < slot ? 0.25f : 1f);
        }
        // The last coin always lands on the exact official number.
        if (landedTotal(f) >= line.contributors * line.coinsEach) inBox = pool;
        boolean collected = f >= Timeline.LOCK_END - 0.02f;
        String text = collected ? coins(pool) + " COLLECTED!" : "TOTAL MATCH POOL: " + coins(inBox);
        if (!text.equals(shownPool)) {
            shownPool = text;
            poolLine.setText(text);
        }

        int total = landedTotal(f);
        if (total > coinsHeard) {
            coinsHeard = total;
            long now = SystemClock.uptimeMillis();
            if (now - lastCoinSound > 60) {
                lastCoinSound = now;
                Sfx.get().play(Sfx.S.COIN, 0.7f, 0.95f + 0.25f * total / (line.contributors * line.coinsEach));
            }
        }
        if (!lockHeard && f >= Timeline.LOCK_END - 0.015f) {
            lockHeard = true;
            Sfx.get().play(Sfx.S.HOME_FINAL);
            gl.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    }

    /** Done (time is up, or the server moved on): the board comes next. Safe to ask twice. */
    void finish() {
        if (finished) return;
        finished = true;
        Choreographer.getInstance().removeFrameCallback(tick);
        done.run();
    }

    void onResume() {
        gl.onResume();
        if (!finished) {
            Choreographer.getInstance().removeFrameCallback(tick);
            Choreographer.getInstance().postFrameCallback(tick);
        }
    }

    void onPause() {
        gl.onPause();
        Choreographer.getInstance().removeFrameCallback(tick);
    }

    void stop() {
        finished = true;
        Choreographer.getInstance().removeFrameCallback(tick);
    }
}
