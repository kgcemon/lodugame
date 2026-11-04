package com.ludox.game.ui;

import android.app.ActivityManager;
import android.content.Context;
import android.provider.Settings;

import com.ludox.game.LudoApp;
import com.ludox.game.gl.ConfigChooser;

/**
 * How much the screen may do on this phone: chosen automatically from its memory, or by the player.
 * Presets change only what is drawn, never the game: the server plays by the same rules for everyone.
 *
 *   Low     30 fps while animating, no edge smoothing, light effects, no board ambience
 *   Medium  light effects, no edge smoothing
 *   High    full effects, 2x edge smoothing
 *   Ultra   full effects, 4x edge smoothing
 *
 * Effect levels. Full: everything. Light: few pieces and no trails. Reduced: no motion at all, only
 * the numbers and a short line (Android's animations are off).
 */
final class Effects {
    private Effects() {}

    static final int REDUCED = 0, LIGHT = 1, FULL = 2;
    /** The player's choice: auto (or unset), low, medium, high, ultra. */
    static final String PREF = "graphics_preset";
    static final String[] PRESETS = {"auto", "low", "medium", "high", "ultra"};

    /** The connection is struggling: cosmetics step back until it recovers. */
    static volatile boolean weakNetwork;

    private static final long GB = 1024L * 1024 * 1024;

    static String choice() {
        return LudoApp.get().prefs.getString(PREF, "auto");
    }

    static void choose(String preset) {
        LudoApp.get().prefs.edit().putString(PREF, preset).apply();
        apply();
    }

    /** What is in force: the player's choice, or what this phone can carry. */
    static String preset() {
        String c = choice();
        return "auto".equals(c) ? autoPreset(LudoApp.get()) : c;
    }

    private static String autoPreset(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        if (am.isLowRamDevice() || info.totalMem < 2 * GB) return "low";
        if (info.totalMem < 4 * GB) return "medium";
        return "high";
    }

    /** Set what the GL surfaces read when they are created. Call at start-up and on a change. */
    static void apply() {
        String p = preset();
        ConfigChooser.maxSamples = "ultra".equals(p) ? 4 : "high".equals(p) ? 2 : 0;
    }

    static String label(String preset) {
        switch (preset) {
            case "low": return "Low";
            case "medium": return "Medium";
            case "high": return "High";
            case "ultra": return "Ultra";
            default: return "Automatic (" + label(autoPreset(LudoApp.get())) + ")";
        }
    }

    static boolean capFrameRate() {
        return "low".equals(preset());
    }

    static boolean ambience() {
        return !"low".equals(preset());
    }

    static int level() {
        LudoApp app = LudoApp.get();
        float scale = Settings.Global.getFloat(app.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        if (scale == 0f) return REDUCED;
        if (weakNetwork) return LIGHT;
        return fullWanted() ? FULL : LIGHT;
    }

    static boolean fullWanted() {
        String p = preset();
        return "high".equals(p) || "ultra".equals(p);
    }
}
