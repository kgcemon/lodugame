package com.ludo3d.game.game;

/**
 * Board geometry on the classic 15x15 grid, ported from shared/board.ts. A cell is
 * {col, row}; row 0 is the far edge. Yards: red top-left, green top-right, yellow
 * bottom-right, blue bottom-left. Play runs clockwise red -> green -> yellow -> blue.
 * World coordinates: x = col - 7, z = row - 7, y up.
 */
public final class Board {
    private Board() {}

    public static final String[] COLORS = {"red", "green", "yellow", "blue"};
    public static final int TRACK_LEN = 52;
    public static final int HOME_START = 51;
    public static final int FINISH = 56;
    public static final int[] SAFE_INDICES = {0, 8, 13, 21, 26, 34, 39, 47};
    public static final int[] START_INDICES = {0, 13, 26, 39};

    public static final int[] COLOR_ARGB = {0xffe53945, 0xff22b46b, 0xfff6bf26, 0xff2f7cf0};

    public static final float[][] TRACK = buildTrack();

    private static final float[][] YARD_ORIGIN = {{0, 0}, {9, 0}, {9, 9}, {0, 9}};
    private static final float[][] YARD_SLOTS = {{1.5f, 1.5f}, {3.5f, 1.5f}, {1.5f, 3.5f}, {3.5f, 3.5f}};
    private static final float[][] FINISH_SPOT = {{6.15f, 7}, {7, 6.15f}, {7.85f, 7}, {7, 7.85f}};

    public static int colorIndex(String color) {
        for (int i = 0; i < 4; i++) if (COLORS[i].equals(color)) return i;
        return 0;
    }

    public static int colorArgb(String color) {
        return COLOR_ARGB[colorIndex(color)];
    }

    private static float[][] buildTrack() {
        float[][] t = new float[52][];
        int n = 0;
        for (int c = 1; c <= 5; c++) t[n++] = new float[]{c, 6};
        for (int r = 5; r >= 0; r--) t[n++] = new float[]{6, r};
        t[n++] = new float[]{7, 0};
        for (int r = 0; r <= 5; r++) t[n++] = new float[]{8, r};
        for (int c = 9; c <= 14; c++) t[n++] = new float[]{c, 6};
        t[n++] = new float[]{14, 7};
        for (int c = 14; c >= 9; c--) t[n++] = new float[]{c, 8};
        for (int r = 9; r <= 14; r++) t[n++] = new float[]{8, r};
        t[n++] = new float[]{7, 14};
        for (int r = 14; r >= 9; r--) t[n++] = new float[]{6, r};
        for (int c = 5; c >= 0; c--) t[n++] = new float[]{c, 8};
        t[n++] = new float[]{0, 7};
        t[n] = new float[]{0, 6};
        return t;
    }

    /** Home column cell for colour index ci and step 0..4. */
    public static float[] homeCell(int ci, int step) {
        switch (ci) {
            case 0: return new float[]{1 + step, 7};
            case 1: return new float[]{7, 1 + step};
            case 2: return new float[]{13 - step, 7};
            default: return new float[]{7, 13 - step};
        }
    }

    public static float[] yardSlot(int ci, int token) {
        float[] o = YARD_ORIGIN[ci];
        float[] s = YARD_SLOTS[token % 4];
        return new float[]{o[0] + s[0], o[1] + s[1]};
    }

    public static float[] yardOrigin(int ci) {
        return YARD_ORIGIN[ci];
    }

    /** Absolute track index of a progress value, or -1 off the shared track. */
    public static int absIndex(int ci, int progress) {
        if (progress < 0 || progress >= HOME_START) return -1;
        return (progress + ci * 13) % TRACK_LEN;
    }

    public static boolean isSafe(int index) {
        for (int s : SAFE_INDICES) if (s == index) return true;
        return false;
    }

    /** Grid cell (possibly fractional) of a token at a given progress. */
    public static float[] cellOf(int ci, int progress, int token) {
        if (progress < 0) return yardSlot(ci, token);
        if (progress < HOME_START) return TRACK[absIndex(ci, progress)];
        if (progress < FINISH) return homeCell(ci, progress - HOME_START);
        return FINISH_SPOT[ci];
    }

    /** Key that identifies a board location so co-located tokens can be grouped. */
    public static String locationKey(int ci, int progress, int token) {
        if (progress < 0) return "yard:" + ci + ":" + token;
        if (progress < HOME_START) return "t:" + absIndex(ci, progress);
        if (progress < FINISH) return "h:" + ci + ":" + progress;
        return "f:" + ci;
    }
}
