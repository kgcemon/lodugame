package com.ludo3d.game.ui;

import android.app.Activity;
import android.content.Intent;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.game.Board;
import com.ludo3d.game.game.LocalGame;

/**
 * "Play offline": pick 2, 3 or 4 players and their names, then pass one phone around.
 * No server, no coins: the match runs on {@link LocalGame} inside GameActivity.
 */
final class OfflineSetup {
    private OfflineSetup() {}

    static final String EXTRA_NAMES = "offline_names";
    private static final String[] COLOR_NAMES = {"Red", "Green", "Yellow", "Blue"};

    static void show(Activity a) {
        LudoApp app = LudoApp.get();
        LinearLayout box = Ui.vbox(a);
        box.addView(Ui.muted(a, "Play on this phone, no internet needed. Pass it to whoever's turn it is."));
        box.addView(Ui.label(a, "Players"));
        final int[] players = {app.prefs.getInt("offline_players", 2)};
        LinearLayout countRow = Ui.hbox(a);
        TextView[] counts = new TextView[3];
        LinearLayout nameList = Ui.vbox(a);
        EditText[] inputs = new EditText[4];
        for (int i = 0; i < 4; i++) {
            inputs[i] = Ui.input(a, "Player " + (i + 1), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            inputs[i].setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
            inputs[i].setText(app.prefs.getString("offline_name_" + i, ""));
        }
        Runnable[] refresh = new Runnable[1];
        for (int n = 2; n <= 4; n++) {
            final int count = n;
            TextView b = Ui.text(a, String.valueOf(n), 16, Ui.INK);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 12));
            b.setOnClickListener(v -> {
                players[0] = count;
                refresh[0].run();
            });
            counts[n - 2] = b;
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.rightMargin = Ui.dp(a, 6);
            countRow.addView(b, lp);
        }
        box.addView(countRow);
        box.addView(Ui.label(a, "Names"));
        box.addView(nameList);
        refresh[0] = () -> {
            for (int i = 0; i < 3; i++) {
                boolean sel = i + 2 == players[0];
                Fx.bevel(counts[i], sel ? Fx.YELLOW : Fx.PURPLE_DARK, 14, 4, true);
                counts[i].setTextColor(sel ? 0xff4a2600 : Ui.INK);
            }
            nameList.removeAllViews();
            String[] colors = LocalGame.colorsForCount(players[0]);
            for (int i = 0; i < players[0]; i++) {
                int ci = Board.colorIndex(colors[i]);
                LinearLayout row = Ui.hbox(a);
                row.setGravity(Gravity.CENTER_VERTICAL);
                View dot = new View(a);
                dot.setBackground(Ui.round(Board.COLOR_ARGB[ci], Ui.dp(a, 999)));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 18));
                dlp.rightMargin = Ui.dp(a, 10);
                row.addView(dot, dlp);
                inputs[i].setHint(COLOR_NAMES[ci]);
                if (inputs[i].getParent() != null) ((LinearLayout) inputs[i].getParent()).removeView(inputs[i]);
                row.addView(inputs[i], Ui.weight(1));
                Ui.margins(row, a, 0, 0, 0, 8);
                nameList.addView(row, Ui.lpMatchWrap());
            }
        };
        refresh[0].run();
        Popup.show(a, "Play offline", box, Popup.plain("Cancel", null), Popup.ok("Start", () -> {
            int n = players[0];
            String[] colors = LocalGame.colorsForCount(n);
            String[] names = new String[n];
            android.content.SharedPreferences.Editor ed = app.prefs.edit().putInt("offline_players", n);
            for (int i = 0; i < n; i++) {
                String typed = inputs[i].getText().toString().trim();
                ed.putString("offline_name_" + i, typed);
                names[i] = typed.isEmpty() ? COLOR_NAMES[Board.colorIndex(colors[i])] : typed;
            }
            ed.apply();
            a.startActivity(new Intent(a, GameActivity.class).putExtra(EXTRA_NAMES, names));
        }));
    }
}
