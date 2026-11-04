package com.ludox.game.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.ludox.game.LudoApp;
import com.ludox.game.audio.Sfx;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;

/**
 * A reward the server has confirmed, shown: the exact amount announced, coins or diamonds
 * flying on curved paths to the real wallet icon, a flash when they land, and the balance
 * counting from the old value to the new one. It only displays what it is given: when it ends
 * the counter shows the wallet the server last sent. It draws over the screen, takes no touches
 * and never holds anything up. Each ledger entry plays once.
 */
final class RewardFx extends View {
    /** What to play and where (the anchor and counter must be in the same window as the host). */
    static final class Play {
        JSONObject reward;
        /** What the coins fly to; null flies to the top of the screen. */
        View anchor;
        /** The number that counts up, with what stands before it ("🪙 "). */
        TextView counter;
        String prefix = "";
        /** Where they start (default: the middle of the screen). */
        View from;
        /** Say the amount in big letters (off where the screen already says it). */
        boolean announce = true;
        Runnable done;
    }

    private static final LinkedHashSet<String> played = new LinkedHashSet<>();
    /** Counters that must not be overwritten with the new balance yet, by reward kind. */
    private static final Map<String, Integer> running = new HashMap<>();
    private static final Map<String, Long> heldUntil = new HashMap<>();
    private static final Map<String, Bitmap> sprites = new HashMap<>();
    private static final long HOLD_MS = 60_000;

    static boolean wasPlayed(JSONObject reward) {
        return reward != null && played.contains(reward.optString("id"));
    }

    /** A screen showing this balance leaves it alone: an animation is counting it up. */
    static boolean counting(String kind) {
        Integer n = running.get(kind);
        if (n != null && n > 0) return true;
        Long until = heldUntil.get(kind);
        return until != null && SystemClock.uptimeMillis() < until;
    }

    /** Keep the old number showing until the reward animates (a popup covers it for a while). */
    static void hold(String kind) {
        heldUntil.put(kind, SystemClock.uptimeMillis() + HOLD_MS);
    }

    static void release(String kind) {
        heldUntil.remove(kind);
    }

    /** The balance the server last sent. */
    static long truth(String kind) {
        LudoApp app = LudoApp.get();
        return "diamonds".equals(kind) ? app.wallet.optLong("gems") : app.balance();
    }

    static void play(FrameLayout host, Play p) {
        JSONObject r = p.reward;
        if (r == null || host == null || r.optLong("amount") <= 0 || !played.add(r.optString("id"))) {
            if (p.done != null) p.done.run();
            return;
        }
        while (played.size() > 64) played.remove(played.iterator().next());
        String kind = r.optString("kind");
        release(kind);
        if (Effects.level() == Effects.REDUCED) {
            // No motion: the number and one line.
            if (p.counter != null) p.counter.setText(p.prefix + Ui.fmt(truth(kind)));
            sound(kind, 0.8f, 1f);
            LudoApp.toast("+" + Ui.fmt(r.optLong("amount")) + ("diamonds".equals(kind) ? " 💎" : " 🪙"));
            if (p.done != null) p.done.run();
            return;
        }
        running.merge(kind, 1, Integer::sum);
        if (p.counter != null) p.counter.setText(p.prefix + Ui.fmt(r.optLong("before")));
        RewardFx fx = new RewardFx(host.getContext(), p);
        host.addView(fx, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static void sound(String kind, float volume, float rate) {
        Sfx s = Sfx.get();
        if (s != null) s.play("diamonds".equals(kind) ? Sfx.S.DIAMOND : Sfx.S.COIN, volume, rate);
    }

    // ------------------------------------------------------------------ the animation

    private static final int TRAIL = 6;
    private static final int SPARKS = 48;

    private final Play play;
    private final String kind, style;
    private final long amount, before;
    private final int count;
    private final boolean trails;
    private final float density;
    private final Random rnd = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Bitmap sprite;
    private final int glow;

    private boolean started, finished;
    private long startAt;
    /** Each piece: start, curve control, delay, flight time, spin phase, size. */
    private float[] sx, sy, cx, cy, spin, size;
    private long[] delay, flight;
    private boolean[] landed;
    private float[][] trail;
    private int[] trailN;
    private int arrived;
    private float tx, ty;
    private long lastLandAt;
    private float flash;
    private final float[][] sparks = new float[SPARKS][6];
    private int sparkNext;
    private long lastFrame;
    private String announceTop, announceSub;

    private RewardFx(Context c, Play p) {
        super(c);
        play = p;
        JSONObject r = p.reward;
        kind = r.optString("kind");
        style = r.optString("style", "diamonds".equals(kind) ? "diamond" : "gold_coin");
        amount = r.optLong("amount");
        before = r.optLong("before");
        density = c.getResources().getDisplayMetrics().density;
        boolean full = Effects.level() == Effects.FULL;
        trails = full;
        int base = 5 + (int) (3 * Math.log10(Math.max(1, amount)));
        if ("crown_coin".equals(style)) base += 6;
        if ("legendary_diamond".equals(style)) base += 8;
        base = Math.min(24, base);
        count = full ? base : Math.max(3, base / 3);
        sprite = sprite(c, style);
        glow = "diamonds".equals(kind) ? ("legendary_diamond".equals(style) ? 0xffff8ae2 : 0xff7fe3ff) : "silver_coin".equals(style) ? 0xffe5e9f5 : 0xffffd34d;
        edge.setColorFilter(new PorterDuffColorFilter(0xff6b4a10, PorterDuff.Mode.SRC_ATOP));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD_ITALIC));
        String unit = "diamonds".equals(kind) ? (amount == 1 ? "DIAMOND" : "DIAMONDS") : (amount == 1 ? "COIN" : "COINS");
        announceTop = "+" + Ui.fmt(amount) + " " + unit;
        switch (r.optString("source")) {
            case "daily_bonus": announceSub = "DAILY BONUS"; break;
            case "daily_spin": announceSub = "DAILY SPIN"; break;
            case "match_win": announceSub = "MATCH WIN"; break;
            case "gift": announceSub = "GIFT"; break;
            default: announceSub = ""; break;
        }
        for (float[] s : sparks) s[4] = 0;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (!started && w > 0 && h > 0) begin(w, h);
    }

    private float[] centreOf(View v) {
        int[] a = new int[2], b = new int[2];
        v.getLocationInWindow(a);
        getLocationInWindow(b);
        return new float[]{a[0] - b[0] + v.getWidth() / 2f, a[1] - b[1] + v.getHeight() / 2f};
    }

    private void begin(int w, int h) {
        started = true;
        startAt = SystemClock.uptimeMillis();
        lastFrame = startAt;
        if (play.anchor != null) {
            float[] t = centreOf(play.anchor);
            tx = t[0];
            ty = t[1];
        } else {
            tx = w / 2f;
            ty = density * 40;
        }
        float ox = w / 2f, oy = h * 0.42f;
        if (play.from != null) {
            float[] f = centreOf(play.from);
            ox = f[0];
            oy = f[1];
        }
        sx = new float[count];
        sy = new float[count];
        cx = new float[count];
        cy = new float[count];
        spin = new float[count];
        size = new float[count];
        delay = new long[count];
        flight = new long[count];
        landed = new boolean[count];
        trail = new float[count][TRAIL * 2];
        trailN = new int[count];
        long lead = play.announce ? 380 : 0;
        for (int i = 0; i < count; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            float out = density * (20 + rnd.nextFloat() * 70);
            sx[i] = ox + (float) Math.cos(ang) * out;
            sy[i] = oy + (float) Math.sin(ang) * out * 0.7f;
            // Pull the curve out sideways, so each piece swings round on its way.
            float side = (i % 2 == 0 ? 1 : -1) * density * (90 + rnd.nextFloat() * 110);
            cx[i] = (sx[i] + tx) / 2 + side;
            cy[i] = (sy[i] + ty) / 2 + density * (rnd.nextFloat() * 60);
            spin[i] = rnd.nextFloat() * 6.28f;
            size[i] = 0.75f + rnd.nextFloat() * 0.45f;
            delay[i] = lead + i * 70L;
            flight[i] = 640 + rnd.nextInt(220);
        }
        if (play.announce && ("crown_coin".equals(style) || "legendary_diamond".equals(style))) {
            Sfx s = Sfx.get();
            if (s != null) s.play(Sfx.S.EXTRA, 0.8f, 1f);
        }
        postInvalidateOnAnimation();
    }

    private void land(int i) {
        landed[i] = true;
        arrived++;
        lastLandAt = SystemClock.uptimeMillis();
        flash = 1f;
        sound(kind, 0.55f, 0.95f + 0.45f * arrived / count);
        if (play.counter != null) {
            long v = arrived >= count ? before + amount : before + amount * arrived / count;
            play.counter.setText(play.prefix + Ui.fmt(v));
        }
        if (play.anchor != null) {
            play.anchor.animate().cancel();
            play.anchor.setScaleX(1.18f);
            play.anchor.setScaleY(1.18f);
            play.anchor.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
        }
        if (trails) for (int k = 0; k < 3; k++) spark(tx, ty);
    }

    private void spark(float x, float y) {
        float[] s = sparks[sparkNext];
        sparkNext = (sparkNext + 1) % SPARKS;
        double a = rnd.nextDouble() * Math.PI * 2;
        float v = density * (60 + rnd.nextFloat() * 130);
        s[0] = x;
        s[1] = y;
        s[2] = (float) Math.cos(a) * v;
        s[3] = (float) Math.sin(a) * v;
        s[4] = 1f;
        s[5] = density * (1.5f + rnd.nextFloat() * 2f);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!started || finished) return;
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        long age = now - startAt;
        if (play.announce) drawAnnouncement(c, age);
        for (int i = 0; i < count; i++) if (!landed[i]) drawPiece(c, i, age);
        drawCollect(c, dt);
        for (float[] s : sparks) {
            if (s[4] <= 0) continue;
            s[4] -= dt * 2.2f;
            s[0] += s[2] * dt;
            s[1] += s[3] * dt;
            paint.setColor(glow);
            paint.setAlpha((int) (255 * Math.max(0, s[4])));
            c.drawCircle(s[0], s[1], s[5], paint);
        }
        paint.setAlpha(255);
        boolean over = arrived >= count && now - lastLandAt > 450;
        if (over) {
            end();
            return;
        }
        postInvalidateOnAnimation();
    }

    private void drawPiece(Canvas c, int i, long age) {
        long a = age - delay[i];
        if (a < 0) {
            // Waiting its turn: pops up where it will leave from.
            float k = Math.max(0f, 1f + a / 240f);
            if (k > 0) drawSprite(c, sx[i], sy[i], size[i] * k, age, i);
            return;
        }
        float t = Math.min(1f, a / (float) flight[i]);
        // Slow to leave, fast to arrive.
        float e = t * t * (1.6f - 0.6f * t);
        float u = 1 - e;
        float x = u * u * sx[i] + 2 * u * e * cx[i] + e * e * tx;
        float y = u * u * sy[i] + 2 * u * e * cy[i] + e * e * ty;
        if (trails) {
            float[] tr = trail[i];
            System.arraycopy(tr, 0, tr, 2, tr.length - 2);
            tr[0] = x;
            tr[1] = y;
            if (trailN[i] < TRAIL) trailN[i]++;
            for (int k = 1; k < trailN[i]; k++) {
                paint.setColor(glow);
                paint.setAlpha(150 * (TRAIL - k) / TRAIL);
                c.drawCircle(tr[k * 2], tr[k * 2 + 1], density * 3.2f * (TRAIL - k) / TRAIL, paint);
            }
            paint.setAlpha(255);
        }
        drawSprite(c, x, y, size[i] * (1f - 0.45f * e), age, i);
        if (t >= 1f) land(i);
    }

    /** A flat sprite turned round its upright axis, with its edge showing, reads as a solid coin. */
    private void drawSprite(Canvas c, float x, float y, float s, long age, int i) {
        boolean gem = "diamonds".equals(kind);
        double turn = age / 130.0 + spin[i];
        float w = (float) Math.cos(turn);
        float half = sprite.getWidth() / 2f;
        c.save();
        c.translate(x, y);
        if (gem) {
            c.rotate((float) Math.sin(turn * 0.7) * 22f);
            c.scale(s * (0.72f + 0.28f * Math.abs(w)), s);
        } else {
            c.scale(s, s);
            c.save();
            c.scale(Math.max(0.1f, Math.abs(w)), 1f);
            c.translate((w >= 0 ? -1 : 1) * density * 2.4f, 0);
            c.drawBitmap(sprite, -half, -half, edge);
            c.restore();
            c.scale(Math.max(0.1f, Math.abs(w)), 1f);
        }
        paint.setAlpha(255);
        c.drawBitmap(sprite, -half, -half, paint);
        c.restore();
    }

    private void drawCollect(Canvas c, float dt) {
        if (flash <= 0) return;
        flash = Math.max(0, flash - dt * 3f);
        float r = density * (16 + 26 * (1 - flash));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density * 3f * flash);
        paint.setColor(glow);
        paint.setAlpha((int) (220 * flash));
        c.drawCircle(tx, ty, r, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha((int) (90 * flash));
        c.drawCircle(tx, ty, r * 0.8f, paint);
        paint.setAlpha(255);
    }

    private void drawAnnouncement(Canvas c, long age) {
        float in = Math.min(1f, age / 240f);
        long lifetime = 1500 + count * 45L;
        float out = age > lifetime ? Math.max(0f, 1f - (age - lifetime) / 260f) : 1f;
        if (out <= 0) return;
        float over = in < 1f ? in * (1.25f - 0.25f * in) : 1f;
        float y = getHeight() * 0.34f;
        c.save();
        c.translate(getWidth() / 2f, y);
        c.scale(over, over);
        float size = Math.min(getWidth() * 0.095f, density * 38);
        drawOutlined(c, announceTop, 0, 0, size, out);
        if (!announceSub.isEmpty()) drawOutlined(c, announceSub, 0, size * 0.85f, size * 0.42f, out);
        c.restore();
    }

    private void drawOutlined(Canvas c, String s, float x, float y, float size, float alpha) {
        text.setTextSize(size);
        text.setShader(null);
        text.setStyle(Paint.Style.STROKE);
        text.setStrokeJoin(Paint.Join.ROUND);
        text.setStrokeWidth(size * 0.17f);
        text.setColor(0xff4a1d00);
        text.setAlpha((int) (255 * alpha));
        c.drawText(s, x, y, text);
        text.setStyle(Paint.Style.FILL);
        text.setColor(Color.WHITE);
        text.setAlpha((int) (255 * alpha));
        text.setShader(new LinearGradient(0, y - size, 0, y, 0xfffff7b0, "diamonds".equals(kind) ? 0xff7fe3ff : 0xffffa90a, Shader.TileMode.CLAMP));
        c.drawText(s, x, y, text);
        text.setShader(null);
    }

    private void end() {
        if (finished) return;
        finished = true;
        Integer n = running.get(kind);
        running.put(kind, n == null ? 0 : Math.max(0, n - 1));
        // The server's number has the last word, whatever else arrived meanwhile.
        if (play.counter != null) play.counter.setText(play.prefix + Ui.fmt(truth(kind)));
        if (play.done != null) play.done.run();
        post(() -> {
            if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        end();
    }

    // ------------------------------------------------------------------ the pieces

    /** Painted once per style; every piece on screen shares it. */
    static Bitmap sprite(Context c, String style) {
        return sprite(c, style, 40);
    }

    /** The same piece painted larger, for showing one on its own. */
    static Bitmap sprite(Context c, String style, int sizeDp) {
        String key = style + sizeDp;
        Bitmap b = sprites.get(key);
        if (b != null) return b;
        int s = Ui.dp(c, sizeDp);
        b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas v = new Canvas(b);
        switch (style) {
            case "silver_coin":
                coin(v, s, 0xffffffff, 0xffc9d0e4, 0xff7f88a8, 0xff5d6685, false);
                break;
            case "crown_coin":
                coin(v, s, 0xfffff6c0, 0xffffc21f, 0xffc96f00, 0xff8a4b00, true);
                break;
            case "diamond":
                diamond(v, s, 0xffc8f6ff, 0xff5fd2ff, 0xff1787d6, 0xff0b4f96);
                break;
            case "legendary_diamond":
                diamond(v, s, 0xffffe0fb, 0xffff7fe0, 0xffa349ff, 0xff4b1aa8);
                break;
            default:
                coin(v, s, 0xfffff4b8, 0xffffd34d, 0xffd48b10, 0xff9a5c06, false);
                break;
        }
        sprites.put(key, b);
        return b;
    }

    private static void coin(Canvas v, int s, int light, int mid, int dark, int line, boolean crown) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float c = s / 2f, r = s * 0.46f;
        p.setShader(new RadialGradient(c - r * 0.35f, c - r * 0.4f, r * 1.7f, new int[]{light, mid, dark}, new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP));
        v.drawCircle(c, c, r, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.035f);
        p.setColor(line);
        v.drawCircle(c, c, r - p.getStrokeWidth() / 2, p);
        p.setColor(line & 0xccffffff);
        v.drawCircle(c, c, r * 0.72f, p);
        p.setStyle(Paint.Style.FILL);
        Path m = new Path();
        if (crown) {
            float w = r * 0.62f, h = r * 0.5f;
            m.moveTo(c - w, c + h * 0.7f);
            m.lineTo(c - w, c - h * 0.6f);
            m.lineTo(c - w * 0.45f, c);
            m.lineTo(c, c - h);
            m.lineTo(c + w * 0.45f, c);
            m.lineTo(c + w, c - h * 0.6f);
            m.lineTo(c + w, c + h * 0.7f);
            m.close();
        } else {
            float o = r * 0.5f, n = o * 0.42f;
            for (int i = 0; i < 10; i++) {
                double a = -Math.PI / 2 + i * Math.PI / 5;
                float q = i % 2 == 0 ? o : n;
                float x = c + (float) Math.cos(a) * q, y = c + (float) Math.sin(a) * q;
                if (i == 0) m.moveTo(x, y);
                else m.lineTo(x, y);
            }
            m.close();
        }
        p.setColor(line);
        v.drawPath(m, p);
        p.setColor(0x66ffffff);
        v.drawOval(new RectF(c - r * 0.62f, c - r * 0.82f, c + r * 0.1f, c - r * 0.28f), p);
    }

    private static void diamond(Canvas v, int s, int light, int mid, int dark, int deep) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float w = s * 0.46f, c = s / 2f, top = s * 0.14f, belt = s * 0.38f, tip = s * 0.92f, inner = w * 0.42f;
        float[][][] facets = {
                {{c - w, belt}, {c - w * 0.55f, top}, {c - inner * 0.6f, belt}},
                {{c - w * 0.55f, top}, {c, top}, {c - inner * 0.6f, belt}},
                {{c, top}, {c + inner * 0.6f, belt}, {c - inner * 0.6f, belt}},
                {{c, top}, {c + w * 0.55f, top}, {c + inner * 0.6f, belt}},
                {{c + w * 0.55f, top}, {c + w, belt}, {c + inner * 0.6f, belt}},
                {{c - w, belt}, {c - inner * 0.6f, belt}, {c, tip}},
                {{c - inner * 0.6f, belt}, {c + inner * 0.6f, belt}, {c, tip}},
                {{c + inner * 0.6f, belt}, {c + w, belt}, {c, tip}},
        };
        int[] shades = {mid, light, Color.WHITE, light, mid, dark, mid, deep};
        for (int i = 0; i < facets.length; i++) {
            Path f = new Path();
            f.moveTo(facets[i][0][0], facets[i][0][1]);
            f.lineTo(facets[i][1][0], facets[i][1][1]);
            f.lineTo(facets[i][2][0], facets[i][2][1]);
            f.close();
            p.setColor(shades[i]);
            v.drawPath(f, p);
        }
        p.setColor(0x44ffffff);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.012f);
        for (float[][] facet : facets) {
            Path f = new Path();
            f.moveTo(facet[0][0], facet[0][1]);
            f.lineTo(facet[1][0], facet[1][1]);
            f.lineTo(facet[2][0], facet[2][1]);
            f.close();
            v.drawPath(f, p);
        }
    }
}
