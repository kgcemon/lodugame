package com.ludox.game.ui;

import org.json.JSONObject;

/** Human-readable text for server notifications (same wording as the website). */
final class Notifications {
    private Notifications() {}

    static String describe(String type, JSONObject p) {
        switch (type) {
            case "friend_request":
                return p.optString("from") + " sent you a friend request";
            case "friend_accepted":
                return p.optString("by") + " accepted your friend request";
            case "room_invite":
                return p.optString("from") + " invited you to a " + p.optString("modeName") + " room (" + p.optString("code") + ")";
            case "match_result":
                if (p.optBoolean("won")) {
                    long payout = p.optLong("payout");
                    return payout > 0 ? "You won a match! +" + Ui.fmt(payout) + " coins" : "You won a match!";
                }
                return "Match finished" + (p.optInt("rank") > 0 ? " — rank #" + p.optInt("rank") : "");
            case "wallet":
                return "Your balance was adjusted by " + p.optLong("amount") + " coins" + (p.has("reason") ? ": " + p.optString("reason") : "");
            case "system":
                // A gift says more than the line kept for apps that can't open one.
                return p.has("giftId") ? p.optString("title", "🎁 A gift from LudoX") : p.optString("message", "System message");
            default:
                return null;
        }
    }
}
