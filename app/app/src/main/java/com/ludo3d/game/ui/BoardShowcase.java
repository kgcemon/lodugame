package com.ludo3d.game.ui;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.opengl.GLSurfaceView;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.audio.Sfx;
import com.ludo3d.game.game.GameState;
import com.ludo3d.game.game.LocalGame;
import com.ludo3d.game.gl.BoardThemes;
import com.ludo3d.game.gl.ConfigChooser;
import com.ludo3d.game.gl.GameRenderer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * A board shown full screen in real 3D: the game's own renderer draws it (the same board
 * you'd play on), with tokens waiting in all four yards and its corner decorations, sky and
 * ambient effect. The camera orbits slowly by itself; drag to turn and tilt it, pinch to
 * zoom. TEST BOARD plays a short demo match on it — four players rolling and moving, run
 * on this phone only: nothing is sent anywhere and no real match is touched.
 */
final class BoardShowcase {
    private BoardShowcase() {}

    @SuppressLint("ClickableViewAccessibility")
    static Dialog show(BaseActivity a, BoardThemes.Theme theme, List<String> labels, DicePreview.Offer offer) {
        FriendPopups.Shell shell = new FriendPopups.Shell(a, false);
        FrameLayout stage = new FrameLayout(a);
        stage.setBackgroundColor(theme.sky);

        // The real 3D board.
        GameRenderer renderer = new GameRenderer();
        renderer.classic = false;
        renderer.showcase = true;
        renderer.pitchDeg = 52f;
        renderer.setMyColor("blue");
        renderer.setBoard(theme.id);
        GLSurfaceView gl = new GLSurfaceView(a);
        gl.setEGLContextClientVersion(2);
        gl.setEGLConfigChooser(new ConfigChooser());
        gl.setRenderer(renderer);
        gl.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        renderer.requestRender = gl::requestRender;
        stage.addView(gl, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Four players' tokens in their yards.
        LocalGame[] demo = {new LocalGame(new String[]{"Red", "Green", "Yellow", "Blue"})};
        feed(gl, renderer, demo[0].snapshot(), false);

        // Turn, tilt and zoom.
        ScaleGestureDetector pinch = new ScaleGestureDetector(a, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                renderer.zoom = Math.max(0.8f, Math.min(1.9f, renderer.zoom * d.getScaleFactor()));
                gl.requestRender();
                return true;
            }
        });
        float[] last = new float[2];
        gl.setOnTouchListener((v, e) -> {
            pinch.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    renderer.dragging = true;
                    last[0] = e.getX();
                    last[1] = e.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (e.getPointerCount() == 1 && !pinch.isInProgress()) {
                        renderer.yawOffset = (renderer.yawOffset + (e.getX() - last[0]) * 0.3f) % 360f;
                        renderer.pitchDeg = Math.max(28f, Math.min(90f, renderer.pitchDeg + (e.getY() - last[1]) * 0.15f));
                    }
                    last[0] = e.getX();
                    last[1] = e.getY();
                    gl.requestRender();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    renderer.dragging = false;
                    break;
                default:
                    break;
            }
            return true;
        });

        // The board's ambient effect (if it has one and effects are on).
        if (BoardAmbient.has(theme.ambient) && BoardAmbient.enabled()) {
            stage.addView(new BoardAmbient(a, theme.ambient), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // Top: name, rarity, collection, labels.
        LinearLayout top = Ui.vbox(a);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        top.setPadding(Ui.dp(a, 16), Ui.dp(a, 14), Ui.dp(a, 60), Ui.dp(a, 14));
        GradientDrawable fadeTop = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xcc000000, 0x00000000});
        top.setBackground(fadeTop);
        Fx.GoldText name = new Fx.GoldText(a, theme.name.toUpperCase(java.util.Locale.ROOT), 26);
        name.setSingleLine(true);
        name.setGravity(Gravity.CENTER);
        top.addView(name, Ui.lpMatchWrap());
        LinearLayout tags = Ui.hbox(a);
        tags.setGravity(Gravity.CENTER);
        tags.addView(DiceLux.badge(a, theme.rarity));
        tags.addView(Ui.text(a, "  " + BoardLux.collectionName(theme.collection), 12, 0xffe8e2ff));
        top.addView(tags, Ui.lpMatchWrap());
        if (labels != null && !labels.isEmpty()) {
            LinearLayout lr = Ui.hbox(a);
            lr.setGravity(Gravity.CENTER);
            for (String l : labels) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(Ui.dp(a, 3), Ui.dp(a, 5), Ui.dp(a, 3), 0);
                lr.addView(DiceLux.label(a, l), lp);
            }
            top.addView(lr, Ui.lpMatchWrap());
        }
        stage.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // Bottom: test, views, price and action.
        LinearLayout bottom = Ui.vbox(a);
        bottom.setPadding(Ui.dp(a, 16), Ui.dp(a, 30), Ui.dp(a, 16), Ui.dp(a, 18));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xee000000, 0x00000000}));
        TextView hint = Ui.text(a, "Drag to turn and tilt · pinch to zoom", 12, 0xccffffff);
        hint.setGravity(Gravity.CENTER);
        bottom.addView(hint, Ui.lpMatchWrap());
        LinearLayout row = Ui.hbox(a);
        Runnable[] stopDemo = {null};
        TextView test = Ui.smallButton(a, "▶  TEST BOARD", "glass", null);
        test.setOnClickListener(v -> {
            if (stopDemo[0] != null) {
                stopDemo[0].run();
                stopDemo[0] = null;
                test.setText("▶  TEST BOARD");
                return;
            }
            Sfx s = Sfx.get();
            if (s != null) s.play(Sfx.S.CLICK, 0.7f, 1f);
            demo[0] = new LocalGame(new String[]{"Red", "Green", "Yellow", "Blue"});
            feed(gl, renderer, demo[0].snapshot(), false);
            stopDemo[0] = playDemo(gl, renderer, demo);
            test.setText("■  STOP TEST");
        });
        TextView view = Ui.smallButton(a, "🎥  Top view", "glass", null);
        view.setOnClickListener(v -> {
            boolean top3d = renderer.pitchDeg >= 85f;
            renderer.pitchDeg = top3d ? 52f : 90f;
            renderer.zoom = 1f;
            view.setText(top3d ? "🎥  Top view" : "🎥  3D view");
            gl.requestRender();
        });
        LinearLayout.LayoutParams tl = Ui.weight(1);
        tl.rightMargin = Ui.dp(a, 6);
        LinearLayout.LayoutParams vl = Ui.weight(1);
        vl.leftMargin = Ui.dp(a, 6);
        row.addView(test, tl);
        row.addView(view, vl);
        LinearLayout.LayoutParams rl = Ui.lpMatchWrap();
        rl.topMargin = Ui.dp(a, 8);
        bottom.addView(row, rl);
        TextView only = Ui.text(a, "Test games run on this phone only — they never touch a real match.", 11, 0x99ffffff);
        only.setGravity(Gravity.CENTER);
        only.setPadding(0, Ui.dp(a, 4), 0, 0);
        bottom.addView(only, Ui.lpMatchWrap());
        LinearLayout buy = Ui.hbox(a);
        buy.setGravity(Gravity.CENTER_VERTICAL);
        buy.setPadding(0, Ui.dp(a, 10), 0, 0);
        LinearLayout pt = Ui.vbox(a);
        pt.addView(new Fx.GoldText(a, offer.status, 20));
        if (offer.note != null) pt.addView(Ui.text(a, offer.note, 11, Ui.GOLD));
        buy.addView(pt, Ui.weight(1));
        if (offer.action != null) {
            TextView act = Ui.button(a, offer.action, offer.actionStyle, offer.onAction == null ? null : v -> {
                if (stopDemo[0] != null) stopDemo[0].run();
                shell.close(offer.onAction);
            });
            if (offer.onAction == null) Ui.setEnabled(act, false);
            buy.addView(act);
        }
        bottom.addView(buy, Ui.lpMatchWrap());
        stage.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        TextView close = Ui.text(a, "✕", 20, Color.WHITE);
        close.setGravity(Gravity.CENTER);
        GradientDrawable cg = new GradientDrawable();
        cg.setShape(GradientDrawable.OVAL);
        cg.setColor(0x66000000);
        cg.setStroke(Ui.dp(a, 1), 0x55ffffff);
        close.setBackground(cg);
        close.setContentDescription("Close");
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40), Gravity.TOP | Gravity.END);
        cl.setMargins(0, Ui.dp(a, 12), Ui.dp(a, 12), 0);
        stage.addView(close, cl);
        close.setOnClickListener(v -> {
            if (stopDemo[0] != null) stopDemo[0].run();
            shell.close(null);
        });
        shell.setOnDismissListener(d -> {
            if (stopDemo[0] != null) stopDemo[0].run();
            renderer.showcase = false;
        });
        shell.setStage(stage);
        shell.show();
        return shell;
    }

    /** Hand an update from the demo game to the renderer (on the GL thread). */
    private static void feed(GLSurfaceView gl, GameRenderer renderer, JSONObject u, boolean animate) {
        if (u == null) return;
        JSONObject st = u.optJSONObject("state");
        if (st == null) return;
        GameState s = GameState.parse(st);
        JSONArray ev = u.optJSONArray("events");
        gl.queueEvent(() -> renderer.applyUpdate(s, ev != null ? ev : new JSONArray(), animate));
        gl.requestRender();
    }

    /** Play the demo: roll, then move the first token that can, every moment. Returns a stopper. */
    private static Runnable playDemo(GLSurfaceView gl, GameRenderer renderer, LocalGame[] demo) {
        boolean[] on = {true};
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (!on[0]) return;
            LocalGame g = demo[0];
            if (g.isOver()) {
                demo[0] = g = new LocalGame(new String[]{"Red", "Green", "Yellow", "Blue"});
                feed(gl, renderer, g.snapshot(), false);
            } else {
                JSONObject u = g.roll();
                if (u == null) {
                    for (int t = 0; t < 4 && u == null; t++) u = g.move(t);
                }
                feed(gl, renderer, u, true);
            }
            LudoApp.MAIN.postDelayed(step[0], 950);
        };
        LudoApp.MAIN.postDelayed(step[0], 300);
        return () -> on[0] = false;
    }
}
