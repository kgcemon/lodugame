package com.ludo3d.game.game;

import java.util.LinkedHashMap;
import java.util.Map;

/** Emotes and quick messages, the same approved lists as shared/social.ts (the server validates ids). */
public final class Social {
    private Social() {}

    public static final Map<String, String> EMOTES = new LinkedHashMap<>();

    static {
        String[][] e = {
                {"grin", "😀"}, {"beam", "😁"}, {"squint", "😆"}, {"joy", "😂"}, {"rofl", "🤣"}, {"blush", "😊"}, {"cool", "😎"}, {"party", "🥳"},
                {"heart", "❤️"}, {"sparkle_heart", "💖"}, {"fire", "🔥"}, {"clap", "👏"}, {"thumbs_up", "👍"}, {"thumbs_down", "👎"}, {"raise_hands", "🙌"}, {"hundred", "💯"},
                {"clown", "🤡"}, {"zany", "🤪"}, {"wink_tongue", "😜"}, {"smirk", "😏"}, {"giggle", "🤭"}, {"see_no_evil", "🙈"},
                {"cry", "😢"}, {"sob", "😭"}, {"pleading", "🥺"}, {"broken_heart", "💔"}, {"pensive", "😔"},
                {"devil", "😈"}, {"skull", "💀"}, {"zap", "⚡"}, {"trophy", "🏆"}, {"crown", "👑"},
                {"open_mouth", "😮"}, {"astonished", "😲"}, {"mind_blown", "🤯"}, {"scream", "😱"}, {"flushed", "😳"},
        };
        for (String[] x : e) EMOTES.put(x[0], x[1]);
    }

    /** One-tap reactions shown next to the gameplay controls. */
    public static final String[] INSTANT_REACTIONS = {"joy", "heart", "fire", "clap", "sob", "cool"};

    public static final String[] QUICK_MESSAGES = {
            "Good luck! 🍀", "Good game! 🎮", "Nice move! 👏", "Well played! 🏆", "Thank you! ❤️", "Oops! 😅",
            "Oh no! 😭", "Wow! 😮", "Your turn! 🎲", "Let's go! 🔥", "Haha! 😂", "GG! 🤝",
    };

    public static final int CHAT_MAX_CHARS = 200;

    public static String emoji(String id) {
        String s = EMOTES.get(id);
        return s != null ? s : "🙂";
    }
}
