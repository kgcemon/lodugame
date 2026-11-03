package com.ludox.game.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.LruCache;
import android.widget.ImageView;

import com.ludox.game.LudoApp;
import com.ludox.game.gl.BoardThemes;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The board shop's collections and its thumbnails: each board tilted in perspective with
 * its rim, shadow and corner decorations, painted off the main thread and kept, so the shop
 * scrolls smoothly. The full 3D board is only drawn for the one you open ({@link BoardShowcase}).
 */
final class BoardLux {
    private BoardLux() {}

    static final String[] COLLECTIONS = {"couple", "love", "royal", "luxury", "nature", "fantasy", "neon", "seasonal", "exclusive", "free"};

    static String collectionName(String c) {
        switch (c == null ? "" : c) {
            case "couple": return "Couple & Romantic Collection";
            case "love": return "Love & Valentine Collection";
            case "royal": return "Royal Collection";
            case "luxury": return "Luxury Collection";
            case "nature": return "Nature Collection";
            case "fantasy": return "Fantasy Collection";
            case "neon": return "Neon Collection";
            case "seasonal": return "Seasonal Collection";
            case "exclusive": return "Exclusive Legendary Collection";
            default: return "Free Collection";
        }
    }

    static String collectionShort(String c) {
        switch (c == null ? "" : c) {
            case "couple": return "💞 Couple";
            case "love": return "💌 Love";
            case "royal": return "👑 Royal";
            case "luxury": return "💎 Luxury";
            case "nature": return "🌿 Nature";
            case "fantasy": return "🐉 Fantasy";
            case "neon": return "🌃 Neon";
            case "seasonal": return "❄ Seasonal";
            case "exclusive": return "✨ Legendary";
            default: return "🎲 Free";
        }
    }

    private static final LruCache<String, Bitmap> THUMBS = new LruCache<String, Bitmap>(20 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService PAINTER = Executors.newSingleThreadExecutor();

    /** Show the board's tilted render in `iv`, painting it in the background the first time. */
    static void thumb(ImageView iv, BoardThemes.Theme t, int px) {
        String key = t.key() + "|" + px;
        Bitmap b = THUMBS.get(key);
        iv.setTag(key);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }
        iv.setImageDrawable(null);
        PAINTER.execute(() -> {
            Bitmap made = render(t, px);
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

    /** The board seen from in front and above: a trapezoid face, a lit rim, a soft shadow. */
    private static Bitmap render(BoardThemes.Theme t, int px) {
        int w = px, h = Math.round(px * 0.78f);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Bitmap face = BoardThemes.bitmap(t, 384);
        float topY = h * 0.08f, botY = h * 0.78f, rimH = h * 0.06f;
        float topL = w * 0.17f, topR = w * 0.83f, botL = w * 0.03f, botR = w * 0.97f;
        // Shadow under the board.
        p.setShader(new RadialGradient(w / 2f, botY + rimH, w * 0.55f, 0x66000000, 0x00000000, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1f, 0.25f, w / 2f, botY + rimH);
        c.drawCircle(w / 2f, botY + rimH, w * 0.55f, p);
        c.restore();
        p.setShader(null);
        // Rim: the front side, then the face on top of it.
        int rim = rgb(t.rim);
        Path side = new Path();
        side.moveTo(botL, botY);
        side.lineTo(botR, botY);
        side.lineTo(botR, botY + rimH);
        side.lineTo(botL, botY + rimH);
        side.close();
        p.setColor(darken(rim, 0.35f));
        c.drawPath(side, p);
        Path frame = new Path();
        frame.moveTo(topL - w * 0.02f, topY - h * 0.02f);
        frame.lineTo(topR + w * 0.02f, topY - h * 0.02f);
        frame.lineTo(botR + w * 0.01f, botY + h * 0.01f);
        frame.lineTo(botL - w * 0.01f, botY + h * 0.01f);
        frame.close();
        p.setColor(rim);
        c.drawPath(frame, p);
        Matrix m = new Matrix();
        float fs = face.getWidth();
        m.setPolyToPoly(new float[]{0, 0, fs, 0, fs, fs, 0, fs}, 0, new float[]{topL, topY, topR, topY, botR, botY, botL, botY}, 0, 4);
        c.save();
        c.concat(m);
        c.drawBitmap(face, 0, 0, p);
        c.restore();
        face.recycle();
        // Little corner decorations (pillars, crystals, lanterns, orbs, trees).
        if (!"none".equals(t.decor)) {
            float[][] at = {{topL, topY}, {topR, topY}, {botL, botY}, {botR, botY}};
            for (float[] k : at) decor(c, p, t, k[0], k[1], k[1] > h / 2f ? w * 0.06f : w * 0.045f);
        }
        return out;
    }

    private static void decor(Canvas c, Paint p, BoardThemes.Theme t, float x, float y, float s) {
        int dc = t.decorColor, rim = rgb(t.rim);
        switch (t.decor) {
            case "pillar":
                p.setColor(darken(dc, 0.15f));
                c.drawRect(x - s * 0.3f, y - s * 2.2f, x + s * 0.3f, y, p);
                p.setColor(rim);
                c.drawRect(x - s * 0.45f, y - s * 2.35f, x + s * 0.45f, y - s * 2.1f, p);
                c.drawRect(x - s * 0.45f, y - s * 0.2f, x + s * 0.45f, y + s * 0.05f, p);
                glow(c, p, x, y - s * 2.6f, s * 0.35f, dc);
                break;
            case "crystal": {
                Path cr = new Path();
                cr.moveTo(x - s * 0.35f, y);
                cr.lineTo(x - s * 0.3f, y - s * 1.6f);
                cr.lineTo(x, y - s * 2.2f);
                cr.lineTo(x + s * 0.3f, y - s * 1.6f);
                cr.lineTo(x + s * 0.35f, y);
                cr.close();
                p.setColor(dc);
                c.drawPath(cr, p);
                p.setColor(0x66ffffff);
                c.drawRect(x - s * 0.08f, y - s * 1.8f, x + s * 0.02f, y - s * 0.2f, p);
                break;
            }
            case "tree":
                p.setColor(0xff6a4a2a);
                c.drawRect(x - s * 0.12f, y - s * 1.2f, x + s * 0.12f, y, p);
                p.setColor(dc);
                c.drawCircle(x, y - s * 1.6f, s * 0.75f, p);
                break;
            case "lantern":
                p.setColor(0xff3a2a1a);
                c.drawRect(x - s * 0.05f, y - s * 1.3f, x + s * 0.05f, y, p);
                glow(c, p, x, y - s * 1.5f, s * 0.5f, dc);
                break;
            default: // orb
                glow(c, p, x, y - s * 0.7f, s * 0.6f, dc);
                break;
        }
    }

    private static void glow(Canvas c, Paint p, float x, float y, float r, int col) {
        p.setShader(new RadialGradient(x, y, r * 2f, (col & 0x00ffffff) | 0x88000000, col & 0x00ffffff, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r * 2f, p);
        p.setShader(null);
        p.setColor(col);
        c.drawCircle(x, y, r * 0.7f, p);
    }

    private static int rgb(float[] f) {
        return 0xff000000 | (Math.round(Math.min(1f, f[0]) * 255) << 16) | (Math.round(Math.min(1f, f[1]) * 255) << 8) | Math.round(Math.min(1f, f[2]) * 255);
    }

    private static int darken(int c, float f) {
        return 0xff000000 | ((int) (((c >> 16) & 255) * (1 - f)) << 16) | ((int) (((c >> 8) & 255) * (1 - f)) << 8) | (int) ((c & 255) * (1 - f));
    }
}
