package com.ludo3d.game.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * A short buzz when the dice come to you (your turn to roll). It uses the vibrator itself,
 * so it works even when the phone's touch vibration is off; players can turn it off in
 * the profile settings.
 */
public final class Haptics {
    private static final String PREF = "vibrate_on";

    private Haptics() {}

    public static boolean isEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(PREF, true);
    }

    public static void setEnabled(SharedPreferences prefs, boolean on) {
        prefs.edit().putBoolean(PREF, on).apply();
    }

    /** Two quick taps: "your turn". */
    public static void turn(Context c, SharedPreferences prefs) {
        if (!isEnabled(prefs)) return;
        try {
            Vibrator v;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v == null || !v.hasVibrator()) return;
            long[] pattern = {0, 45, 70, 45};
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(pattern, new int[]{0, 180, 0, 180}, -1));
            } else {
                v.vibrate(pattern, -1);
            }
        } catch (RuntimeException ignored) {
            // No vibrator, or the system refused: a buzz is never worth a crash.
        }
    }
}
