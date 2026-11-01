package com.ludo3d.game.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Small view toolkit: the screens are built in code with the web app's dark "glass" look. */
public final class Ui {
    private Ui() {}

    public static final int BG = 0xff0b0e2a;
    public static final int CARD = 0xff161b45;
    public static final int CARD_2 = 0xff1f2560;
    public static final int STROKE = 0x24ffffff;
    public static final int INK = 0xfff4f6ff;
    public static final int MUTED = 0xffa3a8cf;
    public static final int GOLD = 0xffffb547;
    public static final int PRIMARY = 0xff2f7cf0;
    public static final int DANGER = 0xffe53945;
    public static final int SUCCESS = 0xff22b46b;

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    public static GradientDrawable roundStroke(int color, float radiusPx, int stroke, int strokeColor) {
        GradientDrawable g = round(color, radiusPx);
        g.setStroke(stroke, strokeColor);
        return g;
    }

    public static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpMatchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    public static <T extends View> T margins(T v, Context c, int l, int t, int r, int b) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        ViewGroup.MarginLayoutParams m = p instanceof ViewGroup.MarginLayoutParams ? (ViewGroup.MarginLayoutParams) p : lpMatchWrap();
        m.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        v.setLayoutParams(m);
        return v;
    }

    public static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    public static TextView title(Context c, CharSequence s) {
        TextView t = text(c, s, 24, INK);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static TextView heading(Context c, CharSequence s) {
        TextView t = text(c, s, 17, INK);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(c, 18), 0, dp(c, 8));
        return t;
    }

    public static TextView muted(Context c, CharSequence s) {
        return text(c, s, 14, MUTED);
    }

    public static TextView bold(TextView t) {
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Pill button. Styles: "primary" (gold), "blue", "glass", "danger", "ghost". */
    public static TextView button(Context c, CharSequence label, String style, View.OnClickListener onClick) {
        TextView b = text(c, label, 15, INK);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12));
        int bg;
        switch (style) {
            case "primary":
                bg = GOLD;
                b.setTextColor(0xff2a1600);
                break;
            case "blue":
                bg = PRIMARY;
                break;
            case "danger":
                bg = DANGER;
                break;
            case "ghost":
                bg = Color.TRANSPARENT;
                b.setTextColor(MUTED);
                break;
            default:
                bg = CARD_2;
        }
        GradientDrawable shape = round(bg, dp(c, 999));
        if ("glass".equals(style)) shape.setStroke(dp(c, 1), STROKE);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), shape, round(Color.WHITE, dp(c, 999))));
        b.setOnClickListener(onClick);
        b.setClickable(true);
        return b;
    }

    public static TextView smallButton(Context c, CharSequence label, String style, View.OnClickListener onClick) {
        TextView b = button(c, label, style, onClick);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setPadding(dp(c, 12), dp(c, 7), dp(c, 12), dp(c, 7));
        return b;
    }

    public static void setEnabled(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.45f);
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = vbox(c);
        l.setBackground(roundStroke(CARD, dp(c, 18), dp(c, 1), STROKE));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = lpMatchWrap();
        lp.bottomMargin = dp(c, 12);
        l.setLayoutParams(lp);
        return l;
    }

    public static EditText input(Context c, String hint, int inputType) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(0x88a3a8cf);
        e.setTextColor(INK);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setInputType(inputType);
        // Password inputs default to a monospace font; keep the app font.
        e.setTypeface(Typeface.DEFAULT);
        e.setSingleLine(inputType != (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        e.setBackground(roundStroke(0xff10143a, dp(c, 12), dp(c, 1), STROKE));
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        LinearLayout.LayoutParams lp = lpMatchWrap();
        lp.bottomMargin = dp(c, 10);
        e.setLayoutParams(lp);
        return e;
    }

    public static TextView label(Context c, String s) {
        TextView t = text(c, s, 13, MUTED);
        t.setPadding(dp(c, 2), dp(c, 4), 0, dp(c, 4));
        return t;
    }

    /** A vertical scrolling page with standard padding; returns the inner column. */
    public static LinearLayout scrollPage(Context c, ViewGroup parent) {
        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        LinearLayout col = vbox(c);
        int p = dp(c, 16);
        col.setPadding(p, p, p, dp(c, 24));
        sv.addView(col, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return col;
    }

    public static View spinner(Context c) {
        FrameLayout f = new FrameLayout(c);
        ProgressBar pb = new ProgressBar(c);
        f.addView(pb, new FrameLayout.LayoutParams(dp(c, 36), dp(c, 36), Gravity.CENTER));
        f.setPadding(0, dp(c, 24), 0, dp(c, 24));
        return f;
    }

    /** Round avatar with the player's initial on their chosen hue (or a seat colour). */
    public static TextView avatar(Context c, String name, int hue, int sizeDp) {
        return avatarColor(c, name, Color.HSVToColor(new float[]{hue, 0.62f, 0.78f}), sizeDp);
    }

    public static TextView avatarColor(Context c, String name, int color, int sizeDp) {
        // First code point, so an emoji (e.g. the bot's 🤖) is not cut in half.
        String initial = name == null || name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
        TextView t = text(c, initial, sizeDp * 0.42f, Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        t.setBackground(g);
        int s = dp(c, sizeDp);
        t.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return t;
    }

    public static TextView tag(Context c, String s, int color) {
        TextView t = text(c, s, 11, color);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2));
        t.setBackground(roundStroke(0x14ffffff, dp(c, 999), dp(c, 1), color & 0x66ffffff));
        return t;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(STROKE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        lp.topMargin = dp(c, 8);
        lp.bottomMargin = dp(c, 8);
        v.setLayoutParams(lp);
        return v;
    }

    public static String fmt(long n) {
        return NumberFormat.getIntegerInstance(Locale.US).format(n);
    }

    public static String coins(long n) {
        return "🪙 " + fmt(n);
    }

    private static Date parseIso(String iso) {
        if (iso == null || iso.isEmpty()) return null;
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX", "yyyy-MM-dd HH:mm:ss"};
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                return f.parse(iso);
            } catch (Exception ignored) {
                // try the next format
            }
        }
        return null;
    }

    public static String timeAgo(String iso) {
        Date d = parseIso(iso);
        if (d == null) return "";
        long s = (System.currentTimeMillis() - d.getTime()) / 1000;
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + "m ago";
        if (s < 86400) return (s / 3600) + "h ago";
        if (s < 86400 * 7) return (s / 86400) + "d ago";
        return new SimpleDateFormat("d MMM yyyy", Locale.US).format(d);
    }

    public static String date(String iso) {
        Date d = parseIso(iso);
        return d == null ? "" : new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(d);
    }

    public static LinearLayout row(Context c) {
        LinearLayout r = hbox(c);
        r.setPadding(0, dp(c, 8), 0, dp(c, 8));
        r.setLayoutParams(lpMatchWrap());
        return r;
    }

    public static View gap(Context c, int w) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, w), dp(c, w)));
        return v;
    }
}
