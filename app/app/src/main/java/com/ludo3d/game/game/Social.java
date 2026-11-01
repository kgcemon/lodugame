package com.ludo3d.game.game;

import java.util.LinkedHashMap;
import java.util.Map;

/** Emotes and quick messages, the same approved lists as shared/social.ts (the server validates ids). */
public final class Social {
    private Social() {}

    public static final Map<String, String> EMOTES = new LinkedHashMap<>();
    /** Reaction style of each emote (decides its sound), as in shared/social.ts. */
    private static final Map<String, String> EFFECTS = new LinkedHashMap<>();

    static {
        String[][] e = {
                {"grin", "😀", "pop"}, {"beam", "😁", "pop"}, {"squint", "😆", "laugh"}, {"joy", "😂", "laugh"}, {"rofl", "🤣", "laugh"},
                {"blush", "😊", "pop"}, {"cool", "😎", "cool"}, {"party", "🥳", "party"},
                {"heart", "❤️", "love"}, {"sparkle_heart", "💖", "love"}, {"fire", "🔥", "fire"}, {"clap", "👏", "clap"},
                {"thumbs_up", "👍", "pop"}, {"thumbs_down", "👎", "pop"}, {"raise_hands", "🙌", "clap"}, {"hundred", "💯", "fire"},
                {"clown", "🤡", "pop"}, {"zany", "🤪", "laugh"}, {"wink_tongue", "😜", "laugh"}, {"smirk", "😏", "cool"},
                {"giggle", "🤭", "pop"}, {"see_no_evil", "🙈", "pop"},
                {"cry", "😢", "cry"}, {"sob", "😭", "cry"}, {"pleading", "🥺", "cry"}, {"broken_heart", "💔", "cry"}, {"pensive", "😔", "cry"},
                {"devil", "😈", "fire"}, {"skull", "💀", "wow"}, {"zap", "⚡", "wow"}, {"trophy", "🏆", "party"}, {"crown", "👑", "party"},
                {"open_mouth", "😮", "wow"}, {"astonished", "😲", "wow"}, {"mind_blown", "🤯", "wow"}, {"scream", "😱", "wow"}, {"flushed", "😳", "wow"},
        };
        for (String[] x : e) {
            EMOTES.put(x[0], x[1]);
            EFFECTS.put(x[0], x[2]);
        }
    }

    public static String effect(String id) {
        String s = EFFECTS.get(id);
        return s != null ? s : "pop";
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
