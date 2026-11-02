package com.ludo3d.game.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.audio.Sfx;
import com.ludo3d.game.net.Api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

/**
 * The daily spin: a full-screen popup with a glowing wheel of ten diamond prizes (1 to 10).
 * It flies in with a bounce, spins on tap while the server picks the prize, slows down
 * slice by slice (the pointer ticks) and lands on the prize with confetti and flying
 * diamonds. The server pays the prize; the wheel only shows it.
 */
final class DailySpin extends Dialog {
    private static final String PREF_DAY = "spin_popup_day";

    /** Today's date in UTC, the day the server's daily spin resets on. */
    static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    /** Whether the popup already opened by itself today. */
    static boolean shownToday() {
        return today().equals(LudoApp.get().prefs.getString(PREF_DAY, ""));
    }

    static void show(MainActivity a, JSONObject state, Runnable onClose) {
        LudoApp.get().prefs.edit().putString(PREF_DAY, today()).apply();
        DailySpin d = new DailySpin(a, state);
        d.setOnDismissListener(x -> {
            if (onClose != null) onClose.run();
        });
        d.show();
    }

    private final MainActivity a;
    private final int[] prizes;
    private final FrameLayout stage;
    private final LinearLayout content;
    private final Rays rays;
    private final Wheel wheel;
    private final Pointer pointer;
    private final View hub;
    /** The big button: green SPIN, then gold COLLECT once the prize is in. */
    private final FrameLayout button;
    private final Fx.Tile collect;
    private final Fx.GoldText buttonLabel;
    private final View close;
    private final Confetti confetti;
    private final TextView subtitle;
    private ValueAnimator spinner;
    private boolean spinning, done;
    private long lastTick;
    private int lastSlice = Integer.MIN_VALUE;
    /** Degrees per second the wheel turns while it waits for the server. */
    private static final float CRUISE = 1100f;

    private DailySpin(MainActivity a, JSONObject state) {
        super(a);
        this.a = a;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        JSONArray p = state.optJSONArray("prizes");
        int n = p != null && p.length() > 1 ? p.length() : 10;
        prizes = new int[n];
        for (int i = 0; i < n; i++) prizes[i] = p != null ? p.optInt(i, i + 1) : i + 1;

        stage = new FrameLayout(a);
        rays = new Rays(a);
        int raySize = Ui.dp(a, 520);
        stage.addView(rays, new FrameLayout.LayoutParams(raySize, raySize, Gravity.CENTER));

        content = Ui.vbox(a);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        Fx.GoldText title = new Fx.GoldText(a, "DAILY SPIN", 38);
        title.setGravity(Gravity.CENTER);
        content.addView(title, Ui.lpMatchWrap());
        subtitle = Ui.bold(Ui.text(a, "Spin the wheel and win up to 10 💎 free, every day!", 14, 0xffffe9a8));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setShadowLayer(Ui.dp(a, 3), 0, Ui.dp(a, 1), 0xcc000000);
        Ui.margins(subtitle, a, 24, 2, 24, 10);
        content.addView(subtitle, Ui.lpMatchWrap());

        int screen = a.getResources().getDisplayMetrics().widthPixels;
        int size = Math.min(screen - Ui.dp(a, 36), Ui.dp(a, 330));
        FrameLayout wheelBox = new FrameLayout(a);
        wheel = new Wheel(a, prizes);
        wheelBox.addView(wheel, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        hub = new Hub(a);
        int hubSize = (int) (size * 0.25f);
        wheelBox.addView(hub, new FrameLayout.LayoutParams(hubSize, hubSize, Gravity.CENTER));
        hub.setOnClickListener(v -> spin());
        pointer = new Pointer(a);
        int pw = Ui.dp(a, 46), ph = Ui.dp(a, 58);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(pw, ph, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        wheelBox.addView(pointer, plp);
        pointer.setPivotX(pw / 2f);
        pointer.setPivotY(ph * 0.32f);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(size, size + Ui.dp(a, 18));
        content.addView(wheelBox, wlp);
        wheel.setTranslationY(Ui.dp(a, 18) / 2f);
        hub.setTranslationY(Ui.dp(a, 18) / 2f);

        button = new FrameLayout(a);
        Fx.Tile go = new Fx.Tile(a, Fx.GREEN, 20, 6);
        buttonLabel = Fx.GoldText.label(a, "SPIN NOW!", 22, 0xff1f6d0a);
        buttonLabel.setGravity(Gravity.CENTER);
        go.addView(buttonLabel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        go.setOnClickListener(v -> {
            if (done) dismissAnimated();
            else spin();
        });
        button.addView(go, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        collect = new Fx.Tile(a, Fx.YELLOW, 20, 6);
        Fx.GoldText collectLabel = Fx.GoldText.label(a, "COLLECT", 22, 0xffa66f00);
        collectLabel.setGravity(Gravity.CENTER);
        collect.addView(collectLabel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        collect.setOnClickListener(v -> dismissAnimated());
        collect.setVisibility(View.GONE);
        button.addView(collect, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(a, 230), Ui.dp(a, 62));
        blp.topMargin = Ui.dp(a, 16);
        content.addView(button, blp);
        stage.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        close = Fx.badge(a, "✖", Fx.RED, 42, v -> dismissAnimated());
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(Ui.dp(a, 42), Ui.dp(a, 46), Gravity.TOP | Gravity.END);
        clp.topMargin = Ui.dp(a, 28);
        clp.rightMargin = Ui.dp(a, 16);
        stage.addView(close, clp);

        confetti = new Confetti(a);
        stage.addView(confetti, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(stage);

        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.78f);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        setCanceledOnTouchOutside(false);
    }

    private static void sound(Sfx.S s, float volume, float rate) {
        Sfx sfx = Sfx.get();
        if (sfx != null) sfx.play(s, volume, rate);
    }

    @Override
    public void show() {
        super.show();
        // The wheel flies in spinning, the title drops, the button rises, the light rays bloom.
        content.setScaleX(0.3f);
        content.setScaleY(0.3f);
        content.setAlpha(0f);
        content.animate().scaleX(1).scaleY(1).alpha(1).setDuration(620).setInterpolator(new OvershootInterpolator(1.3f)).start();
        wheel.setRotation(-220);
        wheel.animate().rotation(0).setDuration(1100).setInterpolator(new DecelerateInterpolator(2f)).start();
        rays.setAlpha(0f);
        rays.setScaleX(0.4f);
        rays.setScaleY(0.4f);
        rays.animate().alpha(1).scaleX(1).scaleY(1).setStartDelay(200).setDuration(800).start();
        ObjectAnimator turn = ObjectAnimator.ofFloat(rays, "rotation", 0, 360);
        turn.setDuration(24000);
        turn.setRepeatCount(ValueAnimator.INFINITE);
        turn.setInterpolator(new LinearInterpolator());
        turn.start();
        close.setAlpha(0f);
        close.animate().alpha(1).setStartDelay(900).setDuration(300).start();
        // The button breathes until it is tapped.
        ObjectAnimator pulse = ObjectAnimator.ofPropertyValuesHolder(button,
                android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f, 1.07f, 1f),
                android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f, 1.07f, 1f));
        pulse.setDuration(1100);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setStartDelay(700);
        pulse.start();
        button.setTag(pulse);
        wheel.startLights();
        sound(Sfx.S.MATCH_FOUND, 0.7f, 1.1f);
    }

    @Override
    public void dismiss() {
        wheel.stopLights();
        Object pulse = button.getTag();
        if (pulse instanceof Animator) ((Animator) pulse).cancel();
        if (spinner != null) spinner.removeAllListeners();
        if (spinner != null) spinner.cancel();
        super.dismiss();
    }

    @Override
    public void onBackPressed() {
        if (!spinning) dismissAnimated();
    }

    private void dismissAnimated() {
        if (spinning) return;
        content.animate().scaleX(0.6f).scaleY(0.6f).alpha(0).setDuration(220).setInterpolator(new AccelerateInterpolator()).start();
        rays.animate().alpha(0).setDuration(220).start();
        close.animate().alpha(0).setDuration(150).start();
        LudoApp.MAIN.postDelayed(() -> {
            if (isShowing()) dismiss();
        }, 230);
    }

    // ------------------------------------------------------------------ spinning

    private void spin() {
        if (spinning || done) return;
        spinning = true;
        Object pulse = button.getTag();
        if (pulse instanceof Animator) ((Animator) pulse).cancel();
        button.animate().scaleX(0.92f).scaleY(0.92f).alpha(0.55f).setDuration(150).start();
        buttonLabel.setText("GOOD LUCK!");
        close.animate().alpha(0).setDuration(150).start();
        close.setEnabled(false);
        wheel.setFast(true);
        sound(Sfx.S.DICE_SHAKE, 0.5f, 1.3f);

        // Wind up, then cruise at a steady speed until the server answers.
        float start = wheel.getRotation();
        final long t0 = System.nanoTime();
        final float windUp = 0.6f;
        spinner = ValueAnimator.ofFloat(0, 1);
        spinner.setDuration(60_000);
        spinner.setInterpolator(new LinearInterpolator());
        spinner.addUpdateListener(an -> {
            float t = (System.nanoTime() - t0) / 1e9f;
            float rot = t < windUp ? start + CRUISE * t * t / (2 * windUp) : start + CRUISE * windUp / 2 + CRUISE * (t - windUp);
            setWheel(rot);
        });
        spinner.start();

        LudoApp.get().api.post("/daily-spin", null, new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                LudoApp.get().mergeWallet(r.optJSONObject("wallet"));
                if (r.has("spin")) a.spinState = r.optJSONObject("spin");
                int index = r.optInt("index", 0);
                int prize = r.optInt("prize", prizes[index]);
                // Let the wind-up finish so the hand-off to the slow-down is smooth.
                long waited = (System.nanoTime() - t0) / 1_000_000;
                LudoApp.MAIN.postDelayed(() -> land(index, prize), Math.max(0, (long) (windUp * 1000) - waited + 120));
            }

            @Override
            public void err(Api.Error e) {
                if (spinner != null) spinner.cancel();
                coast();
                spinning = false;
                LudoApp.toast(e.message);
                if (e.status == 409) {
                    a.spinState = null;
                    done = true;
                    buttonLabel.setText("CLOSE");
                } else {
                    buttonLabel.setText("TRY AGAIN");
                }
                button.animate().scaleX(1).scaleY(1).alpha(1).setDuration(150).start();
                close.setEnabled(true);
                close.animate().alpha(1).setDuration(200).start();
            }
        });
    }

    /** Slow down from cruising speed and stop with the prize's slice under the pointer. */
    private void land(int index, int prize) {
        if (spinner != null) spinner.cancel();
        float from = wheel.getRotation();
        float sweep = 360f / prizes.length;
        // Stop a little off the slice's centre so it feels natural.
        float jitter = (new Random().nextFloat() - 0.5f) * sweep * 0.5f;
        float target = mod(-index * sweep + jitter, 360);
        float factor = 1.6f;
        float dist = mod(target - from, 360) + 360 * 5;
        long duration = (long) (dist * 2 * factor / CRUISE * 1000);
        ValueAnimator slow = ValueAnimator.ofFloat(0, 1);
        slow.setDuration(duration);
        slow.setInterpolator(new DecelerateInterpolator(factor));
        slow.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            setWheel(from + dist * f);
            if (f > 0.7f) wheel.setFast(false);
        });
        slow.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                win(index, prize);
            }
        });
        spinner = slow;
        slow.start();
    }

    private void coast() {
        float from = wheel.getRotation();
        ValueAnimator c = ValueAnimator.ofFloat(0, 1);
        c.setDuration(900);
        c.setInterpolator(new DecelerateInterpolator(1.5f));
        c.addUpdateListener(an -> setWheel(from + 300 * (float) an.getAnimatedValue()));
        c.start();
        wheel.setFast(false);
    }

    private static float mod(float x, float m) {
        float r = x % m;
        return r < 0 ? r + m : r;
    }

    /** Turn the wheel; each slice that passes the pointer ticks and flicks it. */
    private void setWheel(float rot) {
        wheel.setRotation(rot);
        float sweep = 360f / prizes.length;
        int slice = (int) Math.floor((rot + sweep / 2) / sweep);
        if (slice != lastSlice) {
            if (lastSlice != Integer.MIN_VALUE) {
                pointer.animate().cancel();
                pointer.setRotation(-22);
                pointer.animate().rotation(0).setDuration(140).setInterpolator(new OvershootInterpolator(3f)).start();
                long now = System.currentTimeMillis();
                if (now - lastTick > 70) {
                    lastTick = now;
                    sound(Sfx.S.TICK, 0.35f, 1.4f);
                }
            }
            lastSlice = slice;
        }
    }

    private void win(int index, int prize) {
        if (!isShowing()) return;
        spinning = false;
        done = true;
        wheel.highlight(index);
        sound(Sfx.S.VICTORY, 0.9f, 1f);
        confetti.burst();

        // "+7 💎" pops out of the hub and floats over the wheel.
        LinearLayout prizeBox = Ui.vbox(a);
        prizeBox.setGravity(Gravity.CENTER);
        Fx.GoldText big = new Fx.GoldText(a, "+" + prize + " 💎", 56);
        big.setGravity(Gravity.CENTER);
        prizeBox.addView(big);
        Fx.GoldText sub = Fx.GoldText.label(a, prize >= 8 ? "JACKPOT!" : prize >= 5 ? "AWESOME!" : "NICE WIN!", 20, 0xff6b2b00);
        sub.setGravity(Gravity.CENTER);
        prizeBox.addView(sub);
        stage.addView(prizeBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        prizeBox.setScaleX(0f);
        prizeBox.setScaleY(0f);
        prizeBox.animate().scaleX(1).scaleY(1).setDuration(520).setInterpolator(new OvershootInterpolator(2.6f)).start();
        ObjectAnimator wobble = ObjectAnimator.ofFloat(prizeBox, "rotation", 0, -6, 6, -3, 0);
        wobble.setStartDelay(520);
        wobble.setDuration(700);
        wobble.start();

        // Diamonds burst from the wheel and fly up to the diamond counter.
        int count = Math.min(prize + 4, 12);
        for (int i = 0; i < count; i++) {
            TextView gem = Ui.text(a, "💎", 26, Color.WHITE);
            stage.addView(gem, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            double ang = Math.PI * 2 * i / count;
            float r = Ui.dp(a, 90 + (i % 3) * 25);
            gem.setScaleX(0.2f);
            gem.setScaleY(0.2f);
            gem.animate().translationX((float) Math.cos(ang) * r).translationY((float) Math.sin(ang) * r).scaleX(1.2f).scaleY(1.2f)
                    .setDuration(420).setStartDelay(150 + i * 25L).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> gem.animate().translationY(-stage.getHeight() / 2f).translationX(stage.getWidth() * 0.12f)
                            .scaleX(0.5f).scaleY(0.5f).alpha(0).setDuration(650).setStartDelay(500).setInterpolator(new AccelerateInterpolator(1.5f))
                            .withEndAction(() -> stage.removeView(gem)).start())
                    .start();
        }
        LudoApp.MAIN.postDelayed(() -> {
            a.refreshHeader();
            sound(Sfx.S.HOME, 0.8f, 1.2f);
        }, 1500);

        subtitle.setText("You won " + prize + " diamonds! Come back tomorrow for another spin.");
        collect.setVisibility(View.VISIBLE);
        button.animate().scaleX(1).scaleY(1).alpha(1).setDuration(250).setStartDelay(600).start();
        close.setEnabled(true);
        close.animate().alpha(1).setStartDelay(800).setDuration(250).start();
    }

    // ------------------------------------------------------------------ views

    /** The wheel: ten glossy slices, a gold rim with chasing light bulbs, prize labels. */
    static final class Wheel extends View {
        private static final int[][] SLICE = {
                {0xffff6ba6, 0xffd6156a}, {0xff7ee8ff, 0xff1b8fe8}, {0xffffe066, 0xfff59e0b}, {0xffa78bfa, 0xff6d28d9},
                {0xff86efac, 0xff16a34a}, {0xffff9a7a, 0xffe8461f}, {0xff67e8f9, 0xff0e7490}, {0xfff9a8d4, 0xffbe185d},
                {0xffc4b5fd, 0xff5b21b6}, {0xfffde047, 0xffca8a04},
        };
        private final int[] prizes;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint emoji = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private int phase;
        private boolean fast;
        private int highlight = -1;
        private float glow;
        private final Runnable blink = new Runnable() {
            @Override
            public void run() {
                phase++;
                invalidate();
                postDelayed(this, fast ? 80 : 280);
            }
        };
        private ValueAnimator glowAnim;

        Wheel(Context c, int[] prizes) {
            super(c);
            this.prizes = prizes;
            text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC));
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(Color.WHITE);
            stroke.setTypeface(text.getTypeface());
            stroke.setTextAlign(Paint.Align.CENTER);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(0xff3b0d5c);
            emoji.setTextAlign(Paint.Align.CENTER);
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }

        void startLights() {
            removeCallbacks(blink);
            post(blink);
        }

        void stopLights() {
            removeCallbacks(blink);
            if (glowAnim != null) glowAnim.cancel();
        }

        void setFast(boolean f) {
            fast = f;
        }

        void highlight(int i) {
            highlight = i;
            glowAnim = ValueAnimator.ofFloat(0, 1);
            glowAnim.setDuration(500);
            glowAnim.setRepeatMode(ValueAnimator.REVERSE);
            glowAnim.setRepeatCount(ValueAnimator.INFINITE);
            glowAnim.addUpdateListener(an -> {
                glow = (float) an.getAnimatedValue();
                invalidate();
            });
            glowAnim.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), cx = w / 2f, cy = getHeight() / 2f;
            float R = Math.min(w, getHeight()) / 2f;
            float rim = R * 0.11f;
            float r = R - rim;
            int n = prizes.length;
            float sweep = 360f / n;

            // Drop shadow and the gold rim.
            paint.setShader(new RadialGradient(cx, cy + R * 0.04f, R, new int[]{0x99000000, 0x00000000}, new float[]{0.9f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy + R * 0.04f, R, paint);
            paint.setShader(new SweepGradient(cx, cy, new int[]{0xfffff3b0, 0xffe0a106, 0xffffd84a, 0xffa86b00, 0xfffff3b0, 0xffe0a106, 0xfffff3b0}, null));
            c.drawCircle(cx, cy, R * 0.985f, paint);
            paint.setShader(null);
            paint.setColor(0xff7a3d00);
            c.drawCircle(cx, cy, r + rim * 0.18f, paint);

            // Slices, each with a soft radial shine.
            oval.set(cx - r, cy - r, cx + r, cy + r);
            for (int i = 0; i < n; i++) {
                int[] col = SLICE[i % SLICE.length];
                paint.setShader(new RadialGradient(cx, cy, r, new int[]{col[0], col[0], col[1]}, new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
                c.drawArc(oval, -90 - sweep / 2 + i * sweep, sweep, true, paint);
            }
            paint.setShader(null);
            // The winning slice flashes.
            if (highlight >= 0) {
                paint.setColor(Color.argb((int) (40 + 120 * glow), 255, 255, 255));
                c.drawArc(oval, -90 - sweep / 2 + highlight * sweep, sweep, true, paint);
            }
            // Separators.
            paint.setColor(0xccffffff);
            paint.setStrokeWidth(R * 0.012f);
            for (int i = 0; i < n; i++) {
                double ang = Math.toRadians(-90 - sweep / 2 + i * sweep);
                c.drawLine(cx, cy, cx + (float) Math.cos(ang) * r, cy + (float) Math.sin(ang) * r, paint);
            }
            // Gloss over the top half.
            paint.setShader(new LinearGradient(0, cy - r, 0, cy, 0x40ffffff, 0x00ffffff, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, paint);
            paint.setShader(null);

            // Labels: the prize big near the rim, a diamond under it.
            for (int i = 0; i < n; i++) {
                c.save();
                c.rotate(i * sweep, cx, cy);
                float size = R * (prizes[i] == 10 ? 0.2f : 0.18f);
                text.setTextSize(size);
                stroke.setTextSize(size);
                stroke.setStrokeWidth(size * 0.18f);
                String label = String.valueOf(prizes[i]);
                float y = cy - r * 0.72f;
                c.drawText(label, cx, y, stroke);
                c.drawText(label, cx, y, text);
                emoji.setTextSize(R * 0.12f);
                c.drawText("💎", cx, cy - r * 0.42f, emoji);
                c.restore();
            }

            // Light bulbs round the rim, chasing.
            int bulbs = n * 2;
            float br = rim * 0.24f;
            for (int i = 0; i < bulbs; i++) {
                double ang = Math.toRadians(-90 + i * 360f / bulbs);
                float bx = cx + (float) Math.cos(ang) * (r + rim * 0.55f);
                float by = cy + (float) Math.sin(ang) * (r + rim * 0.55f);
                boolean on = (i + phase) % 2 == 0;
                if (on) {
                    paint.setShader(new RadialGradient(bx, by, br * 2.6f, new int[]{0xccfffbe0, 0x00fff3a0}, null, Shader.TileMode.CLAMP));
                    c.drawCircle(bx, by, br * 2.6f, paint);
                    paint.setShader(null);
                }
                paint.setColor(on ? 0xfffffbe6 : 0xffb45309);
                c.drawCircle(bx, by, br, paint);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stopLights();
        }
    }

    /** The centre cap: a glossy gold button with a star. */
    static final class Hub extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Hub(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
            p.setShader(new RadialGradient(cx, cy + r * 0.1f, r, new int[]{0x88000000, 0x00000000}, new float[]{0.8f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy + r * 0.06f, r, p);
            p.setShader(new LinearGradient(0, cy - r, 0, cy + r, new int[]{0xfffff3b0, 0xfff5b400, 0xffa66f00}, null, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r * 0.9f, p);
            p.setShader(new LinearGradient(0, cy - r, 0, cy + r, new int[]{0xff9f6bff, 0xff4c1d95}, null, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r * 0.72f, p);
            p.setShader(null);
            // A gold star.
            Path star = new Path();
            for (int i = 0; i < 10; i++) {
                double ang = Math.toRadians(-90 + i * 36);
                float rr = i % 2 == 0 ? r * 0.48f : r * 0.2f;
                float x = cx + (float) Math.cos(ang) * rr, y = cy + (float) Math.sin(ang) * rr;
                if (i == 0) star.moveTo(x, y);
                else star.lineTo(x, y);
            }
            star.close();
            p.setShader(new LinearGradient(0, cy - r * 0.5f, 0, cy + r * 0.5f, 0xfffffbe0, 0xffffb300, Shader.TileMode.CLAMP));
            c.drawPath(star, p);
            p.setShader(null);
            p.setColor(0x55ffffff);
            c.drawCircle(cx - r * 0.25f, cy - r * 0.3f, r * 0.14f, p);
        }
    }

    /** The red-and-gold pointer at the top that flicks as slices pass. */
    static final class Pointer extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Pointer(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            Path tri = new Path();
            tri.moveTo(w * 0.12f, h * 0.18f);
            tri.lineTo(w * 0.88f, h * 0.18f);
            tri.lineTo(w * 0.5f, h * 0.98f);
            tri.close();
            p.setShader(null);
            p.setColor(0x66000000);
            c.save();
            c.translate(0, h * 0.04f);
            c.drawPath(tri, p);
            c.restore();
            p.setShader(new LinearGradient(0, 0, 0, h, 0xfffff3b0, 0xffd18b00, Shader.TileMode.CLAMP));
            c.drawPath(tri, p);
            Path inner = new Path();
            inner.moveTo(w * 0.24f, h * 0.24f);
            inner.lineTo(w * 0.76f, h * 0.24f);
            inner.lineTo(w * 0.5f, h * 0.84f);
            inner.close();
            p.setShader(new LinearGradient(0, 0, 0, h, 0xffff6b6b, 0xffb91c1c, Shader.TileMode.CLAMP));
            c.drawPath(inner, p);
            // The pin it hangs from.
            p.setShader(new RadialGradient(w * 0.45f, h * 0.26f, w * 0.22f, 0xfffffbe0, 0xffd18b00, Shader.TileMode.CLAMP));
            c.drawCircle(w * 0.5f, h * 0.3f, w * 0.17f, p);
            p.setShader(null);
        }
    }

    /** Slowly turning golden light rays behind the wheel. */
    static final class Rays extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Rays(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
            p.setShader(new RadialGradient(cx, cy, r, new int[]{0x99ffd54a, 0x33ff9f0a, 0x00ff9f0a}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            RectF o = new RectF(cx - r, cy - r, cx + r, cy + r);
            int n = 18;
            for (int i = 0; i < n; i++) c.drawArc(o, i * 360f / n, 360f / n / 2, true, p);
            p.setShader(new RadialGradient(cx, cy, r * 0.75f, new int[]{0x66c084fc, 0x00c084fc}, null, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r * 0.75f, p);
            p.setShader(null);
        }
    }

    /** A burst of confetti that falls with gravity and spins. */
    static final class Confetti extends View {
        private static final int[] COLORS = {0xffff4d6d, 0xffffd60a, 0xff4cc9f0, 0xff80ed99, 0xffc77dff, 0xffff9e00, Color.WHITE};
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<float[]> bits = new ArrayList<>();
        private long last;

        Confetti(Context c) {
            super(c);
            setClickable(false);
            setFocusable(false);
        }

        void burst() {
            Random rnd = new Random();
            float w = getWidth(), h = getHeight();
            float d = getResources().getDisplayMetrics().density;
            for (int i = 0; i < 140; i++) {
                double ang = -Math.PI / 2 + (rnd.nextFloat() - 0.5f) * Math.PI * 1.4;
                float speed = (300 + rnd.nextFloat() * 700) * d;
                // x, y, vx, vy, rotation, spin, size, colour, shape
                bits.add(new float[]{w / 2 + (rnd.nextFloat() - 0.5f) * w * 0.3f, h * 0.45f, (float) Math.cos(ang) * speed, (float) Math.sin(ang) * speed,
                        rnd.nextFloat() * 360, (rnd.nextFloat() - 0.5f) * 900, (5 + rnd.nextFloat() * 6) * d, COLORS[rnd.nextInt(COLORS.length)], rnd.nextInt(3)});
            }
            last = System.nanoTime();
            postInvalidateOnAnimation();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (bits.isEmpty()) return;
            long now = System.nanoTime();
            float dt = Math.min(0.05f, (now - last) / 1e9f);
            last = now;
            float g = 900 * getResources().getDisplayMetrics().density;
            for (int i = bits.size() - 1; i >= 0; i--) {
                float[] b = bits.get(i);
                b[3] += g * dt;
                b[2] *= 0.99f;
                b[0] += b[2] * dt;
                b[1] += b[3] * dt;
                b[4] += b[5] * dt;
                if (b[1] > getHeight() + 40) {
                    bits.remove(i);
                    continue;
                }
                p.setColor((int) b[7]);
                c.save();
                c.translate(b[0], b[1]);
                c.rotate(b[4]);
                float s = b[6];
                // Flutter: squash one axis as the piece turns.
                c.scale(1f, (float) Math.abs(Math.cos(Math.toRadians(b[4] * 2))) * 0.8f + 0.2f);
                if (b[8] == 0) c.drawRect(-s, -s / 2, s, s / 2, p);
                else if (b[8] == 1) c.drawCircle(0, 0, s * 0.6f, p);
                else {
                    Path t = new Path();
                    t.moveTo(0, -s);
                    t.lineTo(s, s);
                    t.lineTo(-s, s);
                    t.close();
                    c.drawPath(t, p);
                }
                c.restore();
            }
            if (!bits.isEmpty()) postInvalidateOnAnimation();
        }
    }
}
