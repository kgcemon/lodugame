package com.ludox.game.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Countries: a player's flag as an emoji next to their name (a globe when the server
 * doesn't know their country), and the flags painted on the shop's flag dice.
 */
final class Flags {
    private Flags() {}

    /** Shown when a player's country is unknown (or for bots). */
    static final String GLOBE = "🌐";

    /** The flag emoji of a two-letter country code, or the globe. */
    static String emoji(String code) {
        if (code == null || code.length() != 2 || !Character.isLetter(code.charAt(0)) || !Character.isLetter(code.charAt(1))) return GLOBE;
        String c = code.toUpperCase(java.util.Locale.ROOT);
        int a = 0x1F1E6 + (c.charAt(0) - 'A'), b = 0x1F1E6 + (c.charAt(1) - 'A');
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }

    /** The flag of a player JSON (its "country"), or the globe. */
    static String of(JSONObject who) {
        return emoji(who == null || who.isNull("country") ? null : who.optString("country", null));
    }

    /** "🇧🇩 name": a player's name with their flag in front. */
    static String named(JSONObject who, String name) {
        return of(who) + " " + name;
    }

    // ------------------------------------------------------------------ flag dice

    private static final Map<String, Bitmap> BITMAPS = new HashMap<>();

    /** A square picture of the flag (for die faces), drawn once per country. */
    static Bitmap bitmap(String code) {
        synchronized (BITMAPS) {
            Bitmap b = BITMAPS.get(code);
            if (b != null) return b;
            b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
            draw(new Canvas(b), code, new RectF(0, 0, 160, 160));
            BITMAPS.put(code, b);
            return b;
        }
    }

    /** Paint a country's flag (simplified to fit a square die face) into r. */
    static void draw(Canvas c, String code, RectF r) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float w = r.width(), h = r.height(), x = r.left, y = r.top;
        switch (code) {
            case "BD":
                // Green with a red disc a little left of centre.
                fill(c, p, r, 0xff006a4e);
                p.setColor(0xfff42a41);
                c.drawCircle(x + w * 0.45f, y + h * 0.5f, w * 0.27f, p);
                break;
            case "IN": {
                // Saffron, white and green with the blue wheel.
                stripesH(c, p, r, 0xffff9933, Color.WHITE, 0xff138808);
                float cx = x + w / 2, cy = y + h / 2, rr = h * 0.13f;
                p.setColor(0xff000080);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(h * 0.022f);
                c.drawCircle(cx, cy, rr, p);
                p.setStrokeWidth(h * 0.01f);
                for (int i = 0; i < 24; i++) {
                    double a = Math.PI * 2 * i / 24;
                    c.drawLine(cx, cy, cx + (float) Math.cos(a) * rr, cy + (float) Math.sin(a) * rr, p);
                }
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, h * 0.03f, p);
                break;
            }
            case "PK": {
                // White band at the hoist, green field with a white crescent and star.
                fill(c, p, r, 0xff01411c);
                p.setColor(Color.WHITE);
                c.drawRect(x, y, x + w * 0.25f, y + h, p);
                float cx = x + w * 0.64f, cy = y + h * 0.5f;
                c.drawCircle(cx, cy, h * 0.24f, p);
                p.setColor(0xff01411c);
                c.drawCircle(cx + w * 0.07f, cy - h * 0.06f, h * 0.21f, p);
                p.setColor(Color.WHITE);
                star(c, p, cx + w * 0.12f, cy - h * 0.1f, h * 0.09f);
                break;
            }
            case "NP": {
                // The double pennant (crimson, blue border) on white, with moon and sun.
                fill(c, p, r, Color.WHITE);
                Path pen = new Path();
                pen.moveTo(x + w * 0.16f, y + h * 0.06f);
                pen.lineTo(x + w * 0.84f, y + h * 0.47f);
                pen.lineTo(x + w * 0.42f, y + h * 0.47f);
                pen.lineTo(x + w * 0.84f, y + h * 0.94f);
                pen.lineTo(x + w * 0.16f, y + h * 0.94f);
                pen.close();
                p.setColor(0xff003893);
                p.setStyle(Paint.Style.FILL_AND_STROKE);
                p.setStrokeJoin(Paint.Join.MITER);
                p.setStrokeWidth(w * 0.07f);
                c.drawPath(pen, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xffdc143c);
                c.drawPath(pen, p);
                p.setColor(Color.WHITE);
                // Moon: a white disc cut by a crimson one, with a small round face.
                c.drawCircle(x + w * 0.33f, y + h * 0.3f, w * 0.085f, p);
                p.setColor(0xffdc143c);
                c.drawCircle(x + w * 0.33f, y + h * 0.26f, w * 0.085f, p);
                p.setColor(Color.WHITE);
                c.drawCircle(x + w * 0.33f, y + h * 0.33f, w * 0.04f, p);
                sun(c, p, x + w * 0.33f, y + h * 0.75f, w * 0.1f);
                break;
            }
            case "LK": {
                // Gold border; green and orange stripes; the maroon panel with a gold lion's
                // sword and the four bo leaves (simplified).
                fill(c, p, r, 0xffffbe29);
                float b = w * 0.05f;
                p.setColor(0xff00534e);
                c.drawRect(x + b, y + b, x + w * 0.17f, y + h - b, p);
                p.setColor(0xffeb7400);
                c.drawRect(x + w * 0.19f, y + b, x + w * 0.31f, y + h - b, p);
                p.setColor(0xff8d153a);
                c.drawRect(x + w * 0.35f, y + b, x + w - b, y + h - b, p);
                p.setColor(0xffffbe29);
                float px = x + w * 0.35f, pw = w * 0.6f - b;
                float[][] corners = {{px + pw * 0.12f, y + h * 0.14f}, {px + pw * 0.88f, y + h * 0.14f}, {px + pw * 0.12f, y + h * 0.86f}, {px + pw * 0.88f, y + h * 0.86f}};
                for (float[] k : corners) c.drawCircle(k[0], k[1], w * 0.045f, p);
                // The lion: a rounded body with a raised sword.
                float lx = px + pw / 2, ly = y + h * 0.55f;
                c.drawOval(new RectF(lx - pw * 0.28f, ly - h * 0.12f, lx + pw * 0.28f, ly + h * 0.12f), p);
                c.drawCircle(lx + pw * 0.2f, ly - h * 0.15f, h * 0.09f, p);
                c.drawRect(lx - pw * 0.25f, ly + h * 0.05f, lx - pw * 0.17f, ly + h * 0.22f, p);
                c.drawRect(lx + pw * 0.12f, ly + h * 0.05f, lx + pw * 0.2f, ly + h * 0.22f, p);
                p.setStrokeWidth(w * 0.025f);
                c.drawLine(lx + pw * 0.3f, ly - h * 0.05f, lx + pw * 0.38f, ly - h * 0.33f, p);
                break;
            }
            case "AE":
                // Red band at the hoist; green, white and black stripes.
                stripesH(c, p, r, 0xff00732f, Color.WHITE, 0xff000000);
                p.setColor(0xffff0000);
                c.drawRect(x, y, x + w * 0.27f, y + h, p);
                break;
            case "SA": {
                // Green with a white sword (the writing above it is shown as a white band).
                fill(c, p, r, 0xff006c35);
                p.setColor(Color.WHITE);
                c.drawRoundRect(new RectF(x + w * 0.2f, y + h * 0.3f, x + w * 0.8f, y + h * 0.46f), h * 0.04f, h * 0.04f, p);
                c.drawRect(x + w * 0.22f, y + h * 0.6f, x + w * 0.76f, y + h * 0.64f, p);
                c.drawRect(x + w * 0.68f, y + h * 0.56f, x + w * 0.7f, y + h * 0.68f, p);
                break;
            }
            default:
                fill(c, p, r, 0xff3b82f6);
                break;
        }
    }

    private static void fill(Canvas c, Paint p, RectF r, int color) {
        p.setColor(color);
        c.drawRect(r, p);
    }

    private static void stripesH(Canvas c, Paint p, RectF r, int a, int b, int d) {
        float t = r.height() / 3;
        p.setColor(a);
        c.drawRect(r.left, r.top, r.right, r.top + t, p);
        p.setColor(b);
        c.drawRect(r.left, r.top + t, r.right, r.top + 2 * t, p);
        p.setColor(d);
        c.drawRect(r.left, r.top + 2 * t, r.right, r.bottom, p);
    }

    private static void star(Canvas c, Paint p, float cx, float cy, float r) {
        Path s = new Path();
        for (int i = 0; i < 10; i++) {
            double a = Math.toRadians(-90 + i * 36);
            float rr = i % 2 == 0 ? r : r * 0.42f;
            float px = cx + (float) Math.cos(a) * rr, py = cy + (float) Math.sin(a) * rr;
            if (i == 0) s.moveTo(px, py);
            else s.lineTo(px, py);
        }
        s.close();
        c.drawPath(s, p);
    }

    private static void sun(Canvas c, Paint p, float cx, float cy, float r) {
        Path s = new Path();
        for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2 * i / 24;
            float rr = i % 2 == 0 ? r : r * 0.7f;
            float px = cx + (float) Math.cos(a) * rr, py = cy + (float) Math.sin(a) * rr;
            if (i == 0) s.moveTo(px, py);
            else s.lineTo(px, py);
        }
        s.close();
        c.drawPath(s, p);
    }
}
