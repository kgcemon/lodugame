package com.ludox.game.ui;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import com.ludox.game.LudoApp;
import com.ludox.game.net.Api;

import org.json.JSONObject;

/**
 * Reporting and blocking a player, from their card in a match or their profile page. The server
 * does the filtering: a blocked player's chat and emotes never reach this phone. A block doesn't
 * change anything about a match: same seats, same turns, same rules.
 */
final class Moderation {
    private Moderation() {
    }

    interface Changed {
        void blocked(boolean nowBlocked);
    }

    /** What can be reported: server value, label. The server has the same list (shared/moderation.ts). */
    private static final String[][] REASONS = {
            {"harassment", "Harassment or bullying"},
            {"hate", "Hate speech"},
            {"sexual", "Sexual content"},
            {"spam", "Spam or scams"},
            {"cheating", "Cheating"},
            {"other", "Something else"},
    };
    private static final int MAX_NOTE = 500;

    /** Fill a row with the Report and Block (or Unblock) buttons. */
    static void fill(BaseActivity a, LinearLayout row, String publicId, String name, boolean blocked, Changed changed) {
        row.removeAllViews();
        row.addView(Ui.smallButton(a, "🚩 Report", "glass", v -> report(a, publicId, name, blocked, changed)), Ui.weight(1));
        row.addView(Ui.gap(a, 10));
        row.addView(blocked
                ? Ui.smallButton(a, "Unblock", "glass", v -> unblock(a, publicId, name, changed))
                : Ui.smallButton(a, "🚫 Block", "danger", v -> confirmBlock(a, publicId, name, changed)), Ui.weight(1));
    }

    private static void confirmBlock(BaseActivity a, String publicId, String name, Changed changed) {
        Popup.message(a, "Block " + name + "?",
                "You won't see their chat or emotes. Neither of you can send the other friend requests, invites or gifts, and any friendship between you ends. "
                        + "You can still end up in the same match, and it plays the same. You can unblock them in Profile.",
                Popup.plain("Cancel", null),
                Popup.danger("Block", () -> block(a, publicId, name, changed)));
    }

    private static void block(BaseActivity a, String publicId, String name, Changed changed) {
        a.app.api.post("/blocks", PlayFlow.obj("player", publicId), new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                LudoApp.toast(name + " is blocked");
                changed.blocked(true);
            }
        });
    }

    private static void unblock(BaseActivity a, String publicId, String name, Changed changed) {
        a.app.api.delete("/blocks/" + Uri.encode(publicId), new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                LudoApp.toast(name + " is unblocked");
                changed.blocked(false);
            }
        });
    }

    private static void report(BaseActivity a, String publicId, String name, boolean blocked, Changed changed) {
        LinearLayout box = Ui.vbox(a);
        box.addView(Ui.muted(a, "An administrator will review this. " + name + " won't be told who reported them."));
        RadioGroup reasons = new RadioGroup(a);
        for (int i = 0; i < REASONS.length; i++) {
            RadioButton b = new RadioButton(a);
            b.setId(i + 1);
            b.setText(REASONS[i][1]);
            b.setTextColor(Color.WHITE);
            b.setButtonTintList(ColorStateList.valueOf(Ui.GOLD));
            reasons.addView(b);
        }
        Ui.margins(reasons, a, 0, 8, 0, 8);
        box.addView(reasons);
        EditText note = Ui.input(a, "Add details (optional)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.setMinLines(2);
        note.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(MAX_NOTE)});
        box.addView(note);

        final Popup[] popup = {null};
        popup[0] = Popup.show(a, "Report " + name, box,
                Popup.plain("Cancel", null),
                Popup.ok("Send report", () -> {
                    int picked = reasons.getCheckedRadioButtonId();
                    if (picked < 1) {
                        LudoApp.toast("Choose a reason");
                        return;
                    }
                    JSONObject body = PlayFlow.obj("player", publicId);
                    try {
                        body.put("reason", REASONS[picked - 1][0]);
                        body.put("note", note.getText().toString().trim());
                    } catch (Exception ignored) {
                        // not reachable
                    }
                    a.app.api.post("/reports", body, new Api.Cb(a) {
                        @Override
                        public void ok(JSONObject r) {
                            popup[0].dismiss();
                            sent(a, publicId, name, blocked, changed);
                        }

                        @Override
                        public void err(Api.Error e) {
                            LudoApp.toast(e.message);
                            if ("ALREADY_REPORTED".equals(e.code)) popup[0].dismiss();
                        }
                    });
                }).keepOpen());
    }

    /** The report is in. Offer the block too, since that is what stops the messages right away. */
    private static void sent(BaseActivity a, String publicId, String name, boolean blocked, Changed changed) {
        if (blocked) {
            Popup.message(a, "Report sent", "Thanks. An administrator will review it.");
            return;
        }
        Popup.message(a, "Report sent", "Thanks. An administrator will review it. Do you also want to block " + name + "?",
                Popup.plain("Not now", null),
                Popup.danger("Block", () -> block(a, publicId, name, changed)));
    }
}
