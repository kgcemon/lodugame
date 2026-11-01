# Ludo 3D — Android app (Java)

A native Android app written in Java. It talks to the same game server as the website
(REST `/api` + Socket.IO), so accounts, coins, rooms and matches are shared between the app and
the site. The server decides everything (dice, turns, fees, payouts); the app shows it.

* **Screens:** sign in / register / forgot password, Home (daily bonus, paid matches, create or
  join a room, stats, online friends), Friends (search, requests, join a friend's room), Ranks
  (leaderboard), Profile (edit name / username / avatar colour, password, sign out), Wallet,
  Match history (with "report a problem"), Notifications (join room invites), player profiles,
  the waiting room (ready, add bot, kick, invite, share code) and the game.
* **Game:** a real 3D board drawn with OpenGL ES 2.0. Tokens hop along the track, captured
  tokens fly home, and the die tumbles. Tap the die (or ROLL) to roll, tap a glowing token to
  move it. Drag sideways to turn the board, double-tap to reset it. It also has the turn timer,
  room chat with quick messages, emotes and the result card (rematch / leave).
* The admin panel stays on the website.

## Open and build

1. Android Studio → **Open** → select this `app` folder. Let Gradle sync finish.
2. Run ▶ on an emulator or phone.
3. For a release: **Build → Generate Signed App Bundle / APK**.

Requirements: Android Studio with JDK 17+, Android SDK 36. The app runs on Android 7.0+.

## Which server the app uses

The default is in `app/build.gradle`:

```
buildConfigField 'String', 'DEFAULT_SERVER_URL', '"https://freefirediamondtopup.com"'
```

* Live site (the default): `https://freefirediamondtopup.com`.
* Emulator + `npm run dev` on this PC: `http://10.0.2.2:3001`.
* Real phone on the same Wi-Fi + `npm run dev`: `http://<your PC's IP>:3001`.

Players can also change it on the sign-in screen (**⚙ Server**). The app needs no CORS
settings on the server.

Before publishing, also:

* Set `applicationId` / `namespace` in `app/build.gradle` (now `com.ludo3d.game`; it can't
  change after release).
* Remove `android:usesCleartextTraffic="true"` from `AndroidManifest.xml` if you only use https.

## Code map

```
app/src/main/java/com/ludo3d/game/
  LudoApp.java          app-wide session: API client, socket, user, wallet, config
  net/Api.java          REST client (cookie session + x-requested-with header)
  net/CookieStore.java  keeps the login cookie across restarts
  net/GameSocket.java   Socket.IO client: room, game updates, chat, emotes, wallet, notifications
  game/                 board geometry, parsed game state, emote list (same as shared/*.ts)
  gl/                   OpenGL renderer: shader, meshes (lathe-turned pawn, dice), textures, animations
  ui/                   activities and tabs (screens are built in code with ui/Ui.java)
```
