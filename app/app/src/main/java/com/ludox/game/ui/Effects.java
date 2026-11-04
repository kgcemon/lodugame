package com.ludox.game.ui;

import android.app.ActivityManager;
import android.content.Context;
import android.provider.Settings;

import com.ludox.game.LudoApp;

/**
 * How much the reward, capture and home effects may do on this phone. Full: everything.
 * Light: few pieces and no trails (low-end phones, or the player's own choice in Settings).
 * Reduced: no motion at all, only the numbers and a short line (Android's animations are off).
 */
final class Effects {
    private Effects() {}

    static final int REDUCED = 0, LIGHT = 1, FULL = 2;
    static final String PREF = "effects_full";

    private static Boolean lowEnd;

    /** Below this much memory a phone gets the light effects unless the player asks for more. */
    private static final long LOW_END_BYTES = 2L * 1024 * 1024 * 1024;

    static int level() {
        LudoApp app = LudoApp.get();
        float scale = Settings.Global.getFloat(app.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        if (scale == 0f) return REDUCED;
        return fullWanted() ? FULL : LIGHT;
    }

    /** The Settings switch: the player's choice, or by default whatever this phone can carry. */
    static boolean fullWanted() {
        LudoApp app = LudoApp.get();
        return app.prefs.contains(PREF) ? app.prefs.getBoolean(PREF, true) : !lowEnd(app);
    }

    private static boolean lowEnd(Context c) {
        if (lowEnd == null) {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            lowEnd = am.isLowRamDevice() || info.totalMem < LOW_END_BYTES;
        }
        return lowEnd;
    }
}
