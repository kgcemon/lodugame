package com.ludo3d.game.audio;

import java.util.Random;

/**
 * Tiny offline synthesizer: every game sound is generated from tones and filtered noise
 * (like the website's Web Audio sounds), so the app ships no audio files.
 */
final class Synth {
    static final int RATE = 44100;
    static final int SINE = 0, TRIANGLE = 1, SQUARE = 2, SAW = 3;

    private final float[] buf;
    private final Random rng = new Random(7);

    Synth(double seconds) {
        buf = new float[(int) (seconds * RATE)];
    }

    private static double wave(int shape, double phase) {
        double p = phase - Math.floor(phase);
        switch (shape) {
            case TRIANGLE: return 4 * Math.abs(p - 0.5) - 1;
            case SQUARE: return Math.tanh(Math.sin(2 * Math.PI * p) * 4); // soft square
            case SAW: return 2 * p - 1;
            default: return Math.sin(2 * Math.PI * p);
        }
    }

    /**
     * A tone gliding exponentially from f0 to f1, with a fast attack and a decay whose
     * steepness is `curve` (1 = linear fade, 3+ = percussive). Optional vibrato.
     */
    Synth tone(double start, double dur, double f0, double f1, int shape, double gain, double attack, double curve,
               double vibRate, double vibDepth) {
        int s0 = (int) (start * RATE), n = (int) (dur * RATE);
        double phase = 0;
        for (int i = 0; i < n && s0 + i < buf.length; i++) {
            double t = i / (double) RATE, x = i / (double) n;
            double f = f0 * Math.pow(f1 / f0, x);
            if (vibDepth > 0) f *= 1 + vibDepth * Math.sin(2 * Math.PI * vibRate * t);
            phase += f / RATE;
            double env = Math.min(1, t / Math.max(attack, 1e-4)) * Math.pow(1 - x, curve);
            buf[s0 + i] += (float) (wave(shape, phase) * env * gain);
        }
        return this;
    }

    Synth tone(double start, double dur, double f0, double f1, int shape, double gain, double attack, double curve) {
        return tone(start, dur, f0, f1, shape, gain, attack, curve, 0, 0);
    }

    /** A bell-like note: fundamental plus soft overtones. */
    Synth bell(double start, double dur, double f, double gain) {
        tone(start, dur, f, f, SINE, gain, 0.004, 2.2);
        tone(start, dur * 0.7, f * 2, f * 2, SINE, gain * 0.35, 0.003, 3);
        tone(start, dur * 0.4, f * 3.01, f * 3.01, SINE, gain * 0.15, 0.002, 4);
        return this;
    }

    /**
     * Filtered noise. `lp` / `hp` are one-pole coefficients in 0..1 (lp: smaller = darker,
     * hp: larger = thinner). `attack` shapes a swell (whooshes).
     */
    Synth noise(double start, double dur, double gain, double lp, double hp, double attack, double curve) {
        int s0 = (int) (start * RATE), n = (int) (dur * RATE);
        double low = 0, prevIn = 0, high = 0;
        for (int i = 0; i < n && s0 + i < buf.length; i++) {
            double t = i / (double) RATE, x = i / (double) n;
            double in = rng.nextDouble() * 2 - 1;
            low += lp * (in - low);
            high = hp * (high + low - prevIn);
            prevIn = low;
            double v = hp > 0 ? high : low;
            double env = Math.min(1, t / Math.max(attack, 1e-4)) * Math.pow(1 - x, curve);
            buf[s0 + i] += (float) (v * env * gain);
        }
        return this;
    }

    double random() {
        return rng.nextDouble();
    }

    /** Normalised 16-bit PCM with a 4 ms fade-out so nothing clicks at the end. */
    short[] render(double peak) {
        float max = 1e-6f;
        for (float v : buf) max = Math.max(max, Math.abs(v));
        double k = peak / max;
        short[] out = new short[buf.length];
        int fade = Math.min(buf.length, RATE / 250);
        for (int i = 0; i < buf.length; i++) {
            double v = Math.tanh(buf[i] * k * 1.1) / Math.tanh(1.1);
            if (i >= buf.length - fade) v *= (buf.length - i) / (double) fade;
            out[i] = (short) Math.max(-32767, Math.min(32767, Math.round(v * 32767)));
        }
        return out;
    }
}
