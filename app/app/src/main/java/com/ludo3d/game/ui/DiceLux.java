package com.ludo3d.game.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.LruCache;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.TextView;

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.gl.DiceArt;

import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The dice shop's look: rarity colours and badges, the dark marble-and-gold card, the
 * glowing display platform and the dice thumbnails (still renders of the 3D die, painted
 * off the main thread and kept, so a long list scrolls smoothly; only the die you open is
 * live 3D).
 */
final class DiceLux {
    private DiceLux() {}

    static final String[] RARITIES = {"common", "rare", "epic", "legendary", "mythic", "ultimate"};
    static final String[] COLLECTIONS = {"royal", "gemstone", "fantasy", "special", "exclusive", "classic", "flags"};

    static String rarityName(String r) {
        return r == null || r.isEmpty() ? "Common" : r.substring(0, 1).toUpperCase(Locale.ROOT) + r.substring(1);
    }

    static String collectionName(String c) {
        switch (c == null ? "" : c) {
            case "royal": return "Royal Collection";
            case "gemstone": return "Gemstone Collection";
            case "fantasy": return "Fantasy Collection";
            case "special": return "Special Collection";
            case "exclusive": return "Exclusive Collection";
            case "flags": return "Country Flags";
            default: return "Classic Collection";
        }
    }

    static String collectionShort(String c) {
        switch (c == null ? "" : c) {
            case "royal": return "👑 Royal";
            case "gemstone": return "💎 Gemstone";
            case "fantasy": return "🐉 Fantasy";
            case "special": return "⚡ Special";
            case "exclusive": return "✨ Exclusive";
            case "flags": return "🏳 Flags";
            default: return "🎲 Classic";
        }
    }

    /** Rarity order (0 = common .. 5 = ultimate). */
    static int rank(String r) {
        for (int i = 0; i < RARITIES.length; i++) if (RARITIES[i].equals(r)) return i;
        return 0;
    }

    /** The rarity's colour (its glow, border and badge). */
    static int color(String r) {
        switch (r == null ? "" : r) {
            case "rare": return 0xff4fa3ff;
            case "epic": return 0xffb05cff;
            case "legendary": return 0xffffb547;
            case "mythic": return 0xffff4f7b;
            case "ultimate": return 0xffffe27a;
            default: return 0xffb8c0d0;
        }
    }

    /** A rarity pill: grey for common up to a gold-to-purple gradient for ultimate. */
    static TextView badge(Context c, String rarity) {
        TextView t = Ui.bold(Ui.text(c, rarityName(rarity).toUpperCase(Locale.ROOT), 10, Color.WHITE));
        t.setLetterSpacing(0.12f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(c, 8), Ui.dp(c, 2), Ui.dp(c, 8), Ui.dp(c, 3));
        int col = color(rarity);
        GradientDrawable g = "ultimate".equals(rarity)
                ? new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xffffcf40, 0xffc77dff, 0xff6a1fd0})
                : new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Fx.blend(col, Color.WHITE, 0.15f), Fx.blend(col, Color.BLACK, 0.35f)});
        g.setCornerRadius(Ui.dp(c, 999));
        g.setStroke(Ui.dp(c, 1), 0x66ffffff);
        t.setBackground(g);
        t.setShadowLayer(Ui.dp(c, 1), 0, Ui.dp(c, 1), 0x88000000);
        if ("common".equals(rarity)) t.setTextColor(0xff1c1f2a);
        return t;
    }

    /** A shop label (NEW ARRIVAL, BEST SELLER, ...) as a small ribbon. */
    static TextView label(Context c, String text) {
        int col;
        switch (text) {
            case "NEW ARRIVAL": col = 0xff22b46b; break;
            case "BEST SELLER": col = 0xffff6a1e; break;
            case "MOST POPULAR": col = 0xffe91e63; break;
            case "LIMITED EDITION": col = 0xffd61f2c; break;
            case "EXCLUSIVE": col = 0xff7b2fd1; break;
            default: col = 0xffc78a00; break;
        }
        TextView t = Ui.bold(Ui.text(c, text, 9, Color.WHITE));
        t.setLetterSpacing(0.08f);
        t.setPadding(Ui.dp(c, 6), Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Fx.blend(col, Color.WHITE, 0.15f), Fx.blend(col, Color.BLACK, 0.2f)});
        g.setCornerRadius(Ui.dp(c, 6));
        t.setBackground(g);
        return t;
    }

    /** Dark marble with a metallic border in the rarity's colour. */
    static Drawable card(Context c, String rarity, boolean glowing) {
        return new Marble(c, color(rarity), glowing);
    }

    private static final class Marble extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int edge;
        private final boolean glowing;
        private final float radius, stroke;
        private final long seed;

        Marble(Context c, int edge, boolean glowing) {
            this.edge = edge;
            this.glowing = glowing;
            radius = Ui.dp(c, 18);
            stroke = Ui.dp(c, glowing ? 2 : 1.5f);
            seed = edge;
        }

        @Override
        public void draw(Canvas c) {
            RectF r = new RectF(getBounds());
            r.inset(stroke, stroke);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.BLACK);
            p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, new int[]{0xff2a1550, 0xff140a2c, 0xff0b0618}, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, radius, radius, p);
            // Faint marble veins.
            c.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            Random rnd = new Random(seed);
            for (int v = 0; v < 3; v++) {
                android.graphics.Path path = new android.graphics.Path();
                float x = r.left + rnd.nextFloat() * r.width(), y = r.top;
                path.moveTo(x, y);
                for (int k = 0; k < 5; k++) {
                    x += (rnd.nextFloat() - 0.5f) * r.width() * 0.5f;
                    y += r.height() / 4.5f;
                    path.lineTo(x, y);
                }
                p.setStrokeWidth(stroke * (0.6f + rnd.nextFloat()));
                p.setColor(0x14ffffff);
                c.drawPath(path, p);
            }
            // A glossy top.
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.35f, 0x1affffff, 0x00ffffff, Shader.TileMode.CLAMP));
            c.drawRect(r, p);
            c.restore();
            // Metallic border.
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                    new int[]{Fx.blend(edge, Color.WHITE, 0.5f), edge, Fx.blend(edge, Color.BLACK, 0.4f), edge},
                    null, Shader.TileMode.CLAMP));
            if (!glowing) p.setAlpha(170);
            c.drawRoundRect(r, radius, radius, p);
            p.setShader(null);
            p.setAlpha(255);
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter cf) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** A glowing round display platform for a die (drawn under it). */
    static final class Platform extends android.view.View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int col;

        Platform(Context c, String rarity) {
            super(c);
            col = color(rarity);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h * 0.82f, rx = w * 0.42f;
            // Light pooling on the platform.
            p.setShader(new RadialGradient(cx, cy, rx * 1.2f, (col & 0x00ffffff) | 0x66000000, col & 0x00ffffff, Shader.TileMode.CLAMP));
            c.save();
            c.scale(1f, 0.3f, cx, cy);
            c.drawCircle(cx, cy, rx * 1.2f, p);
            c.restore();
            // The platform: a dark disc with a glowing rim.
            p.setShader(new LinearGradient(0, cy - rx * 0.25f, 0, cy + rx * 0.25f, 0xff2a1a50, 0xff0a0518, Shader.TileMode.CLAMP));
            RectF disc = new RectF(cx - rx, cy - rx * 0.22f, cx + rx, cy + rx * 0.22f);
            c.drawOval(disc, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, w * 0.012f));
            p.setColor(col);
            c.drawOval(disc, p);
            p.setStyle(Paint.Style.FILL);
            // A soft beam of light from above.
            p.setShader(new LinearGradient(0, 0, 0, cy, (col & 0x00ffffff), (col & 0x00ffffff) | 0x26000000, Shader.TileMode.CLAMP));
            android.graphics.Path beam = new android.graphics.Path();
            beam.moveTo(cx - rx * 0.35f, 0);
            beam.lineTo(cx + rx * 0.35f, 0);
            beam.lineTo(cx + rx, cy);
            beam.lineTo(cx - rx, cy);
            beam.close();
            c.drawPath(beam, p);
            p.setShader(null);
        }
    }

    // ---------------------------------------------------------------- thumbnails

    private static final LruCache<String, Bitmap> THUMBS = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService PAINTER = Executors.newSingleThreadExecutor();

    /** Show the die's still render in `iv`, painting it in the background the first time. */
    static void thumb(ImageView iv, DiceView.Skin skin, int px) {
        String key = skin.id + "|" + px;
        Bitmap b = THUMBS.get(key);
        iv.setTag(key);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }
        iv.setImageDrawable(null);
        DiceArt.Look look = skin.look();
        PAINTER.execute(() -> {
            Bitmap made = DiceArt.thumbnail(look, px);
            THUMBS.put(key, made);
            LudoApp.MAIN.post(() -> {
                if (key.equals(iv.getTag())) {
                    iv.setImageBitmap(made);
                    iv.setAlpha(0f);
                    iv.animate().alpha(1f).setDuration(180).start();
                }
            });
        });
    }
}
