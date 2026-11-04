package com.ludox.game.ui;

/** The app's way in to the graphics settings at start-up and when memory runs short. */
public final class Graphics {
    private Graphics() {}

    public static void init() {
        Effects.apply();
    }

    /** The app has left the screen: pictures can be loaded or painted again when it returns. */
    public static void trim() {
        AvatarView.Images.trim();
        com.ludox.game.gl.GiftThumbs.trim();
    }
}
