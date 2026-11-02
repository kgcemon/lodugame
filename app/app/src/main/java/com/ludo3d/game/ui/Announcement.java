package com.ludo3d.game.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SweepGradient;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.audio.Sfx;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

/**
 * A popup an administrator posts for every player (text, a picture, or both). It rises
 * in with a bounce inside a glowing, slowly turning rainbow-gold frame; the picture fades
 * and settles in once it has loaded. Each announcement shows once per phone.
 */
final class Announcement extends Dialog {
    private static final String PREF_SEEN = "announcement_seen";

    /** Whether this phone already showed the announcement. */
    static boolean seen(JSONObject ann) {
        return ann.optString("id").equals(LudoApp.get().prefs.getString(PREF_SEEN, ""));
    }

    /** The popup for this announcement, marked as seen on this phone. */
    static Announcement create(Activity a, JSONObject ann) {
        LudoApp.get().prefs.edit().putString(PREF_SEEN, ann.optString("id")).apply();
        return new Announcement(a, ann);
    }

    private final GlowFrame frame;
    private final View panel;

    private Announcement(Activity a, JSONObject ann) {
        super(a);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setCancelable(true);
        setCanceledOnTouchOutside(true);

        int screenW = a.getResources().getDisplayMetrics().widthPixels;
        int screenH = a.getResources().getDisplayMetrics().heightPixels;
        int width = Math.min(screenW - Ui.dp(a, 36), Ui.dp(a, 420));

        FrameLayout outer = new FrameLayout(a);
        frame = new GlowFrame(a);
        int pad = Ui.dp(a, 4);
        frame.setPadding(pad, pad, pad, pad);

        LinearLayout card = Ui.vbox(a);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xff5a32b8, 0xff2e1065});
        bg.setCornerRadius(Ui.dp(a, 22));
        card.setBackground(bg);
        card.setClipToOutline(true);

        String image = ann.isNull("imageUrl") ? "" : ann.optString("imageUrl", "");
        ImageView pic = null;
        if (!image.isEmpty()) {
            pic = new RoundTopImage(a, Ui.dp(a, 22));
            pic.setAdjustViewBounds(true);
            pic.setScaleType(ImageView.ScaleType.FIT_CENTER);
            pic.setMaxHeight((int) (screenH * 0.45f));
            pic.setMinimumHeight(Ui.dp(a, 160));
            pic.setBackgroundColor(0x33000000);
            card.addView(pic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            loadImage(a, image.startsWith("/") ? LudoApp.get().serverUrl() + image : image, pic, width);
        }

        LinearLayout text = Ui.vbox(a);
        text.setGravity(Gravity.CENTER_HORIZONTAL);
        text.setPadding(Ui.dp(a, 18), Ui.dp(a, 14), Ui.dp(a, 18), Ui.dp(a, 18));
        Fx.GoldText title = new Fx.GoldText(a, ann.optString("title"), 24);
        title.setGravity(Gravity.CENTER);
        text.addView(title, Ui.lpMatchWrap());
        String body = ann.optString("body", "");
        if (!body.isEmpty()) {
            TextView t = Ui.text(a, body, 15, Color.WHITE);
            t.setGravity(Gravity.CENTER);
            t.setLineSpacing(0, 1.2f);
            ScrollView sv = new ScrollView(a);
            sv.addView(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(a, 8);
            text.addView(sv, lp);
            if (image.isEmpty()) sv.setMinimumHeight(Ui.dp(a, 40));
        }
        Fx.Tile ok = new Fx.Tile(a, Fx.GREEN, 16, 5);
        Fx.GoldText label = Fx.GoldText.label(a, ann.optString("buttonText", "OK").toUpperCase(), 17, 0xff1f6d0a);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        ok.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ok.setOnClickListener(v -> close());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(a, 200), Ui.dp(a, 54));
        blp.topMargin = Ui.dp(a, 16);
        text.addView(ok, blp);
        card.addView(text, Ui.lpMatchWrap());

        frame.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        flp.topMargin = Ui.dp(a, 16);
        flp.rightMargin = Ui.dp(a, 8);
        outer.addView(frame, flp);
        View x = Fx.badge(a, "✖", Fx.RED, 38, v -> close());
        outer.addView(x, new FrameLayout.LayoutParams(Ui.dp(a, 38), Ui.dp(a, 42), Gravity.TOP | Gravity.END));
        panel = outer;
        setContentView(outer);

        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.7f);
            w.setLayout(width + Ui.dp(a, 8), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    @Override
    public void show() {
        super.show();
        panel.setTranslationY(Ui.dp(getContext(), 220));
        panel.setScaleX(0.7f);
        panel.setScaleY(0.7f);
        panel.setAlpha(0f);
        panel.animate().translationY(0).scaleX(1).scaleY(1).alpha(1).setDuration(520).setInterpolator(new OvershootInterpolator(1.2f)).start();
        frame.start();
        Sfx s = Sfx.get();
        if (s != null) s.play(Sfx.S.MESSAGE, 0.8f, 1f);
    }

    private void close() {
        panel.animate().translationY(Ui.dp(getContext(), 80)).scaleX(0.85f).scaleY(0.85f).alpha(0).setDuration(200)
                .setInterpolator(new AccelerateInterpolator()).withEndAction(() -> {
                    if (isShowing()) dismiss();
                }).start();
    }

    @Override
    public void dismiss() {
        frame.stop();
        super.dismiss();
    }

    /** Download the picture (scaled to the popup's width) and fade it in with a gentle zoom. */
    private static void loadImage(Activity a, String url, ImageView into, int width) {
        Request req;
        try {
            req = new Request.Builder().url(url).build();
        } catch (IllegalArgumentException e) {
            into.setVisibility(View.GONE);
            return;
        }
        LudoApp.get().api.http.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                LudoApp.MAIN.post(() -> into.setVisibility(View.GONE));
            }

            @Override
            public void onResponse(Call call, Response res) {
                Bitmap b = null;
                try (Response r = res) {
                    if (r.isSuccessful() && r.body() != null) {
                        byte[] bytes = r.body().bytes();
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                        int sample = 1;
                        while (o.outWidth / (sample * 2) >= width) sample *= 2;
                        o = new BitmapFactory.Options();
                        o.inSampleSize = sample;
                        b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                    }
                } catch (Exception | OutOfMemoryError ignored) {
                    b = null;
                }
                Bitmap bitmap = b;
                LudoApp.MAIN.post(() -> {
                    if (a.isFinishing() || a.isDestroyed()) return;
                    if (bitmap == null) {
                        into.setVisibility(View.GONE);
                        return;
                    }
                    into.setBackgroundColor(Color.TRANSPARENT);
                    into.setImageBitmap(bitmap);
                    into.setAlpha(0f);
                    into.setScaleX(1.12f);
                    into.setScaleY(1.12f);
                    into.animate().alpha(1).scaleX(1).scaleY(1).setDuration(700).setInterpolator(new DecelerateInterpolator()).start();
                });
            }
        });
    }

    /** A picture with rounded top corners, to sit flush at the top of the card. */
    static final class RoundTopImage extends ImageView {
        private final float radius;
        private final Path clip = new Path();

        RoundTopImage(Context c, float radius) {
            super(c);
            this.radius = radius;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            clip.reset();
            clip.addRoundRect(new RectF(0, 0, getWidth(), getHeight()), new float[]{radius, radius, radius, radius, 0, 0, 0, 0}, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            super.onDraw(canvas);
            canvas.restore();
        }
    }

    /** A rounded frame whose gold-and-rainbow glow turns slowly round the card. */
    static final class GlowFrame extends FrameLayout {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float angle;
        private ValueAnimator anim;

        GlowFrame(Context c) {
            super(c);
            setWillNotDraw(false);
        }

        void start() {
            anim = ValueAnimator.ofFloat(0, 360);
            anim.setDuration(4000);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(v -> {
                angle = (float) v.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        void stop() {
            if (anim != null) anim.cancel();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float r = Ui.dp(getContext(), 25);
            SweepGradient g = new SweepGradient(w / 2, h / 2,
                    new int[]{0xffffd54a, 0xffff4d9d, 0xff8b5cf6, 0xff38bdf8, 0xff4ade80, 0xffffd54a}, null);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setRotate(angle, w / 2, h / 2);
            g.setLocalMatrix(m);
            p.setShader(g);
            c.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stop();
        }
    }
}
