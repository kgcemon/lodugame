package com.ludo3d.game.ui;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ludo3d.game.audio.Sfx;
import com.ludo3d.game.gl.DiceArt;

import java.util.List;

/**
 * A die shown full screen: big, live and turning on a glowing display platform, with its
 * rarity's presentation (a glow for rare, drifting particles for epic, a light sweep and
 * rays for legendary and up). Drag to turn it, tap a face below to see it, or TEST ROLL to
 * throw it — only for show: nothing is sent to the server and no match is touched. The
 * button at the bottom buys or equips it through the shop.
 */
final class DicePreview {
    private DicePreview() {}

    /** What the bottom of the preview offers. */
    static final class Offer {
        /** "💎 450", "Owned", "✓ Equipped"... */
        String status;
        /** Button label, or null for no button. */
        String action;
        String actionStyle = "primary";
        Runnable onAction;
        /** Extra line (e.g. "Only 3 left"). */
        String note;
    }

    static Dialog show(BaseActivity a, DiceView.Skin skin, List<String> labels, Offer offer) {
        FriendPopups.Shell shell = new FriendPopups.Shell(a, false);
        FrameLayout stage = new FrameLayout(a);
        int rank = DiceLux.rank(skin.rarity);
        int col = DiceLux.color(skin.rarity);

        // Spotlight in the rarity's colour.
        View backdrop = new View(a);
        GradientDrawable spot = new GradientDrawable();
        spot.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        spot.setGradientRadius(a.getResources().getDisplayMetrics().heightPixels * 0.55f);
        spot.setGradientCenter(0.5f, 0.3f);
        spot.setColors(new int[]{Fx.blend(col, 0xff12062a, 0.62f), 0xf5100524, 0xfc040109});
        backdrop.setBackground(spot);
        stage.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout col0 = Ui.vbox(a);
        col0.setGravity(Gravity.CENTER_HORIZONTAL);
        col0.setPadding(Ui.dp(a, 18), Ui.dp(a, 12), Ui.dp(a, 18), Ui.dp(a, 24));
        scroll.addView(col0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Top: rarity, collection and labels.
        LinearLayout tags = Ui.hbox(a);
        tags.setGravity(Gravity.CENTER);
        tags.addView(DiceLux.badge(a, skin.rarity));
        TextView coll = Ui.text(a, "  " + DiceLux.collectionName(skin.collection), 13, 0xffd9cfff);
        tags.addView(coll);
        col0.addView(tags, Ui.lpMatchWrap());
        if (labels != null && !labels.isEmpty()) {
            LinearLayout lr = Ui.hbox(a);
            lr.setGravity(Gravity.CENTER);
            for (String l : labels) {
                TextView t = DiceLux.label(a, l);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(Ui.dp(a, 3), Ui.dp(a, 6), Ui.dp(a, 3), 0);
                lr.addView(t, lp);
            }
            col0.addView(lr, Ui.lpMatchWrap());
        }

        // The stage: rays (legendary and up), the platform, ambient particles and the die.
        FrameLayout show = new FrameLayout(a);
        show.setClipChildren(false);
        int stageH = Ui.dp(a, 330);
        if (rank >= 3) {
            FriendPopups.Rays rays = new FriendPopups.Rays(a, rank >= 5 ? 0x88ffcf40 : (col & 0x00ffffff) | 0x66000000);
            int rs = Ui.dp(a, 380);
            stage.post(() -> FriendPopups.spin(rays, rank >= 5 ? 9000 : 16000));
            FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(rs, rs, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            rl.topMargin = -Ui.dp(a, 40);
            show.addView(rays, rl);
            if (rank >= 5) {
                FriendPopups.Rays inner = new FriendPopups.Rays(a, 0x66c77dff);
                inner.setRotation(11f);
                stage.post(() -> FriendPopups.spin(inner, 14000));
                show.addView(inner, new FrameLayout.LayoutParams(rs, rs, Gravity.CENTER_HORIZONTAL | Gravity.TOP));
                ((FrameLayout.LayoutParams) inner.getLayoutParams()).topMargin = -Ui.dp(a, 40);
            }
        }
        show.addView(new DiceLux.Platform(a, skin.rarity), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, stageH));
        DiceFx ambient = null;
        String fx = DiceFx.has(skin.effect) ? skin.effect : rank >= 2 ? "sparkle" : null;
        if (rank >= 2 && fx != null) {
            ambient = new DiceFx(a, fx);
            show.addView(ambient, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, stageH));
            ambient.ambient(0.5f, 0.45f, rank >= 4 ? 0.34f : 0.28f);
        }
        LiveDie die = new LiveDie(a, skin);
        show.addView(die, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, stageH));
        DiceFx burst = new DiceFx(a, DiceFx.has(skin.effect) ? skin.effect : "sparkle");
        show.addView(burst, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, stageH));
        col0.addView(show, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, stageH));
        TextView hint = Ui.text(a, "Drag to turn · tap a face below", 12, 0x99d9cfff);
        hint.setGravity(Gravity.CENTER);
        col0.addView(hint, Ui.lpMatchWrap());

        // Name and story.
        Fx.GoldText name = rank >= 3
                ? new Fx.GoldText(a, skin.name.toUpperCase(java.util.Locale.ROOT), 30)
                : new Fx.GoldText(a, skin.name.toUpperCase(java.util.Locale.ROOT), 28, new int[]{0xffffffff, 0xffe6e0ff, 0xffb9a8f0}, 0xff2a1460);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        LinearLayout.LayoutParams nl = Ui.lpMatchWrap();
        nl.topMargin = Ui.dp(a, 6);
        col0.addView(name, nl);
        if (skin.blurb != null && !skin.blurb.isEmpty()) {
            TextView blurb = Ui.text(a, skin.blurb, 14, 0xffe8e2ff);
            blurb.setGravity(Gravity.CENTER);
            col0.addView(blurb, Ui.lpMatchWrap());
        }

        // Test roll and its result.
        TextView result = Ui.bold(Ui.text(a, "", 15, 0xff1c0c00));
        result.setGravity(Gravity.CENTER);
        result.setVisibility(View.INVISIBLE);
        GradientDrawable chip = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xffffe27a, 0xfff5b400});
        chip.setCornerRadius(Ui.dp(a, 999));
        result.setBackground(chip);
        result.setPadding(Ui.dp(a, 16), Ui.dp(a, 5), Ui.dp(a, 16), Ui.dp(a, 6));
        LinearLayout.LayoutParams rl2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl2.topMargin = Ui.dp(a, 10);
        col0.addView(result, rl2);
        View roll = FriendPopups.button(a, "🎲  TEST ROLL", Fx.PURPLE, 0xff3b0d8c);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(Ui.dp(a, 230), Ui.dp(a, 52));
        tl.topMargin = Ui.dp(a, 10);
        col0.addView(roll, tl);
        TextView only = Ui.text(a, "Just for fun — test rolls never count in a match.", 11, 0x88d9cfff);
        only.setGravity(Gravity.CENTER);
        col0.addView(only, Ui.lpMatchWrap());
        die.setOnRolled(v -> {
            Sfx s = Sfx.get();
            if (s != null) s.play(Sfx.S.STEP, 0.9f, 0.8f);
            burst.burst(burst.getWidth() / 2f, burst.getHeight() * 0.5f, die.getWidth() * 0.48f, false);
            result.setText("🎲  You rolled a " + v);
            result.setVisibility(View.VISIBLE);
            result.setScaleX(0.4f);
            result.setScaleY(0.4f);
            result.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(new OvershootInterpolator(3f)).start();
        });
        roll.setOnClickListener(v -> {
            if (die.isRolling()) return;
            Sfx s = Sfx.get();
            if (s != null) s.play(Sfx.S.DICE_SHAKE);
            result.setVisibility(View.INVISIBLE);
            die.testRoll();
        });

        // All six faces: tap one to turn it to you.
        TextView facesTitle = Ui.bold(Ui.text(a, "ALL SIX FACES", 11, 0xffd9cfff));
        facesTitle.setLetterSpacing(0.15f);
        facesTitle.setGravity(Gravity.CENTER);
        facesTitle.setPadding(0, Ui.dp(a, 18), 0, Ui.dp(a, 6));
        col0.addView(facesTitle, Ui.lpMatchWrap());
        HorizontalScrollView fs = new HorizontalScrollView(a);
        fs.setHorizontalScrollBarEnabled(false);
        LinearLayout faces = Ui.hbox(a);
        faces.setGravity(Gravity.CENTER);
        DiceArt.Look look = skin.look();
        int fsz = Ui.dp(a, 46);
        for (int v = 1; v <= 6; v++) {
            ImageView iv = new ImageView(a);
            iv.setImageBitmap(DiceArt.face(look, v, 128));
            iv.setContentDescription("Show the " + v);
            int value = v;
            iv.setOnClickListener(x -> {
                die.showFace(value);
                x.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction(() -> x.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(fsz, fsz);
            lp.setMargins(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0);
            faces.addView(iv, lp);
        }
        fs.addView(faces);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.gravity = Gravity.CENTER_HORIZONTAL;
        col0.addView(fs, fl);

        // Price and what you can do.
        LinearLayout buy = Ui.vbox(a);
        buy.setGravity(Gravity.CENTER_HORIZONTAL);
        buy.setBackground(DiceLux.card(a, skin.rarity, true));
        buy.setPadding(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16), Ui.dp(a, 14));
        Fx.GoldText price = new Fx.GoldText(a, offer.status, 22, new int[]{0xffffffff, 0xffb9f2ff, 0xff4fd8ff}, 0xff0b4f8a);
        price.setGravity(Gravity.CENTER);
        buy.addView(price, Ui.lpMatchWrap());
        if (offer.note != null) {
            TextView note = Ui.text(a, offer.note, 12, Ui.GOLD);
            note.setGravity(Gravity.CENTER);
            buy.addView(note, Ui.lpMatchWrap());
        }
        if (offer.action != null) {
            TextView act = Ui.button(a, offer.action, offer.actionStyle, offer.onAction == null ? null : v -> shell.close(offer.onAction));
            if (offer.onAction == null) Ui.setEnabled(act, false);
            LinearLayout.LayoutParams al = Ui.lpMatchWrap();
            al.topMargin = Ui.dp(a, 10);
            buy.addView(act, al);
        }
        LinearLayout.LayoutParams bl = Ui.lpMatchWrap();
        bl.topMargin = Ui.dp(a, 18);
        col0.addView(buy, bl);

        stage.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Close.
        TextView close = Ui.text(a, "✕", 20, Color.WHITE);
        close.setGravity(Gravity.CENTER);
        GradientDrawable cg = new GradientDrawable();
        cg.setShape(GradientDrawable.OVAL);
        cg.setColor(0x55000000);
        cg.setStroke(Ui.dp(a, 1), 0x55ffffff);
        close.setBackground(cg);
        close.setContentDescription("Close");
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40), Gravity.TOP | Gravity.END);
        cl.setMargins(0, Ui.dp(a, 10), Ui.dp(a, 12), 0);
        stage.addView(close, cl);
        close.setOnClickListener(v -> shell.close(null));
        shell.setStage(stage);
        shell.show();
        return shell;
    }
}
