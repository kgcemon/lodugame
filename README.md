# Ludo 3D — online platform

A 3D Ludo game (React Three Fiber) plus a full online platform: accounts, a virtual-coin
wallet with an auditable ledger, paid matches with server-decided payouts, friends, room
invitations, leaderboards, match history, emotes/chat, and an admin dashboard.

Virtual coins have **no cash value** and cannot be bought or withdrawn.

## Architecture

```
Browser (React + Vite)  ──HTTPS JSON (/api, cookie session)──►  Node server (Express + Socket.IO)
        │                                                            │  ├─ auth, wallet, friends, admin  (server/*.ts)
        └─────────── WebSocket (/socket.io, same cookie) ───────────►│  ├─ rooms + authoritative game     (server/rooms.ts)
                                                                     │  └─ coin ledger for matches        (server/ledger.ts)
                                                                     ▼
                                                PostgreSQL or MySQL (DATABASE_URL, e.g. Supabase / CloudPanel)
                                                or PGlite (PostgreSQL in WebAssembly, ./data) when unset
```

* The browser never talks to the database. Every coin movement happens in the server inside a
  database transaction (`server/wallet.ts → applyWalletTx`), which locks the wallet row, checks an
  idempotency key, updates the balance (a `CHECK (balance >= 0)` keeps it non-negative) and writes
  the ledger row.
* Auth is implemented in the app server: scrypt password hashes, random session tokens stored only
  as SHA-256 hashes, HttpOnly `SameSite=Lax` cookies (`Secure` in production). Supabase **Auth** is
  not used; Supabase works as the **PostgreSQL** host.
* All tables have Row Level Security enabled with no policies, so Supabase's `anon`/`authenticated`
  keys can't read or change anything. The server connects as the table owner.

## Quick start (local)

Requires Node.js 22+.

```bash
npm install
cp .env.example .env        # then edit ADMIN_EMAILS to your own email
npm run dev                 # API + game server on :3001, Vite on :5173
```

Open http://localhost:5173 and create an account. Without `DATABASE_URL` the data lives in
`./data/pglite` (a real PostgreSQL engine, no install needed). Without `RESEND_API_KEY`,
verification and password-reset links are printed in the server terminal.

| Command              | What it does                                                           |
| -------------------- | ---------------------------------------------------------------------- |
| `npm run dev`        | Server (auto-restart) + Vite (hot reload)                              |
| `npm test`           | Rules engine, game server, chat/emotes and platform tests (Vitest)     |
| `npm run build`      | Type-check everything and build the site into `dist/`                  |
| `npm start`          | Production: one process serves `dist/`, `/api` and the game socket     |
| `npm run db:migrate` | Apply database migrations and exit (the server also does this on boot) |

## How a match starts

Entry fees are charged when a player takes a seat and refunded if they leave while the match is
still waiting. When the room fills (`server/rooms.ts → startGame`), one transaction
(`server/ledger.ts → start`) checks that seat records and ledger agree each fee was paid exactly
once, records `matches.prize_pool` and starts the match. If that fails, a quick room closes and
everyone is refunded. Nobody is committed while offline: a full quick room waits until they are back
or the lobby's grace period frees their seat.

The room then carries `intro` (`shared/protocol.ts → MatchIntro`) while the Match Found introduction
plays and the first turn is held: the same players, colours and numbers for everyone, and `msLeft`, so
clients stay together and a reconnect joins part-way. The match is already on at this point: leaving
is a forfeit and a lost connection has the usual 20 seconds. The app draws it in
`app/.../ui/MatchIntroScreen.java` and `gl/TreasureRenderer.java`; the coins only show what is recorded.

## Database setup

Migrations are plain SQL in `server/db/migrations/` (PostgreSQL/PGlite) and
`server/db/migrations-mysql/` (MySQL) and are applied automatically, in order, when the server
starts (tracked in `schema_migrations`). A schema change needs a file in both folders.

**PostgreSQL / Supabase:**

1. Create a Supabase project (or any PostgreSQL 14+ database).
2. Copy the connection string (Supabase: *Project Settings → Database → Connection string → URI*)
   into `.env` as `DATABASE_URL=...` — server only, never in the frontend.
3. `npm run db:migrate` (or just start the server).

**MySQL / MariaDB (CloudPanel, cPanel, XAMPP; MySQL 8.0.16+ or MariaDB 10.4.2+):**

1. Create an empty database and a user with full rights on it (CloudPanel: *Sites → your site →
   Databases → Add Database*).
2. Put it in `.env` as `DATABASE_URL=mysql://DB_USER:DB_PASSWORD@127.0.0.1:3306/DB_NAME`
   (URL-encode special characters in the password, e.g. `@` → `%40`).
3. `npm run db:migrate` (or just start the server).

Application SQL is written for PostgreSQL; `server/db/index.ts → toMysql` rewrites the few
differences (`$1` placeholders, casts, `ilike`, `on conflict`) for MySQL. MySQL has no row level
security, so keep the database user private to the server. To run the test suite on MySQL, set
`TEST_MYSQL_URL=mysql://root:PASSWORD@127.0.0.1:3306/` (a user that may create databases); each
test server gets its own temporary database.

Tables: `users`, `profiles`, `sessions`, `auth_tokens`, `wallets`, `wallet_transactions`,
`matches`, `match_players`, `match_settlements`, `match_disputes`, `friend_requests`,
`friendships`, `notifications`, `game_settings`, `audit_logs`.

**Administrators:** put your email in `ADMIN_EMAILS`; that account becomes an admin when it
registers or next signs in. Admin settings (bonuses, entry fees, payout multiplier, mode
names/limits) live in `game_settings` and are edited from **/admin → Settings**.

## Production deployment

```bash
npm ci
npm run build
NODE_ENV=production PORT=3001 APP_BASE_URL=https://your-domain.example DATABASE_URL=... npm start
```

* Serve it behind HTTPS (nginx, Caddy or a PaaS). Enable WebSocket upgrades for `/socket.io/`.
  Set `TRUST_PROXY=true` behind a proxy so rate limits see real client IPs.
* `APP_BASE_URL` is used for invitation links and emails — set it to your real public URL.
* Set `RESEND_API_KEY` and `MAIL_FROM` for real emails; `REQUIRE_EMAIL_VERIFICATION=true` to
  require a verified email before paid matches.
* Rooms in progress live in server memory: run **one** server instance. On restart, open matches
  are refunded automatically (see policy below).

## Coins, matches and policies

| Event | Coins |
| --- | --- |
| Registration | Welcome bonus (default 1,000), once per account, same transaction as the account |
| Daily bonus | Default 100, once per UTC day (`daily:<date>` idempotency key) |
| Joining a match | Official entry fee from the server's settings, charged once per seat (`entry:<match>:<attempt>`) |
| Winning | Total payout = multiplier × your fee (default 2×, fee included), once per match (`match_settlements` primary key + `reward:<match>` key) |
| Admin adjustment | Any amount with a required reason; recorded in the ledger and the audit log |

**Payout accounting:** all players in a room pay the same fee (mixed fees are impossible —
the fee belongs to the room). 2 players: pot 2E, winner gets 2E. 4 players: pot 4E, winner gets 2E,
and the remaining 2E is retained by the house and stored per match as `house_amount`. If a bot
wins, nobody is paid.

**Refund policy** (`server/ledger.ts`):

* Leaving, being removed, or timing out **before the start** → full refund.
* Room cancelled before the start (everyone left, creation failed) → full refund.
* Server restart/crash → full refund to players still in the match; players who had already
  forfeited are not refunded.
* Leaving, or staying offline past the reconnection deadline, **after the start** → a forfeit: no
  refund and no reward.
* Every player out with nobody left to win → the match is closed with no winner and no refunds.
* Losing → no refund.

**Connection loss and forfeits** (`server/rooms.ts`, decided only by the server):

* A dropped connection starts a 20-second countdown (`RECONNECT_GRACE_MS`), noticed through the
  Socket.IO heartbeat within 11 s if the connection dies silently. Everyone in the room sees it.
* Back in time (`session:hello`, on the signed-in session of the new socket) the player gets
  their seat and the server's state back. Nothing the client says reaches the match, and the turn
  clock carries on from where it stopped. A bare new socket changes nothing.
* A connection has to hold for 10 s to earn a new countdown: otherwise the next drop carries on
  with what was left, so a flapping connection can't stall a match.
* Nothing is played for an offline player. On their turn the match waits, at most until their
  deadline. At the deadline they are out, whichever arrives first of the timer and their reconnect.
* Leaving during a match (`room:leave`) is the same forfeit, at once, with no countdown.
* After a forfeit: 3 or 4 players play on, tokens untouched, turn order skipping the seat. With
  one left, they win and are paid as usual. If that last player is offline too, they win only by
  coming back in time. If only bots are left, the match ends and no coins are paid.
* 2 vs 2: a forfeit loses the match for that team, since a team needs both partners home to win.
* `match_players` records the connection status, disconnect time, deadline, reconnect time,
  forfeit time and reason. The result screen is built from that record, so it survives a restart.

Matchmaking (Quick/Classic/Pro/Master) starts a room when it is full. Otherwise, when the
configured wait (default 45 s, `matchmaking_wait_seconds`) runs out, bots take the empty seats
and the match starts. Bots never receive payouts. Friendly rooms are free, private and allow bots.

## Gems and re-rolls

Gems are a second currency, bought in the Android app through Google Play
(`server/gems.ts`, `server/googleplay.ts`):

* **Re-roll:** after rolling, a player can pay gems to roll again — 5, then 15, then 45 gems,
  at most 3 times per match. It works until they move; if the roll already passed the turn,
  the next player is held for 2.5 s so the roller can still decide. The server rolls the new
  number. Every gem change is a row in `gem_transactions`; the balance can't go negative.
* **Packs:** `gems_50`, `gems_150`, `gems_500` (consumable in-app products). Create them with
  these exact ids in Play Console → Monetize → In-app products and set the prices there.
* **Verification:** the app sends the purchase token to `POST /api/gems/purchase`; the server
  checks it with the Google Play Developer API, grants the gems once (tokens are unique and tied
  to the buyer's account) and consumes the purchase. Setup:
  1. Play Console → Setup → API access: link a Google Cloud project, create a service account,
     grant it *View financial data* and *Manage orders* for the app, download its JSON key.
  2. On the server: `GOOGLE_PLAY_SERVICE_ACCOUNT=/path/to/key.json` (keep it out of git) and
     `GOOGLE_PLAY_PACKAGE=com.ludox.game`, then restart.
  3. Purchases only work for builds installed from Play (internal testing track is enough).
* **Support:** admins can add or remove gems with `POST /api/admin/users/:id/gems`.

## Gifts and rewards

Administrators send gifts from **Send gifts** in the admin panel: coins, diamonds, a dice skin, a
board or an avatar frame, optionally in a gift box, with a message and an expiry. A send writes one
row per player (`gifts`), a notification and an audit record, and credits nothing. The player's claim
is one transaction that locks the gift, credits it (ledger key `gift:<id>`, or the inventory) and
writes `gift_claims`, whose primary key allows one claim per gift. Claiming again answers with the
first claim and moves nothing.

- **States:** `pending` → `opened` → `claimed`; or `expired` / `cancelled`, which can't be claimed.
- **Gift boxes** are a presentation over what the administrator chose: the box hides it until opened,
  and adds nothing.
- **A cosmetic already owned** isn't granted twice: the player gets 25% of its shop price instead, in
  the currency the shop sells it for (`DUPLICATE_REFUND_SHARE` in `shared/gifts.ts`).
- **Everyone** means every player who isn't suspended (administrators are left out). It needs the
  player count the administrator confirmed; a repeat of the same request skips players already served.
- **Older apps** get a plain system notice; they can't open gifts, which wait for the update.

A credit the ledger confirms is reported as a `RewardEvent` (`shared/gifts.ts`): in the reply for the
daily bonus, spin and gift claim, and as the `reward` socket event for a match win. The app animates
that event once per ledger entry, and shows the server's balance when the animation ends.

## Security summary

* Server-side validation on every endpoint; parameterised SQL everywhere (no string-built values).
* CSRF: state-changing requests need JSON, a custom `X-Requested-With` header and an allowed
  `Origin`; cookies are `SameSite=Lax`. WebSocket connections also check `Origin`.
* Rate limits on registration, login, password reset, chat, emotes, friend requests and admin actions.
* Security headers including a strict Content-Security-Policy; generic error messages (no SQL or stacks).
* Chat and names are rendered as React text (no HTML injection).
* Clients never send balances, fees, winners or sender identities — the server derives them.

## Project structure (main parts)

```
shared/       Rules engine, board geometry, AI, emote/chat rules, socket protocol
server/
  index.ts    Express + Socket.IO wiring, static hosting, crash recovery
  auth.ts     Register / login / logout / verify / reset / change password
  api.ts      Profile, wallet, history, stats, leaderboard, friends, notifications, admin
  wallet.ts   Ledger: applyWalletTx, daily bonus, transaction lists
  ledger.ts   Match money: entry, refunds, settlement, cancellation, recovery
  rooms.ts    Rooms, matchmaking, authoritative turns, chat/emotes
  settings.ts Admin-configurable game settings
  db/         PostgreSQL/MySQL/PGlite adapter, migrations (+ migrations-mysql), migrate CLI
src/
  ui/platform Auth pages, dashboard shell, play/room/friends/wallet/history/leaderboard/admin pages
  ui/         In-game HUD, chat & emote panels, practice setup
  three/      3D scene, board, tokens, dice, effects (loaded only when a match starts)
tests/        engine, server, social (chat/emotes), platform (accounts, wallet, payouts, friends, admin)
```

## Not included

* Buying coins, or withdrawing coins or gems (by design); gems are bought only through Google Play. Referral rewards.
* Supabase Auth (accounts are handled by the app server; Supabase is used as the database).
* Running several server instances at once (rooms are in memory).
* Profanity filtering / server-side chat moderation (players can mute and hide locally).
