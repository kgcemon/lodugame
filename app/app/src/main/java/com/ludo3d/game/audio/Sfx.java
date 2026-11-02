package com.ludo3d.game.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Game sound effects, the one place every sound goes through. Each sound is synthesized
 * once (see Synth), cached as a small WAV in the app's cache folder and played through
 * SoundPool, so playback is instant. Every sound keeps track of its last stream so it can
 * be stopped (stop / stopAll), "exclusive" sounds restart instead of stacking on top of
 * themselves, and a repeat of the same sound within its minimum gap is dropped (so a roll
 * that already rattled on tap doesn't rattle again when the server confirms it).
 * Safe to call from any thread.
 */
public final class Sfx {
    public enum S {
        CLICK, DICE_SHAKE, STEP, CAPTURE, HOME, TURN, EXTRA, VICTORY, DEFEAT, ERROR, MESSAGE,
        E_LAUGH, E_LOVE, E_FIRE, E_CLAP, E_WOW, E_CRY, E_PARTY, E_COOL, E_POP,
        SEARCH, TICK, MATCH_FOUND, WARNING
    }

    /** Bump when a sound's design changes so cached files are regenerated. */
    private static final int VERSION = 4;
    private static Sfx instance;

    private final SharedPreferences prefs;
    private final SoundPool pool;
    private final int[] ids = new int[S.values().length];
    /** Sample ids SoundPool finished loading (the callback may beat the ids[] assignment). */
    private final java.util.Set<Integer> loaded = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Last stream started for each sound (0 = none), and when it started. */
    private final int[] streams = new int[S.values().length];
    private final long[] startedAt = new long[S.values().length];
    private volatile boolean enabled;

    private Sfx(Context c, SharedPreferences prefs) {
        this.prefs = prefs;
        enabled = prefs.getBoolean("sound_on", true);
        pool = new SoundPool.Builder()
                .setMaxStreams(8)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        pool.setOnLoadCompleteListener((p, sampleId, status) -> {
            if (status == 0) loaded.add(sampleId);
            else android.util.Log.w("Sfx", "sound failed to load: sample " + sampleId + " status " + status);
        });
        File dir = new File(c.getCacheDir(), "sfx_v" + VERSION);
        new Thread(() -> {
            if (!dir.exists() && !dir.mkdirs()) return;
            for (S s : S.values()) {
                if (s == S.DICE_SHAKE) {
                    // A real recording: dice shaken, thrown, bouncing and settling, cut to the
                    // length of the roll animation (res/raw/dice_roll.wav).
                    ids[s.ordinal()] = pool.load(c, com.ludo3d.game.R.raw.dice_roll, 1);
                    continue;
                }
                File f = new File(dir, s.name().toLowerCase() + ".wav");
                try {
                    if (!f.exists() || f.length() < 100) writeWav(f, design(s));
                    ids[s.ordinal()] = pool.load(f.getPath(), 1);
                } catch (IOException e) {
                    // A missing sound just stays silent.
                    android.util.Log.w("Sfx", "could not prepare " + s, e);
                }
            }
        }, "sfx-init").start();
    }

    public static synchronized void init(Context c, SharedPreferences prefs) {
        if (instance == null) instance = new Sfx(c.getApplicationContext(), prefs);
    }

    public static Sfx get() {
        return instance;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean on) {
        enabled = on;
        prefs.edit().putBoolean("sound_on", on).apply();
        if (!on) stopAll();
    }

    public void play(S s) {
        play(s, 1f, 1f);
    }

    /** volume 0..1, rate 0.5..2 (pitch). */
    public void play(S s, float volume, float rate) {
        int i = s.ordinal();
        int id = ids[i];
        if (!enabled || id == 0 || !loaded.contains(id)) return;
        long now = android.os.SystemClock.uptimeMillis();
        synchronized (streams) {
            if (streams[i] != 0 && now - startedAt[i] < minGapMs(s)) return;
            if (exclusive(s) && streams[i] != 0) pool.stop(streams[i]);
            int stream = pool.play(id, volume, volume, priority(s), 0, Math.max(0.5f, Math.min(2f, rate)));
            if (stream != 0) {
                streams[i] = stream;
                startedAt[i] = now;
            }
        }
    }

    /** Stop the latest instance of a sound (e.g. the dice rattle when a roll is refused). */
    public void stop(S s) {
        int i = s.ordinal();
        synchronized (streams) {
            if (streams[i] != 0) pool.stop(streams[i]);
            streams[i] = 0;
        }
    }

    /** Silence everything (leaving a match, the app going to the background, muting). */
    public void stopAll() {
        synchronized (streams) {
            for (int i = 0; i < streams.length; i++) {
                if (streams[i] != 0) pool.stop(streams[i]);
                streams[i] = 0;
            }
        }
    }

    /** Sounds that restart rather than layering a second copy over themselves. */
    private static boolean exclusive(S s) {
        switch (s) {
            case STEP:
            case CLICK:
                return false;
            default:
                return true;
        }
    }

    /** A second play of the same sound sooner than this is a duplicate trigger. */
    private static long minGapMs(S s) {
        switch (s) {
            case DICE_SHAKE: return 1000;
            case CAPTURE: case HOME: case TURN: case EXTRA: return 150;
            case STEP: return 40;
            default: return 60;
        }
    }

    /** When SoundPool runs out of streams, game sounds win over decoration. */
    private static int priority(S s) {
        switch (s) {
            case DICE_SHAKE: case CAPTURE: case WARNING: case VICTORY: case DEFEAT: return 3;
            case STEP: case HOME: case TURN: case EXTRA: return 2;
            default: return 1;
        }
    }

    /** Sound for an emote effect name (shared/social.ts EmoteEffect). */
    public static S forEmoteEffect(String effect) {
        switch (effect) {
            case "laugh": return S.E_LAUGH;
            case "love": return S.E_LOVE;
            case "fire": return S.E_FIRE;
            case "clap": return S.E_CLAP;
            case "wow": return S.E_WOW;
            case "cry": return S.E_CRY;
            case "party": return S.E_PARTY;
            case "cool": return S.E_COOL;
            default: return S.E_POP;
        }
    }

    // ------------------------------------------------------------------ sound design

    private static final double C5 = 523.25, E5 = 659.26, G5 = 783.99, A5 = 880, B5 = 987.77;
    private static final double C6 = 1046.5, E6 = 1318.5, G6 = 1568, B6 = 1975.5, C7 = 2093;

    static short[] design(S s) {
        Synth x;
        switch (s) {
            case CLICK:
                return new Synth(0.05).tone(0, 0.04, 1900, 1200, Synth.SINE, 0.6, 0.001, 3)
                        .noise(0, 0.012, 0.25, 0.9, 0.6, 0.0005, 2).render(0.45);
            case STEP:
                // A token tapping onto a wooden square.
                return new Synth(0.09)
                        .tone(0, 0.08, 560, 380, Synth.TRIANGLE, 0.7, 0.001, 3)
                        .noise(0, 0.02, 0.3, 0.6, 0, 0.0005, 2)
                        .render(0.55);
            case CAPTURE:
                // Whoosh out of the board, then a knock as it lands back home.
                return new Synth(0.65)
                        .noise(0, 0.38, 0.5, 0.12, 0, 0.12, 1.2)
                        .tone(0, 0.36, 900, 160, Synth.SAW, 0.28, 0.01, 1.4)
                        .tone(0.34, 0.26, 120, 55, Synth.SINE, 1, 0.002, 2)
                        .noise(0.34, 0.06, 0.55, 0.3, 0, 0.001, 2)
                        .render(0.9);
            case HOME:
                return new Synth(0.8).bell(0, 0.45, C6, 0.5).bell(0.09, 0.45, E6, 0.5).bell(0.18, 0.45, G6, 0.5)
                        .bell(0.27, 0.5, C7, 0.55).render(0.8);
            case TURN:
                return new Synth(0.5).bell(0, 0.35, G5, 0.6).bell(0.12, 0.38, C6, 0.6).render(0.65);
            case EXTRA:
                return new Synth(0.45).bell(0, 0.22, E6, 0.45).bell(0.06, 0.22, G6, 0.45).bell(0.12, 0.3, B6, 0.45)
                        .noise(0.1, 0.25, 0.12, 0.9, 0.85, 0.02, 2).render(0.6);
            case VICTORY: {
                x = new Synth(1.8);
                x.tone(0, 0.18, C5, C5, Synth.TRIANGLE, 0.5, 0.005, 1.5).tone(0.15, 0.18, E5, E5, Synth.TRIANGLE, 0.5, 0.005, 1.5)
                        .tone(0.3, 0.18, G5, G5, Synth.TRIANGLE, 0.5, 0.005, 1.5);
                for (double f : new double[]{C5, E5, G5, C6}) x.tone(0.48, 1.2, f, f, Synth.TRIANGLE, 0.3, 0.01, 1.6, 5, 0.004);
                x.bell(0.5, 0.6, C7, 0.25).bell(0.7, 0.6, G6, 0.2).bell(0.9, 0.7, C7, 0.2);
                x.noise(0.5, 0.9, 0.1, 0.9, 0.9, 0.05, 2);
                return x.render(0.85);
            }
            case DEFEAT:
                return new Synth(1.2)
                        .tone(0, 0.35, 392, 392, Synth.TRIANGLE, 0.5, 0.01, 1.2, 5, 0.006)
                        .tone(0.3, 0.35, 329.6, 329.6, Synth.TRIANGLE, 0.5, 0.01, 1.2, 5, 0.006)
                        .tone(0.6, 0.6, 261.6, 246, Synth.TRIANGLE, 0.5, 0.01, 1.4, 5, 0.01)
                        .render(0.6);
            case ERROR:
                return new Synth(0.22).tone(0, 0.2, 165, 150, Synth.SQUARE, 0.4, 0.004, 1.5)
                        .tone(0, 0.2, 172, 155, Synth.SQUARE, 0.3, 0.004, 1.5).render(0.45);
            case MESSAGE:
                return new Synth(0.25).tone(0, 0.08, A5, 1320, Synth.SINE, 0.5, 0.003, 1.5).bell(0.07, 0.17, 1320, 0.45)
                        .render(0.5);
            case E_LAUGH: {
                // "Ha-ha-ha-ha": bouncy blips, each a little higher.
                x = new Synth(0.65);
                for (int i = 0; i < 4; i++) {
                    double t = i * 0.13, f = 520 + i * 45;
                    x.tone(t, 0.11, f * 1.15, f, Synth.TRIANGLE, 0.6 - i * 0.08, 0.005, 1.6, 28, 0.03);
                }
                return x.render(0.7);
            }
            case E_LOVE:
                return new Synth(0.95)
                        .tone(0, 0.45, A5, A5, Synth.SINE, 0.5, 0.03, 1.2, 6, 0.008)
                        .tone(0, 0.45, A5 * 2, A5 * 2, Synth.SINE, 0.12, 0.03, 2)
                        .tone(0.3, 0.6, 1108.7, 1108.7, Synth.SINE, 0.5, 0.03, 1.4, 6, 0.008)
                        .tone(0.3, 0.6, 1108.7 * 2, 1108.7 * 2, Synth.SINE, 0.12, 0.03, 2)
                        .tone(0, 0.9, 440, 440, Synth.SINE, 0.15, 0.08, 1.5)
                        .render(0.7);
            case E_FIRE: {
                // Crackles over a rising whoosh.
                x = new Synth(0.8);
                x.noise(0, 0.75, 0.45, 0.08, 0, 0.25, 1);
                for (int i = 0; i < 14; i++) x.noise(x.random() * 0.7, 0.01, 0.6, 0.9, 0.7, 0.0005, 2);
                x.tone(0, 0.6, 180, 420, Synth.SAW, 0.1, 0.15, 1.2);
                return x.render(0.75);
            }
            case E_CLAP: {
                x = new Synth(0.75);
                for (int i = 0; i < 4; i++) {
                    double t = i * 0.16 + x.random() * 0.02;
                    x.noise(t, 0.06, 0.9, 0.7, 0.75, 0.001, 3);
                    x.noise(t + 0.008, 0.05, 0.5, 0.5, 0.6, 0.001, 3);
                }
                return x.render(0.75);
            }
            case E_WOW:
                return new Synth(0.75)
                        .tone(0, 0.35, 280, 820, Synth.SINE, 0.55, 0.02, 0.4, 6, 0.01)
                        .tone(0.33, 0.4, 820, 640, Synth.SINE, 0.55, 0.005, 1.6, 6, 0.015)
                        .tone(0, 0.7, 560, 1280, Synth.SINE, 0.12, 0.05, 1.5)
                        .render(0.7);
            case E_CRY:
                return new Synth(1.0)
                        .tone(0, 0.95, 720, 330, Synth.TRIANGLE, 0.5, 0.03, 1.1, 7, 0.035)
                        .tone(0, 0.95, 1440, 660, Synth.SINE, 0.1, 0.03, 1.5, 7, 0.035)
                        .render(0.6);
            case E_PARTY: {
                // Party horn, then confetti sparkles.
                x = new Synth(1.0);
                x.tone(0, 0.42, 330, 470, Synth.SAW, 0.4, 0.01, 0.8, 32, 0.02);
                x.tone(0, 0.42, 660, 940, Synth.SQUARE, 0.12, 0.01, 0.8, 32, 0.02);
                x.bell(0.42, 0.3, C7, 0.25).bell(0.5, 0.3, G6, 0.22).bell(0.58, 0.35, E6 * 2, 0.18).bell(0.66, 0.3, C7, 0.18);
                x.noise(0.4, 0.5, 0.08, 0.9, 0.9, 0.02, 2);
                return x.render(0.75);
            }
            case E_COOL:
                return new Synth(0.7)
                        .tone(0, 0.3, 110, 82, Synth.SINE, 0.7, 0.005, 1.8)
                        .bell(0.08, 0.5, E5, 0.3).bell(0.08, 0.5, 830.6, 0.25).bell(0.08, 0.55, B5, 0.25)
                        .render(0.65);
            case SEARCH: {
                // A soft, hopeful 2-bar loop for matchmaking: rising arpeggio over a warm pad.
                x = new Synth(2.0);
                double[] notes = {C5, E5, G5, B5, C6, G5, E5, G5};
                for (int i = 0; i < notes.length; i++) x.bell(i * 0.25, 0.5, notes[i], 0.28);
                for (double f : new double[]{261.63, 329.63, 392.0}) x.tone(0, 2.0, f, f, Synth.SINE, 0.12, 0.35, 1.1, 4, 0.004);
                x.noise(0, 2.0, 0.025, 0.9, 0.92, 0.5, 1.2); // airy shimmer
                return x.render(0.42);
            }
            case TICK:
                // A light clock tick for the final seconds.
                return new Synth(0.06)
                        .tone(0, 0.03, 2600, 2200, Synth.SINE, 0.6, 0.0005, 3)
                        .noise(0, 0.01, 0.4, 0.9, 0.8, 0.0003, 2)
                        .render(0.4);
            case WARNING:
                // Turn almost over: two sharp, urgent beeps.
                return new Synth(0.32)
                        .tone(0, 0.11, 1175, 1175, Synth.SQUARE, 0.35, 0.002, 2)
                        .tone(0.16, 0.11, 1175, 1175, Synth.SQUARE, 0.35, 0.002, 2)
                        .render(0.5);
            case MATCH_FOUND: {
                // Upward sweep into a bright chord with sparkles.
                x = new Synth(1.3);
                x.tone(0, 0.35, 300, 1200, Synth.SAW, 0.15, 0.02, 0.6);
                x.noise(0, 0.35, 0.12, 0.3, 0, 0.25, 0.8);
                for (double f : new double[]{C6, E6, G6}) x.tone(0.32, 0.9, f, f, Synth.TRIANGLE, 0.28, 0.005, 1.8, 5, 0.004);
                x.bell(0.32, 0.6, C7, 0.3).bell(0.45, 0.5, E6 * 2, 0.22).bell(0.6, 0.55, G6 * 2, 0.18);
                x.noise(0.32, 0.8, 0.07, 0.9, 0.9, 0.02, 2);
                return x.render(0.8);
            }
            default: // E_POP
                return new Synth(0.25)
                        .tone(0, 0.07, 380, 1250, Synth.SINE, 0.7, 0.002, 1.5)
                        .noise(0.05, 0.015, 0.3, 0.9, 0.6, 0.0005, 2)
                        .bell(0.06, 0.15, 1500, 0.2)
                        .render(0.6);
        }
    }

    private static void writeWav(File f, short[] pcm) throws IOException {
        int dataLen = pcm.length * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + dataLen).put(new byte[]{'W', 'A', 'V', 'E'});
        b.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(Synth.RATE).putInt(Synth.RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put(new byte[]{'d', 'a', 't', 'a'}).putInt(dataLen);
        for (short v : pcm) b.putShort(v);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(b.array());
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
    }
}
