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
    /** The card with its close button: what flies in and out. */
    private final View panel;
    /** The dark backdrop; tapping it closes the popup. */
    private final View scrim;
    private final View shine;

    private Announcement(Activity a, JSONObject ann) {
        // A full-screen, see-through window that centres the card itself, so no dialog
        // theme (insets, minimum widths) can push it to one side.
        super(a, android.R.style.Theme_Translucent_NoTitleBar);
        setCancelable(true);

        android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
        int screenW = dm.widthPixels, screenH = dm.heightPixels;
        boolean small = screenW < Ui.dp(a, 360);
        // The card: the screen less a margin each side, at most 400dp (tablets).
        int width = Math.min(screenW - Ui.dp(a, 40), Ui.dp(a, 400));
        int corner = Ui.dp(a, 22);

        FrameLayout root = new FrameLayout(a);
        scrim = new View(a);
        scrim.setBackgroundColor(0xcc0a0320);
        scrim.setOnClickListener(v -> close());
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout safe = new FrameLayout(a);
        safe.setFitsSystemWindows(true);
        root.addView(safe, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        frame = new GlowFrame(a);
        int pad = Ui.dp(a, 4);
        frame.setPadding(pad, pad, pad, pad);

        LinearLayout card = Ui.vbox(a);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xff6a3fd0, 0xff3b1680, 0xff26104f});
        bg.setCornerRadius(corner);
        card.setBackground(bg);
        card.setClipToOutline(true);

        String image = ann.isNull("imageUrl") ? "" : ann.optString("imageUrl", "");
        if (!image.isEmpty()) {
            ImageView pic = new RoundTopImage(a, corner);
            pic.setAdjustViewBounds(true);
            pic.setScaleType(ImageView.ScaleType.FIT_CENTER);
            pic.setMaxHeight((int) (screenH * 0.38f));
            pic.setMinimumHeight(Ui.dp(a, 140));
            pic.setBackgroundColor(0x33000000);
            card.addView(pic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            loadImage(a, image.startsWith("/") ? LudoApp.get().serverUrl() + image : image, pic, width);
        } else {
            // No picture: a gold megaphone badge heads the card.
            Fx.Tile badge = Fx.badge(a, "📢", Fx.YELLOW, 64, null);
            badge.setClickable(false);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(a, 64), Ui.dp(a, 68));
            blp.topMargin = Ui.dp(a, 18);
            card.addView(badge, blp);
        }

        LinearLayout text = Ui.vbox(a);
        text.setGravity(Gravity.CENTER_HORIZONTAL);
        text.setPadding(Ui.dp(a, 18), Ui.dp(a, 12), Ui.dp(a, 18), Ui.dp(a, 18));
        Fx.GoldText title = new Fx.GoldText(a, ann.optString("title"), small ? 21 : 24);
        title.setGravity(Gravity.CENTER);
        text.addView(title, Ui.lpMatchWrap());
        String body = ann.optString("body", "");
        if (!body.isEmpty()) {
            TextView t = Ui.text(a, body, small ? 14 : 15, 0xfff3eeff);
            t.setGravity(Gravity.CENTER);
            t.setLineSpacing(0, 1.25f);
            // Long text scrolls inside the card instead of pushing it off the screen.
            ScrollView sv = new MaxHeightScroll(a, (int) (screenH * (image.isEmpty() ? 0.42f : 0.2f)));
            sv.addView(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(a, 8);
            text.addView(sv, lp);
        }
        FrameLayout okBox = new FrameLayout(a);
        Fx.Tile ok = new Fx.Tile(a, Fx.GREEN, 18, 5);
        Fx.GoldText label = Fx.GoldText.label(a, ann.optString("buttonText", "OK").toUpperCase(), 18, 0xff1f6d0a);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        ok.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ok.setOnClickListener(v -> close());
        okBox.addView(ok, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // A gleam that sweeps across the button now and then.
        shine = new Shine(a);
        okBox.addView(shine, new FrameLayout.LayoutParams(Ui.dp(a, 46), ViewGroup.LayoutParams.MATCH_PARENT));
        okBox.setClipChildren(true);
        okBox.setClipToOutline(true);
        okBox.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight() - Ui.dp(v.getContext(), 5), Ui.dp(v.getContext(), 18));
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 56));
        blp.topMargin = Ui.dp(a, 16);
        blp.leftMargin = Ui.dp(a, 12);
        blp.rightMargin = Ui.dp(a, 12);
        text.addView(okBox, blp);
        card.addView(text, Ui.lpMatchWrap());
        frame.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The card with the close button on its corner; equal room both sides keeps it centred.
        int edge = Ui.dp(a, 14);
        FrameLayout holder = new FrameLayout(a);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
        flp.topMargin = edge;
        flp.leftMargin = edge;
        flp.rightMargin = edge;
        holder.addView(frame, flp);
        View x = Fx.badge(a, "✖", Fx.RED, 38, v -> close());
        holder.addView(x, new FrameLayout.LayoutParams(Ui.dp(a, 38), Ui.dp(a, 42), Gravity.TOP | Gravity.END));
        panel = holder;
        safe.addView(holder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);

        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    @Override
    public void show() {
        super.show();
        scrim.setAlpha(0f);
        scrim.animate().alpha(1).setDuration(280).start();
        panel.setTranslationY(Ui.dp(getContext(), 180));
        panel.setScaleX(0.75f);
        panel.setScaleY(0.75f);
        panel.setAlpha(0f);
        panel.animate().translationY(0).scaleX(1).scaleY(1).alpha(1).setDuration(520).setInterpolator(new OvershootInterpolator(1.2f)).start();
        frame.start();
        sweep();
        Sfx s = Sfx.get();
        if (s != null) s.play(Sfx.S.MESSAGE, 0.8f, 1f);
    }

    /** Run the button's gleam across, then again a little later. */
    private void sweep() {
        View box = (View) shine.getParent();
        if (box == null || !isShowing()) return;
        shine.setTranslationX(-shine.getWidth() - Ui.dp(getContext(), 20));
        shine.animate().translationX(box.getWidth() + Ui.dp(getContext(), 20)).setStartDelay(900).setDuration(750)
                .setInterpolator(new DecelerateInterpolator()).withEndAction(this::sweep).start();
    }

    @Override
    public void onBackPressed() {
        close();
    }

    private void close() {
        scrim.animate().alpha(0).setDuration(220).start();
        panel.animate().translationY(Ui.dp(getContext(), 80)).scaleX(0.85f).scaleY(0.85f).alpha(0).setDuration(200)
                .setInterpolator(new AccelerateInterpolator()).withEndAction(() -> {
                    if (isShowing()) dismiss();
                }).start();
    }

    @Override
    public void dismiss() {
        frame.stop();
        shine.animate().cancel();
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

    /** A scroll view that grows with its text up to a maximum height. */
    static final class MaxHeightScroll extends ScrollView {
        private final int max;

        MaxHeightScroll(Context c, int max) {
            super(c);
            this.max = max;
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
        }
    }

    /** A slanted white gleam (moved across the button). */
    static final class Shine extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Shine(Context c) {
            super(c);
            setClickable(false);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setShader(new android.graphics.LinearGradient(0, 0, w, 0, new int[]{0x00ffffff, 0x88ffffff, 0x00ffffff}, null, android.graphics.Shader.TileMode.CLAMP));
            Path band = new Path();
            band.moveTo(w * 0.35f, 0);
            band.lineTo(w, 0);
            band.lineTo(w * 0.65f, h);
            band.lineTo(0, h);
            band.close();
            c.drawPath(band, p);
        }
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
