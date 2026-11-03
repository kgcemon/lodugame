package com.ludox.game.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ludox.game.audio.Sfx;

/**
 * The game's popup: a 3D purple panel with a gold title, any content, and chunky 3D
 * buttons. It pops in with a small bounce. Replaces the system AlertDialog everywhere.
 */
final class Popup extends Dialog {
    /** A popup button. Styles: "ok" (green), "danger" (red), "plain" (purple), "gold". */
    static final class Btn {
        final String label, style;
        final Runnable action;
        boolean keepOpen;

        Btn(String label, String style, Runnable action) {
            this.label = label;
            this.style = style;
            this.action = action;
        }

        Btn keepOpen() {
            keepOpen = true;
            return this;
        }
    }

    static Btn ok(String label, Runnable action) {
        return new Btn(label, "ok", action);
    }

    static Btn danger(String label, Runnable action) {
        return new Btn(label, "danger", action);
    }

    static Btn plain(String label, Runnable action) {
        return new Btn(label, "plain", action);
    }

    private final View panel;

    private Popup(Activity a, String title, View body, boolean cancelable, Btn... buttons) {
        super(a);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setCancelable(cancelable);
        setCanceledOnTouchOutside(cancelable);

        Fx.Tile card = new Fx.Tile(a, Fx.PANEL, 24, 7);
        card.setClickable(false);
        LinearLayout col = Ui.vbox(a);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Ui.dp(a, 18), Ui.dp(a, 16), Ui.dp(a, 18), Ui.dp(a, 16));
        if (title != null) {
            Fx.GoldText t = new Fx.GoldText(a, title, 22);
            t.setGravity(Gravity.CENTER);
            col.addView(t, Ui.lpMatchWrap());
        }
        if (body != null) {
            ScrollView sv = new ScrollView(a);
            sv.setFillViewport(false);
            sv.addView(body);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.topMargin = Ui.dp(a, 10);
            col.addView(sv, lp);
        }
        if (buttons.length > 0) {
            // Two buttons side by side; three or more stacked.
            LinearLayout row = buttons.length <= 2 ? Ui.hbox(a) : Ui.vbox(a);
            for (int i = 0; i < buttons.length; i++) {
                Btn b = buttons[i];
                Fx.Tile t = new Fx.Tile(a, b.style.equals("ok") ? Fx.GREEN : b.style.equals("danger") ? Fx.RED
                        : b.style.equals("gold") ? Fx.YELLOW : Fx.PURPLE, 16, 5);
                int outline = b.style.equals("ok") ? 0xff1f6d0a : b.style.equals("danger") ? 0xff8a0d16 : b.style.equals("gold") ? 0xffa66f00 : 0xff3b0d8c;
                Fx.GoldText l = Fx.GoldText.label(a, b.label.toUpperCase(), 15, outline);
                l.setGravity(Gravity.CENTER);
                l.setSingleLine(true);
                t.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
                t.setOnClickListener(v -> {
                    if (!b.keepOpen) dismiss();
                    if (b.action != null) b.action.run();
                });
                LinearLayout.LayoutParams lp;
                if (buttons.length <= 2) {
                    lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 52), 1);
                    if (i > 0) lp.leftMargin = Ui.dp(a, 10);
                } else {
                    lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 52));
                    if (i > 0) lp.topMargin = Ui.dp(a, 8);
                }
                row.addView(t, lp);
            }
            LinearLayout.LayoutParams rlp = Ui.lpMatchWrap();
            rlp.topMargin = Ui.dp(a, 16);
            col.addView(row, rlp);
        }
        card.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel = card;
        setContentView(card);

        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.62f);
            int screen = a.getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(screen - Ui.dp(a, 32), Ui.dp(a, 420)), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    @Override
    public void show() {
        super.show();
        panel.setScaleX(0.75f);
        panel.setScaleY(0.75f);
        panel.setAlpha(0f);
        panel.animate().scaleX(1).scaleY(1).alpha(1).setDuration(260).setInterpolator(new OvershootInterpolator(1.4f)).start();
        Sfx s = Sfx.get();
        if (s != null) s.play(Sfx.S.CLICK, 0.6f, 1.2f);
    }

    /** A popup with a content view and buttons. */
    static Popup show(Activity a, String title, View body, Btn... buttons) {
        Popup p = new Popup(a, title, body, true, buttons);
        p.show();
        return p;
    }

    /** Same, but it can only be closed with its buttons (e.g. the match result). */
    static Popup showModal(Activity a, String title, View body, Btn... buttons) {
        Popup p = new Popup(a, title, body, false, buttons);
        p.show();
        return p;
    }

    /** A short message with buttons (default: OK). */
    static Popup message(Activity a, String title, String message, Btn... buttons) {
        return show(a, title, text(a, message), buttons.length > 0 ? buttons : new Btn[]{ok("OK", null)});
    }

    static TextView text(Activity a, String message) {
        TextView t = Ui.text(a, message, 15, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.15f);
        return t;
    }
}
