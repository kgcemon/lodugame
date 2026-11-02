package com.ludo3d.game.ui;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ludo3d.game.LudoApp;
import com.ludo3d.game.audio.Sfx;
import com.ludo3d.game.game.Board;
import com.ludo3d.game.net.Api;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Public player profiles during a match: cached lookups (for the level badge on the
 * cards) and the 3D profile popup opened by tapping an avatar. Only public data from
 * GET /players/:id is shown; coins and gems appear on my own card only.
 */
final class PlayerProfile {
    private PlayerProfile() {
    }

    /** Public profiles fetched this session, by public id. */
    private static final Map<String, JSONObject> CACHE = new HashMap<>();
    private static final Map<String, Long> FETCHED_AT = new HashMap<>();
    private static final java.util.Set<String> LOADING = new java.util.HashSet<>();

    static JSONObject cached(String publicId) {
        return publicId == null ? null : CACHE.get(publicId);
    }

    /** Fetch a profile (at most once a minute) and run `done` on the main thread when it arrives. */
    static void load(BaseActivity a, String publicId, boolean force, Runnable done) {
        if (publicId == null || publicId.isEmpty() || LOADING.contains(publicId)) return;
        Long at = FETCHED_AT.get(publicId);
        if (!force && at != null && System.currentTimeMillis() - at < 60_000) return;
        LOADING.add(publicId);
        a.app.api.get("/players/" + android.net.Uri.encode(publicId), new Api.Cb(a) {
            @Override
            public void ok(JSONObject r) {
                LOADING.remove(publicId);
                JSONObject p = r.optJSONObject("player");
                if (p == null) return;
                CACHE.put(publicId, p);
                FETCHED_AT.put(publicId, System.currentTimeMillis());
                if (done != null) done.run();
            }

            @Override
            public void err(Api.Error e) {
                LOADING.remove(publicId);
                if (done != null) done.run();
            }
        });
    }

    // ---------------------------------------------------------------- level & badges

    private static long xp(JSONObject stats) {
        return stats.optLong("matches") * 20 + stats.optLong("wins") * 50;
    }

    /** Level from public stats: grows with matches played and, faster, with wins. */
    static int level(JSONObject stats) {
        return (int) Math.floor(Math.sqrt(xp(stats) / 40.0)) + 1;
    }

    /** Progress (0..1) from the current level to the next. */
    static float levelProgress(JSONObject stats) {
        int l = level(stats);
        double from = 40.0 * (l - 1) * (l - 1), to = 40.0 * l * l;
        return (float) Math.max(0, Math.min(1, (xp(stats) - from) / (to - from)));
    }

    /** Achievement badges earned from public stats: {emoji, label}. */
    static List<String[]> badges(JSONObject s) {
        List<String[]> out = new ArrayList<>();
        long matches = s.optLong("matches"), wins = s.optLong("wins");
        if (wins >= 1) out.add(new String[]{"🥇", "First win"});
        if (wins >= 25) out.add(new String[]{"🏆", "Champion"});
        if (matches >= 50) out.add(new String[]{"🎖️", "Veteran"});
        if (matches >= 10 && s.optDouble("winRate") >= 50) out.add(new String[]{"🎯", "Sharpshooter"});
        if (matches > 0 && s.optInt("rank") > 0 && s.optInt("rank") <= 10) out.add(new String[]{"👑", "Top 10"});
        if (s.optLong("seasonPoints") >= 100) out.add(new String[]{"⚡", "Season star"});
        return out;
    }

    // ---------------------------------------------------------------- popup

    /**
     * Open the profile card: a framed purple card with a red close button on its corner, the
     * player's header (avatar, name, ID, add-friend), a big die, a two-column info panel and
     * a STATS section. `seat` is the room seat (may be null); bots get a card without stats.
     */
    static void show(BaseActivity a, String name, String colorName, JSONObject seat, boolean bot, boolean mine, boolean online) {
        int col = Board.colorArgb(colorName);
        String publicId = seat != null && !seat.isNull("publicId") ? seat.optString("publicId", null) : null;
        if (mine && (publicId == null || publicId.isEmpty()) && a.app.user != null) publicId = a.app.user.optString("publicId", null);
        final String pid = publicId;
        // Where we stand with this player (friends / request sent / ...), filled once the profile loads.
        final JSONObject[] friendship = {null};
        final Runnable[] showFriendship = {() -> {}};

        android.app.Dialog dialog = new android.app.Dialog(a);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        // The card, with room above and to the right for the close button.
        FrameLayout holder = new FrameLayout(a);
        holder.setClipChildren(false);
        holder.setClipToPadding(false);
        LinearLayout card = Ui.vbox(a);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xff8e3bd6, 0xff6a24b8, 0xff4a1690});
        bg.setCornerRadius(Ui.dp(a, 22));
        bg.setStroke(Ui.dp(a, 3), Ui.GOLD);
        card.setBackground(bg);
        card.setElevation(Ui.dp(a, 16));
        int pad = Ui.dp(a, 14);
        card.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(a, 14);
        clp.rightMargin = Ui.dp(a, 10);
        holder.addView(card, clp);

        TextView close = Ui.bold(Ui.text(a, "✕", 18, Color.WHITE));
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        GradientDrawable cg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xffff6b6b, 0xffd61f2c});
        cg.setShape(GradientDrawable.OVAL);
        cg.setStroke(Ui.dp(a, 3), Color.WHITE);
        close.setBackground(cg);
        close.setElevation(Ui.dp(a, 20));
        close.setContentDescription("Close");
        close.setOnClickListener(v -> dialog.dismiss());
        holder.addView(close, new FrameLayout.LayoutParams(Ui.dp(a, 38), Ui.dp(a, 38), Gravity.TOP | Gravity.END));

        // ---- header: avatar, name + ID, add friend
        LinearLayout head = Ui.hbox(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackground(Ui.round(0x33000000, Ui.dp(a, 16)));
        head.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 10), Ui.dp(a, 8));
        FrameLayout avf = new FrameLayout(a);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(col);
        ring.setStroke(Ui.dp(a, 3), Ui.GOLD);
        avf.setBackground(ring);
        int av = 58;
        TextView avatar = bot ? Ui.avatarColor(a, "🤖", Fx.blend(col, Color.BLACK, 0.2f), av - 8)
                : seat != null ? Ui.avatar(a, seat, name, av - 8) : Ui.avatarColor(a, name, col, av - 8);
        avf.addView(avatar, new FrameLayout.LayoutParams(Ui.dp(a, av - 8), Ui.dp(a, av - 8), Gravity.CENTER));
        View dot = new View(a);
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(online ? Ui.SUCCESS : 0xff8a8fb0);
        dg.setStroke(Ui.dp(a, 2), 0xff2e1065);
        dot.setBackground(dg);
        avf.addView(dot, new FrameLayout.LayoutParams(Ui.dp(a, 14), Ui.dp(a, 14), Gravity.BOTTOM | Gravity.END));
        head.addView(avf, new LinearLayout.LayoutParams(Ui.dp(a, av), Ui.dp(a, av)));
        LinearLayout who = Ui.vbox(a);
        who.setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 6), 0);
        TextView nm = Ui.bold(Ui.text(a, (seat != null && !bot ? Flags.of(seat) + " " : "") + name, 17, Color.WHITE));
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        who.addView(nm);
        TextView sub = Ui.bold(Ui.text(a, (online ? "● Online" : "● Offline") + "  ·  " + cap(colorName) + (mine ? "  ·  You" : ""), 12,
                online ? 0xff8ef0b4 : Ui.MUTED));
        sub.setSingleLine(true);
        who.addView(sub);
        head.addView(who, Ui.weight(1));
        TextView add = null;
        if (!bot && !mine && pid != null) {
            add = Ui.bold(Ui.text(a, "＋👤", 15, Color.WHITE));
            add.setGravity(Gravity.CENTER);
            int depth = Fx.bevel(add, Fx.GREEN, 10, 3, true);
            add.setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 8), depth);
            add.setContentDescription("Add friend");
            head.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(a, 38)));
        }
        final TextView quick = add;
        card.addView(head, Ui.lpMatchWrap());

        // ---- a big die in a tile, labelled with the player's level (or BOT)
        LinearLayout hero = Ui.vbox(a);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        View die = new View(a) {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Path path = new android.graphics.Path();

            @Override
            protected void onDraw(android.graphics.Canvas c) {
                float w = getWidth();
                paint.setShader(null);
                paint.setColor(0x40000000);
                c.drawRoundRect(0, 0, w, getHeight(), w * 0.22f, w * 0.22f, paint);
                float d = w * 0.62f;
                DiceView.drawDie(c, paint, path, (w - d) / 2f, (getHeight() - d) / 2f, d, 6, true);
            }
        };
        die.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        hero.addView(die, new LinearLayout.LayoutParams(Ui.dp(a, 78), Ui.dp(a, 78)));
        TextView heroLabel = Ui.bold(Ui.text(a, bot ? "BOT" : "LEVEL –", 15, Color.WHITE));
        heroLabel.setShadowLayer(4, 0, 1, 0x88000000);
        heroLabel.setGravity(Gravity.CENTER);
        hero.addView(heroLabel, Ui.lpMatchWrap());
        Ui.margins(hero, a, 0, 12, 0, 10);
        card.addView(hero, Ui.lpMatchWrap());
        // A slow wobble so the die feels alive.
        die.animate().rotation(-8).setDuration(0).start();
        android.animation.ObjectAnimator wob = android.animation.ObjectAnimator.ofFloat(die, "rotation", -8f, 8f);
        wob.setDuration(1400);
        wob.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        wob.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        wob.start();
        dialog.setOnDismissListener(d -> wob.cancel());

        // ---- info panel
        LinearLayout panel = Ui.vbox(a);
        panel.setBackground(Ui.round(0x38000000, Ui.dp(a, 16)));
        panel.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 12));
        card.addView(panel, Ui.lpMatchWrap());

        if (bot) {
            panel.addView(note(a, "Bots fill empty seats and play automatically. They have no public stats."));
        } else if (pid == null) {
            panel.addView(note(a, "Stats are kept for online matches only."));
        } else {
            JSONObject p = cached(pid);
            if (p != null) fill(a, panel, heroLabel, p, pid);
            else panel.addView(Ui.spinner(a));
            load(a, pid, true, () -> {
                JSONObject fresh = cached(pid);
                panel.removeAllViews();
                if (fresh != null) fill(a, panel, heroLabel, fresh, pid);
                else panel.addView(note(a, "Couldn't load the profile right now."));
                if (fresh != null && fresh.optJSONObject("friendship") != null) {
                    friendship[0] = fresh.optJSONObject("friendship");
                    showFriendship[0].run();
                }
            });
        }

        // ---- footer: my wallet, or add friend
        if (mine) {
            LinearLayout wallet = Ui.hbox(a);
            wallet.setGravity(Gravity.CENTER);
            wallet.setBackground(Ui.roundStroke(0x33000000, Ui.dp(a, 14), Ui.dp(a, 1), 0x55ffb547));
            wallet.setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8));
            wallet.addView(Ui.bold(Ui.text(a, "🪙 " + Ui.fmt(a.app.balance()), 16, Ui.GOLD)));
            wallet.addView(Ui.gap(a, 18));
            wallet.addView(Ui.bold(Ui.text(a, "💎 " + Ui.fmt(a.app.wallet.optLong("gems")), 16, 0xff7fd6ff)));
            Ui.margins(wallet, a, 0, 12, 0, 0);
            card.addView(wallet, Ui.lpMatchWrap());
        } else if (!bot && pid != null) {
            // Add friend → (sent) Cancel request; Accept when they asked first; nothing once friends.
            FrameLayout footer = new FrameLayout(a);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 48));
            blp.topMargin = Ui.dp(a, 12);
            card.addView(footer, blp);
            final String shownName = name;
            showFriendship[0] = () -> {
                String st = friendState(friendship[0]);
                footer.removeAllViews();
                int[] colors = "outgoing".equals(st) ? Fx.RED : "friend".equals(st) || "limit".equals(st) ? Fx.PURPLE_DARK : Fx.GREEN;
                int outline = "outgoing".equals(st) ? 0xff8a0d16 : "friend".equals(st) || "limit".equals(st) ? 0xff26104f : 0xff1f6d0a;
                String label = "outgoing".equals(st) ? "✖ CANCEL REQUEST" : "incoming".equals(st) ? "✓ ACCEPT REQUEST"
                        : "friend".equals(st) ? "✓ FRIENDS" : "limit".equals(st) ? "REQUEST LIMIT REACHED" : "＋ ADD FRIEND";
                Fx.Tile btn = new Fx.Tile(a, colors, 16, 5);
                Fx.GoldText l = Fx.GoldText.label(a, label, 15, outline);
                l.setGravity(Gravity.CENTER);
                btn.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
                View.OnClickListener act = v -> friendAction(a, pid, shownName, seat, friendship, showFriendship[0]);
                btn.setOnClickListener(act);
                footer.addView(btn, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                // A little flip as the button changes.
                btn.setRotationX(-70);
                btn.animate().rotationX(0).setDuration(320).setInterpolator(new OvershootInterpolator(1.6f)).start();
                if (quick != null) {
                    quick.setText("outgoing".equals(st) ? "✖" : "friend".equals(st) ? "✓" : "limit".equals(st) ? "⛔" : "＋👤");
                    int d = Fx.bevel(quick, colors, 10, 3, true);
                    quick.setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 8), d);
                    quick.setOnClickListener(act);
                }
            };
            JSONObject known = cached(pid);
            if (known != null) friendship[0] = known.optJSONObject("friendship");
            showFriendship[0].run();
        }

        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
        sv.addView(holder);
        dialog.setContentView(sv);
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.65f);
            int screen = a.getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(screen - Ui.dp(a, 24), Ui.dp(a, 400)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        Sfx.get().play(Sfx.S.CLICK, 0.6f, 1.2f);
        // Pop in with a bounce and a flip, then lean with the finger.
        holder.setScaleX(0.7f);
        holder.setScaleY(0.7f);
        holder.setAlpha(0f);
        holder.animate().scaleX(1).scaleY(1).alpha(1).setDuration(300).setInterpolator(new OvershootInterpolator(1.5f)).start();
        tilt(card);
    }

    /** none / outgoing / incoming / friend / limit (no requests left to send). */
    private static String friendState(JSONObject f) {
        if (f == null) return "none";
        if (f.optBoolean("isFriend")) return "friend";
        String pending = f.isNull("pending") ? "" : f.optString("pending");
        if ("outgoing".equals(pending)) return "outgoing";
        if ("incoming".equals(pending)) return "incoming";
        return f.optInt("sendsLeft", 1) <= 0 ? "limit" : "none";
    }

    /** What the friend button does in its current state; updates the state and redraws. */
    private static void friendAction(BaseActivity a, String pid, String name, JSONObject seat, JSONObject[] f, Runnable redraw) {
        String st = friendState(f[0]);
        JSONObject who = cached(pid) != null ? cached(pid) : seat != null ? seat : PlayFlow.obj("username", name);
        switch (st) {
            case "friend":
                LudoApp.toast("You're already friends");
                return;
            case "limit":
                LudoApp.toast("You can send a friend request only twice");
                return;
            case "outgoing":
                a.app.api.post("/friends/requests/" + f[0].optString("requestId") + "/cancel", null, new Api.Cb(a) {
                    @Override
                    public void ok(JSONObject r) {
                        f[0] = r.optJSONObject("friendship");
                        LudoApp.toast("Friend request cancelled");
                        redraw.run();
                    }
                });
                return;
            case "incoming":
                a.app.api.post("/friends/requests/" + f[0].optString("requestId") + "/accept", null, new Api.Cb(a) {
                    @Override
                    public void ok(JSONObject r) {
                        f[0] = PlayFlow.obj("isFriend", true);
                        redraw.run();
                        FriendPopups.celebrate(a, who);
                    }
                });
                return;
            default:
                a.app.api.post("/friends/requests", PlayFlow.obj("player", pid), new Api.Cb(a) {
                    @Override
                    public void ok(JSONObject r) {
                        if (r.optBoolean("accepted")) {
                            f[0] = PlayFlow.obj("isFriend", true);
                            FriendPopups.celebrate(a, who);
                        } else {
                            JSONObject out = new JSONObject();
                            try {
                                out.put("pending", "outgoing").put("requestId", r.optString("requestId")).put("sendsLeft", r.optInt("sendsLeft"));
                            } catch (Exception ignored) {
                            }
                            f[0] = out;
                            Sfx.get().play(Sfx.S.MESSAGE, 0.8f, 1.2f);
                            LudoApp.toast("Friend request sent 💌");
                        }
                        redraw.run();
                    }

                    @Override
                    public void err(Api.Error e) {
                        if ("FRIEND_REQUEST_LIMIT".equals(e.code)) {
                            f[0] = PlayFlow.obj("sendsLeft", 0);
                            redraw.run();
                        }
                        LudoApp.toast(e.message);
                    }
                });
        }
    }

    /** Info fields, the STATS section and badges once the profile is here. */
    private static void fill(BaseActivity a, LinearLayout panel, TextView heroLabel, JSONObject p, String pid) {
        JSONObject s = p.optJSONObject("stats");
        if (s == null) return;
        int level = level(s);
        heroLabel.setText("LEVEL " + level);
        long matches = s.optLong("matches"), wins = s.optLong("wins");

        panel.addView(fieldRow(a, "Level", String.valueOf(level), "Rank", matches > 0 ? "#" + s.optInt("rank") : "–"));
        panel.addView(fieldRow(a, "Player ID", pid, "Season points", Ui.fmt(s.optLong("seasonPoints"))));

        // Level progress bar.
        FrameLayout bar = new FrameLayout(a);
        bar.setBackground(Ui.round(0x40000000, Ui.dp(a, 4)));
        View fillBar = new View(a);
        fillBar.setBackground(Ui.round(Ui.GOLD, Ui.dp(a, 4)));
        fillBar.setPivotX(0);
        fillBar.setScaleX(0f);
        bar.addView(fillBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 6));
        blp.topMargin = Ui.dp(a, 8);
        panel.addView(bar, blp);
        fillBar.animate().scaleX(levelProgress(s)).setStartDelay(250).setDuration(700).start();
        TextView next = Ui.text(a, "Next level: Lv " + (level + 1), 11, Ui.MUTED);
        next.setGravity(Gravity.END);
        panel.addView(next, Ui.lpMatchWrap());

        // STATS divider.
        LinearLayout div = Ui.hbox(a);
        div.setGravity(Gravity.CENTER_VERTICAL);
        View l1 = new View(a);
        l1.setBackground(Ui.round(0x55ffffff, 1));
        div.addView(l1, new LinearLayout.LayoutParams(0, Ui.dp(a, 2), 1));
        TextView st = Ui.bold(Ui.text(a, "STATS", 12, Color.WHITE));
        st.setPadding(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 4));
        st.setBackground(Ui.roundStroke(0xff5b1fa6, Ui.dp(a, 999), Ui.dp(a, 1), 0x88ffffff));
        div.addView(st);
        View l2 = new View(a);
        l2.setBackground(Ui.round(0x55ffffff, 1));
        div.addView(l2, new LinearLayout.LayoutParams(0, Ui.dp(a, 2), 1));
        Ui.margins(div, a, 0, 10, 0, 2);
        panel.addView(div, Ui.lpMatchWrap());

        panel.addView(fieldRow(a, "Games won", Ui.fmt(wins) + " of " + Ui.fmt(matches), "Win rate", trim(s.optDouble("winRate")) + "%"));
        panel.addView(fieldRow(a, "Losses", Ui.fmt(s.optLong("losses")), "Joined", Ui.date(p.optString("joinedAt"))));

        List<String[]> badges = badges(s);
        if (!badges.isEmpty()) {
            HorizontalScrollView hs = new HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout br = Ui.hbox(a);
            for (String[] b : badges) {
                TextView t = Ui.text(a, b[0] + " " + b[1], 12, Ui.INK);
                t.setPadding(Ui.dp(a, 10), Ui.dp(a, 5), Ui.dp(a, 10), Ui.dp(a, 5));
                t.setBackground(Ui.roundStroke(0x26ffffff, Ui.dp(a, 999), Ui.dp(a, 1), 0x55ffd66b));
                br.addView(t);
                br.addView(Ui.gap(a, 6));
            }
            hs.addView(br);
            Ui.margins(hs, a, 0, 10, 0, 0);
            panel.addView(hs);
        }

        // Fields slide in one after another.
        for (int i = 0; i < panel.getChildCount(); i++) {
            View v = panel.getChildAt(i);
            v.setAlpha(0f);
            v.setTranslationY(Ui.dp(a, 8));
            v.animate().alpha(1).translationY(0).setStartDelay(80 + i * 50L).setDuration(240).start();
        }
    }

    /** Two labelled values side by side, each value in a dark pill. */
    private static LinearLayout fieldRow(android.content.Context c, String k1, String v1, String k2, String v2) {
        LinearLayout row = Ui.hbox(c);
        row.addView(field(c, k1, v1), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = Ui.dp(c, 10);
        row.addView(field(c, k2, v2), lp);
        LinearLayout.LayoutParams rlp = Ui.lpMatchWrap();
        rlp.topMargin = Ui.dp(c, 6);
        row.setLayoutParams(rlp);
        return row;
    }

    private static LinearLayout field(android.content.Context c, String k, String v) {
        LinearLayout f = Ui.vbox(c);
        f.addView(Ui.bold(Ui.text(c, k, 11, 0xffe9d8ff)));
        TextView val = Ui.bold(Ui.text(c, v, 15, Color.WHITE));
        val.setSingleLine(true);
        val.setEllipsize(TextUtils.TruncateAt.END);
        val.setPadding(Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4));
        val.setBackground(Ui.round(0x40000000, Ui.dp(c, 8)));
        LinearLayout.LayoutParams lp = Ui.lpMatchWrap();
        lp.topMargin = Ui.dp(c, 2);
        f.addView(val, lp);
        return f;
    }

    /** The card leans toward the finger and springs back; it also flips in on open. */
    @SuppressLint("ClickableViewAccessibility")
    private static void tilt(View v) {
        v.setCameraDistance(9000 * v.getResources().getDisplayMetrics().density);
        v.setRotationY(-28f);
        v.animate().rotationY(0).setDuration(420).setInterpolator(new OvershootInterpolator(1.6f)).start();
        v.setOnTouchListener((view, e) -> {
            float w = Math.max(1, view.getWidth()), h = Math.max(1, view.getHeight());
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    view.animate().cancel();
                    view.setRotationY((e.getX() / w - 0.5f) * 14f);
                    view.setRotationX(-(e.getY() / h - 0.5f) * 10f);
                    return true;
                default:
                    view.animate().rotationX(0).rotationY(0).setDuration(380).setInterpolator(new OvershootInterpolator(2f)).start();
                    return true;
            }
        });
    }

    static TextView levelBadge(android.content.Context c, String text, float sp) {
        TextView t = Ui.bold(Ui.text(c, text, sp, 0xff3b1d00));
        t.setPadding(Ui.dp(c, 6), Ui.dp(c, 1), Ui.dp(c, 6), Ui.dp(c, 1));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xffffe27a, 0xfff5b400});
        g.setCornerRadius(Ui.dp(c, 999));
        g.setStroke(Ui.dp(c, 1), 0xffa66f00);
        t.setBackground(g);
        t.setSingleLine(true);
        return t;
    }

    private static TextView chip(android.content.Context c, String text, int color) {
        TextView t = Ui.bold(Ui.text(c, text, 12, Ui.INK));
        t.setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 4));
        t.setBackground(Ui.roundStroke((color & 0x00ffffff) | 0x33000000, Ui.dp(c, 999), Ui.dp(c, 1), color));
        return t;
    }

    private static TextView note(android.content.Context c, String text) {
        TextView t = Ui.text(c, text, 13, Ui.MUTED);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 4));
        return t;
    }

    private static String cap(String s) {
        return TextUtils.isEmpty(s) ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(java.util.Locale.US, "%.1f", d);
    }
}
