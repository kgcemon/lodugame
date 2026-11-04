package com.ludox.game.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.util.LruCache;
import android.util.TypedValue;
import android.widget.TextView;

import com.ludox.game.LudoApp;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * A player's round avatar: their profile picture (from Google) or their initial on a colour,
 * and around it the animated profile frame they bought in the shop (fire, hearts, …).
 * With a frame the picture shrinks to leave room for it inside the same square.
 */
public class AvatarView extends TextView {
    /** Picture radius as a share of the view's radius when a frame is drawn around it. */
    private static final float FRAMED = 0.72f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Matrix m = new Matrix();
    private int color;
    private final float textPx;
    private String url;
    private Bitmap photo;
    private String frame;

    AvatarView(Context c, int color, float textSp) {
        super(c);
        this.color = color;
        this.textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSp, c.getResources().getDisplayMetrics());
        setWillNotDraw(false);
    }

    /** The circle's colour behind the initial (e.g. while picking an avatar colour). */
    public AvatarView color(int c) {
        color = c;
        invalidate();
        return this;
    }

    /** Show this picture (https URL) instead of the initial; null or empty keeps the initial. */
    public AvatarView photo(String u) {
        url = u == null || u.isEmpty() || "null".equals(u) ? null : u;
        photo = null;
        if (url != null) {
            String want = url;
            Images.load(want, b -> {
                if (!want.equals(url) || b == null) return;
                photo = b;
                setTextColor(Color.TRANSPARENT);
                setShadowLayer(0, 0, 0, 0);
                invalidate();
            });
        }
        invalidate();
        return this;
    }

    /** Draw this profile frame around the picture (null or unknown: none). */
    public AvatarView frame(String id) {
        frame = id == null || id.isEmpty() || "null".equals(id) || !Frames.known(id) ? null : id;
        setTextSize(TypedValue.COMPLEX_UNIT_PX, frame != null ? textPx * FRAMED : textPx);
        invalidate();
        return this;
    }

    /** Picture and frame from a player JSON (avatarUrl, frame). */
    public AvatarView of(JSONObject o) {
        if (o == null) return this;
        return photo(o.isNull("avatarUrl") ? null : o.optString("avatarUrl", null))
                .frame(o.isNull("frame") ? null : o.optString("frame", null));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, big = Math.min(w, h) / 2f;
        float r = frame != null ? big * FRAMED : big;
        float t = SystemClock.uptimeMillis() / 1000f;
        if (frame != null) Frames.back(canvas, p, path, frame, cx, cy, r, big, t);

        p.setStyle(Paint.Style.FILL);
        if (photo != null) {
            BitmapShader s = new BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = 2 * r / Math.min(photo.getWidth(), photo.getHeight());
            m.setScale(scale, scale);
            m.postTranslate(cx - photo.getWidth() * scale / 2f, cy - photo.getHeight() * scale / 2f);
            s.setLocalMatrix(m);
            p.setShader(s);
            canvas.drawCircle(cx, cy, r, p);
            p.setShader(null);
        } else {
            p.setShader(null);
            p.setColor(color);
            canvas.drawCircle(cx, cy, r, p);
            // A soft highlight, like the rest of the game's round things.
            p.setShader(new RadialGradient(cx - r * 0.4f, cy - r * 0.5f, r * 1.1f, 0x55ffffff, 0x00ffffff, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, p);
            p.setShader(null);
        }
        super.onDraw(canvas);

        if (frame != null) {
            Frames.front(canvas, p, path, frame, cx, cy, r, big, t);
            postInvalidateOnAnimation();
        }
    }

    // ------------------------------------------------------------------ frames

    /** The animated frames, drawn by id (the server's catalogue is shared/frames.ts). */
    static final class Frames {
        static boolean known(String id) {
            switch (id) {
                case "fire": case "hearts": case "neon": case "gold": case "frost": case "rainbow":
                    return true;
                default:
                    return false;
            }
        }

        /** Glow behind the picture. */
        static void back(Canvas c, Paint p, Path path, String id, float cx, float cy, float r, float R, float t) {
            int glow;
            switch (id) {
                case "fire": glow = 0xccff6a00; break;
                case "hearts": glow = 0xaaff4f9a; break;
                case "neon": glow = 0x9900f0ff; break;
                case "gold": glow = 0x99ffc400; break;
                case "frost": glow = 0x998fe3ff; break;
                default: glow = 0x88ffffff; break;
            }
            float pulse = 0.85f + 0.15f * (float) Math.sin(t * 3);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(cx, cy, R * pulse, new int[]{glow, glow, 0x00000000}, new float[]{0f, r / R, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, R, p);
            p.setShader(null);
            if ("fire".equals(id)) fireTongues(c, p, path, cx, cy, r, R, t, true);
        }

        /** Ring and effects over the picture's edge. */
        static void front(Canvas c, Paint p, Path path, String id, float cx, float cy, float r, float R, float t) {
            switch (id) {
                case "fire": fire(c, p, path, cx, cy, r, R, t); break;
                case "hearts": hearts(c, p, path, cx, cy, r, R, t); break;
                case "neon": neon(c, p, cx, cy, r, R, t); break;
                case "gold": gold(c, p, path, cx, cy, r, R, t); break;
                case "frost": frost(c, p, path, cx, cy, r, R, t); break;
                case "rainbow": rainbow(c, p, cx, cy, r, R, t); break;
                default: break;
            }
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setStrokeWidth(0);
        }

        private static void ring(Canvas c, Paint p, float cx, float cy, float r, float width, Shader s, int color) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(width);
            p.setShader(s);
            if (s == null) p.setColor(color);
            c.drawCircle(cx, cy, r, p);
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
        }

        // Fire burning all the way round: flickering tongues on every side, embers rising.
        private static void fire(Canvas c, Paint p, Path path, float cx, float cy, float r, float R, float t) {
            fireTongues(c, p, path, cx, cy, r, R, t, false);
            ring(c, p, cx, cy, r, (R - r) * 0.22f, new SweepGradient(cx, cy, new int[]{0xffffd000, 0xffff5a00, 0xffffd000, 0xffff5a00, 0xffffd000}, null), 0);
            // Embers drifting up and out.
            for (int i = 0; i < 7; i++) {
                float ph = (t * 0.7f + i / 7f) % 1f;
                double a = i * 2.39 + Math.floor(t * 0.7f + i / 7f) * 1.3;
                float d = r + (R - r) * (0.2f + 0.8f * ph);
                float x = cx + (float) Math.cos(a) * d, y = cy + (float) Math.sin(a) * d - ph * (R - r) * 0.5f;
                p.setColor(Color.argb((int) (255 * (1 - ph)), 255, 200 - (int) (120 * ph), 40));
                c.drawCircle(x, y, (R - r) * 0.07f * (1 - ph * 0.5f), p);
            }
        }

        private static void fireTongues(Canvas c, Paint p, Path path, float cx, float cy, float r, float R, float t, boolean outer) {
            int n = 16;
            float band = R - r;
            p.setStyle(Paint.Style.FILL);
            p.setShader(outer
                    ? new RadialGradient(cx, cy, R, new int[]{0xffff8a00, 0xffff8a00, 0xffe8260b, 0x00b30000}, new float[]{0f, r / R, (r + band * 0.6f) / R, 1f}, Shader.TileMode.CLAMP)
                    : new RadialGradient(cx, cy, R, new int[]{0xfffff3a0, 0xfffff3a0, 0xffffb000, 0x00ff6a00}, new float[]{0f, r / R, (r + band * 0.45f) / R, 0.97f}, Shader.TileMode.CLAMP));
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2 / n + (outer ? Math.PI / n : 0) + Math.sin(t * 0.8) * 0.05;
                float flick = 0.5f + 0.5f * (float) (Math.sin(t * 9 + i * 2.1) * 0.5 + Math.sin(t * 13.7 + i * 1.3) * 0.5);
                float len = band * (outer ? 0.62f + 0.38f * flick : 0.35f + 0.3f * flick);
                float half = (float) (Math.PI / n) * (outer ? 1.25f : 0.9f);
                float base = r * 0.96f, tip = r + len;
                // The tip sways a little sideways.
                double ta = a + Math.sin(t * 6 + i) * 0.12;
                path.reset();
                path.moveTo(cx + (float) Math.cos(a - half) * base, cy + (float) Math.sin(a - half) * base);
                path.quadTo(cx + (float) Math.cos(a - half * 0.6) * (base + len * 0.55f), cy + (float) Math.sin(a - half * 0.6) * (base + len * 0.55f),
                        cx + (float) Math.cos(ta) * tip, cy + (float) Math.sin(ta) * tip);
                path.quadTo(cx + (float) Math.cos(a + half * 0.6) * (base + len * 0.55f), cy + (float) Math.sin(a + half * 0.6) * (base + len * 0.55f),
                        cx + (float) Math.cos(a + half) * base, cy + (float) Math.sin(a + half) * base);
                path.close();
                c.drawPath(path, p);
            }
            p.setShader(null);
        }

        // Pink ring with hearts circling it and little hearts floating away.
        private static void hearts(Canvas c, Paint p, Path path, float cx, float cy, float r, float R, float t) {
            float band = R - r;
            ring(c, p, cx, cy, r + band * 0.08f, band * 0.22f,
                    new SweepGradient(cx, cy, new int[]{0xffff6fae, 0xffff2d6f, 0xffffb3d1, 0xffff2d6f, 0xffff6fae}, null), 0);
            int n = 8;
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2 / n + t * 0.6;
                float d = r + band * 0.5f;
                float beat = 1f + 0.18f * (float) Math.max(0, Math.sin(t * 6 + i));
                heart(c, p, path, cx + (float) Math.cos(a) * d, cy + (float) Math.sin(a) * d, band * 0.34f * beat, 0xffff3d7f);
            }
            for (int i = 0; i < 5; i++) {
                float ph = (t * 0.45f + i / 5f) % 1f;
                double a = -Math.PI / 2 + (i - 2) * 0.55;
                float d = r + band * ph;
                int alpha = (int) (230 * (1 - ph));
                heart(c, p, path, cx + (float) Math.cos(a) * d, cy + (float) Math.sin(a) * d, band * 0.22f, (alpha << 24) | 0xffb3d1);
            }
        }

        private static void heart(Canvas c, Paint p, Path path, float x, float y, float s, int color) {
            path.reset();
            path.moveTo(x, y + s * 0.45f);
            path.cubicTo(x - s * 1.1f, y - s * 0.25f, x - s * 0.45f, y - s * 0.95f, x, y - s * 0.35f);
            path.cubicTo(x + s * 0.45f, y - s * 0.95f, x + s * 1.1f, y - s * 0.25f, x, y + s * 0.45f);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            c.drawPath(path, p);
            p.setColor(((Color.alpha(color) * 0x66 / 255) << 24) | 0xffffff);
            c.drawCircle(x - s * 0.3f, y - s * 0.35f, s * 0.14f, p);
        }

        // Two glowing tubes, cyan and magenta, pulsing, with light running round.
        private static void neon(Canvas c, Paint p, float cx, float cy, float r, float R, float t) {
            float band = R - r;
            float pulse = 0.6f + 0.4f * (float) Math.abs(Math.sin(t * 2.5));
            tube(c, p, cx, cy, r + band * 0.12f, band * 0.14f, 0x00f0ff, pulse);
            tube(c, p, cx, cy, r + band * 0.55f, band * 0.1f, 0xff3df2, 1.6f - pulse);
            // Running sparks.
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 3; i++) {
                double a = t * 2.2 + i * Math.PI * 2 / 3;
                float d = r + band * 0.12f;
                p.setColor(0xffffffff);
                c.drawCircle(cx + (float) Math.cos(a) * d, cy + (float) Math.sin(a) * d, band * 0.09f, p);
                double b = -t * 1.7 + i * Math.PI * 2 / 3;
                float e = r + band * 0.55f;
                c.drawCircle(cx + (float) Math.cos(b) * e, cy + (float) Math.sin(b) * e, band * 0.07f, p);
            }
        }

        private static void tube(Canvas c, Paint p, float cx, float cy, float r, float w, int rgb, float glow) {
            glow = Math.max(0.2f, Math.min(1f, glow));
            p.setStyle(Paint.Style.STROKE);
            for (int k = 3; k >= 1; k--) {
                p.setStrokeWidth(w * (1 + k * 1.2f));
                p.setColor(((int) (40 * glow / k) << 24) | rgb);
                c.drawCircle(cx, cy, r, p);
            }
            p.setStrokeWidth(w);
            p.setColor(0xff000000 | rgb);
            c.drawCircle(cx, cy, r, p);
            p.setStrokeWidth(w * 0.35f);
            p.setColor(0xddffffff);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.FILL);
        }

        // A thick gold band with a moving shine, gems, twinkling stars and a crown on top.
        private static void gold(Canvas c, Paint p, Path path, float cx, float cy, float r, float R, float t) {
            float band = R - r;
            SweepGradient s = new SweepGradient(cx, cy, new int[]{0xff8a5a00, 0xffffe58a, 0xffd99a00, 0xfffff6c8, 0xff8a5a00}, null);
            Matrix rot = new Matrix();
            rot.setRotate(t * 60, cx, cy);
            s.setLocalMatrix(rot);
            ring(c, p, cx, cy, r + band * 0.18f, band * 0.4f, s, 0);
            ring(c, p, cx, cy, r + band * 0.38f, band * 0.06f, null, 0xff6b4200);
            int n = 6;
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2 / n + Math.PI / 2;
                float x = cx + (float) Math.cos(a) * (r + band * 0.18f), y = cy + (float) Math.sin(a) * (r + band * 0.18f);
                float g = band * 0.17f;
                path.reset();
                path.moveTo(x, y - g);
                path.lineTo(x + g * 0.75f, y);
                path.lineTo(x, y + g);
                path.lineTo(x - g * 0.75f, y);
                path.close();
                p.setColor(i % 2 == 0 ? 0xffe0115f : 0xff1aa3ff);
                c.drawPath(path, p);
                p.setColor(0x99ffffff);
                c.drawCircle(x - g * 0.2f, y - g * 0.3f, g * 0.22f, p);
            }
            // Crown.
            float cw = band * 1.2f, ch = band * 0.75f, top = cy - r - band * 0.95f;
            path.reset();
            path.moveTo(cx - cw / 2, top + ch);
            path.lineTo(cx - cw / 2, top + ch * 0.3f);
            path.lineTo(cx - cw / 4, top + ch * 0.62f);
            path.lineTo(cx, top);
            path.lineTo(cx + cw / 4, top + ch * 0.62f);
            path.lineTo(cx + cw / 2, top + ch * 0.3f);
            path.lineTo(cx + cw / 2, top + ch);
            path.close();
            p.setShader(new LinearGradient(0, top, 0, top + ch, 0xfffff2a8, 0xffd18b00, Shader.TileMode.CLAMP));
            c.drawPath(path, p);
            p.setShader(null);
            p.setColor(0xffe0115f);
            c.drawCircle(cx, top + ch * 0.68f, band * 0.09f, p);
            for (int i = 0; i < 4; i++) {
                float tw = (float) Math.max(0, Math.sin(t * 3.1 + i * 1.9));
                double a = i * 1.7 + 0.4;
                float d = r + band * (0.55f + 0.3f * (i % 2));
                star(c, p, path, cx + (float) Math.cos(a) * d, cy + (float) Math.sin(a) * d, band * 0.28f * tw, 0xffffffff);
            }
        }

        private static void star(Canvas c, Paint p, Path path, float x, float y, float s, int color) {
            if (s <= 0.5f) return;
            path.reset();
            path.moveTo(x, y - s);
            path.quadTo(x, y, x + s, y);
            path.quadTo(x, y, x, y + s);
            path.quadTo(x, y, x - s, y);
            path.quadTo(x, y, x, y - s);
            path.close();
            p.setColor(color);
            c.drawPath(path, p);
        }

        // Ice: a frosty ring, six crystals slowly turning, and glints.
        private static void frost(Canvas c, Paint p, Path path, float cx, float cy, float r, float R, float t) {
            float band = R - r;
            ring(c, p, cx, cy, r + band * 0.12f, band * 0.26f,
                    new SweepGradient(cx, cy, new int[]{0xffe8fbff, 0xff6fd3ff, 0xffffffff, 0xff2fa8e8, 0xffe8fbff}, null), 0);
            int n = 6;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2 / n + t * 0.35;
                float x = cx + (float) Math.cos(a) * (r + band * 0.56f), y = cy + (float) Math.sin(a) * (r + band * 0.56f);
                float s = band * 0.3f;
                p.setStrokeWidth(band * 0.06f);
                p.setColor(0xffffffff);
                for (int k = 0; k < 3; k++) {
                    double b = k * Math.PI / 3 + t;
                    float dx = (float) Math.cos(b) * s, dy = (float) Math.sin(b) * s;
                    c.drawLine(x - dx, y - dy, x + dx, y + dy, p);
                }
                p.setColor(0xff8fe3ff);
                p.setStrokeWidth(band * 0.03f);
                c.drawCircle(x, y, s * 0.35f, p);
            }
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 3; i++) {
                float tw = (float) Math.max(0, Math.sin(t * 2.3 + i * 2.1));
                double a = i * 2.1 + 1;
                star(c, p, path, cx + (float) Math.cos(a) * (r + band * 0.12f), cy + (float) Math.sin(a) * (r + band * 0.12f), band * 0.22f * tw, 0xffffffff);
            }
        }

        // Every colour, spinning.
        private static void rainbow(Canvas c, Paint p, float cx, float cy, float r, float R, float t) {
            float band = R - r;
            SweepGradient s = new SweepGradient(cx, cy, new int[]{0xffff3b3b, 0xffffa21f, 0xfffff23b, 0xff3bff6b, 0xff3bc8ff, 0xff7a4dff, 0xffff3bd4, 0xffff3b3b}, null);
            Matrix rot = new Matrix();
            rot.setRotate(t * 90, cx, cy);
            s.setLocalMatrix(rot);
            ring(c, p, cx, cy, r + band * 0.2f, band * 0.42f, s, 0);
            ring(c, p, cx, cy, r + band * 0.2f, band * 0.1f, null, 0x66ffffff);
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 6; i++) {
                double a = -t * 1.3 + i * Math.PI / 3;
                float d = r + band * 0.68f;
                p.setColor(Color.HSVToColor(new float[]{(i * 60 + t * 90) % 360, 0.7f, 1f}));
                c.drawCircle(cx + (float) Math.cos(a) * d, cy + (float) Math.sin(a) * d, band * 0.1f, p);
            }
        }
    }

    // ------------------------------------------------------------------ pictures

    /** Downloads and caches profile pictures (small, round-cropped when drawn). */
    static final class Images {
        interface Cb {
            void done(Bitmap b);
        }

        private static final OkHttpClient HTTP = new OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build();
        private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
        private static final Map<String, List<Cb>> WAITING = new HashMap<>();

        static void trim() {
            CACHE.evictAll();
        }

        static void load(String url, Cb cb) {
            Bitmap hit = CACHE.get(url);
            if (hit != null) {
                cb.done(hit);
                return;
            }
            synchronized (WAITING) {
                List<Cb> list = WAITING.get(url);
                if (list != null) {
                    list.add(cb);
                    return;
                }
                list = new ArrayList<>();
                list.add(cb);
                WAITING.put(url, list);
            }
            Request req;
            try {
                // Google serves the picture at any size: ask for one big enough to stay sharp.
                req = new Request.Builder().url(url.replaceAll("=s\\d+-c$", "=s256-c")).build();
            } catch (IllegalArgumentException e) {
                finish(url, null);
                return;
            }
            HTTP.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    finish(url, null);
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
                            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 256) sample *= 2;
                            o = new BitmapFactory.Options();
                            o.inSampleSize = sample;
                            b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                        }
                    } catch (Exception ignored) {
                        b = null;
                    }
                    if (b != null) CACHE.put(url, b);
                    finish(url, b);
                }
            });
        }

        private static void finish(String url, Bitmap b) {
            List<Cb> list;
            synchronized (WAITING) {
                list = WAITING.remove(url);
            }
            if (list == null) return;
            LudoApp.MAIN.post(() -> {
                for (Cb cb : list) cb.done(b);
            });
        }
    }
}
