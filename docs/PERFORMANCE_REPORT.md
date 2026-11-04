# LudoX performance and hardening report

Measured on 4 November 2026. Reproduce the load figures with `scripts/loadtest.ts`.

## What LudoX actually is

The brief described a stack that differs from this repository, so the work targets what exists:

| Brief | Actual |
| --- | --- |
| Next.js / React game client | Native Android app (Java). React (Vite) is only the admin panel |
| Three.js | OpenGL ES 2.0, drawn on demand |
| WebView or PWA | Native activities; no JavaScript bridge, no service worker |
| LiveKit Cloud voice | Friends-only AAC voice relayed through the game server over Socket.IO |
| 2-core VPS | 4 vCPU, 4.8 GB RAM, shared with another site's Next.js server |
| iPhone | No iOS client exists |

## Load test

A real server against MariaDB, both pinned to two CPU cores on a Windows development PC, with the
load generator on other cores. Each simulated player matches, rolls, moves, chats, sends emotes and
gifts, browses wallet and history, and drops and reconnects; a fifth are friend pairs sending voice.

Percent CPU is of one core (200% would be two cores flat out). Round trips exclude internet latency.

### Baseline, small database

| Players | CPU avg / max | RSS | Dice p99 | Matchmaking p99 | Timeouts | Downstream |
| --- | --- | --- | --- | --- | --- | --- |
| 10 | 1% / 3% | 93 MB | 3.2 ms | 13 ms | 0 | 3 kB/s |
| 25 | 2% / 4% | 94 MB | 3.7 ms | 16 ms | 0 | 14 kB/s |
| 50 | 2% / 5% | 122 MB | 2.7 ms | 12 ms | 1 | 36 kB/s |
| 100 | 3% / 8% | 125 MB | 3.5 ms | 19 ms | 0 | 70 kB/s |

The live game was never the constraint. A `game:update` is about 700 bytes, and all traffic for 100
players is 70 kB/s. Delta sync, batching, compression, Redis and clustering are therefore **not
justified** and were not added. Matches live in one process's memory, so a second worker would also
split them.

### Large database (20,000 players, 400,000 match records)

| Query | Before | After |
| --- | --- | --- |
| Open a profile | 2,960 ms each | 26 ms, plus a shared cached table |
| Start a match (ranks) | 2,899 ms each | under 30 ms, plus the same table |
| Leaderboard page | 2,922 ms each | one read shared by everyone, then about 4 ms |

Before the fix, 300 players starved the database pool: matchmaking p99 was 66,712 ms, with 481 ack
timeouts. After it:

| Players | CPU avg / max | RSS | Dice p99 | Matchmaking p99 | Gift p99 | Timeouts |
| --- | --- | --- | --- | --- | --- | --- |
| 100 | 8% / 11% | 121 MB | 3.6 ms | 25 ms | 31 ms | 0 |
| 300 | 19% / 23% | 136 MB | 5.6 ms | 63 ms | 70 ms | 0 |

The first leaderboard read after a start still takes several seconds at this size (p99 8.5 s in the
100-player run); later reads come from memory.

**Conclusion:** on this evidence 100 concurrent players fit comfortably, with headroom to at least
300. No upgrade is indicated. This was not measured on the VPS itself, whose MariaDB, CPU and network
differ, and its other site shares the same machine.

## Bottlenecks fixed

| # | Problem | Fix |
| --- | --- | --- |
| 1 | Every profile view and match start scanned all match records | One player's own rows (indexed), rank from a cached table of win counts |
| 2 | Leaderboard reads stampeded and held all 10 database connections | One shared read per page; slow tables refresh behind the page held, at about 3% of database time |
| 3 | Friend-online alert ran 2 queries per online friend | One query |
| 4 | Home screen redrew non-stop for two looping badges | Short nudge, then a 10 s rest, only while visible: about 59% to 18% of a core on the emulator |
| 5 | Socket stayed open in the background with nothing to do | Closed after 45 s when not in a match; reopens on return |
| 6 | No SIGTERM handling: a restart could cut off a settlement | Match writes finish first, with a 7 s limit |
| 7 | Expired sessions and tokens were kept for ever | Daily cleanup, only rows expired over 30 days; nothing a player sees, nothing in the ledgers |
| 8 | PM2 ran `npm start`: an idle extra process, no restart limits | `ecosystem.config.cjs`: direct node, backoff, memory limit, kill timeout |
| 9 | No monitoring | `/admin/health`, `/healthz/db`, log warnings on event-loop lag or high memory |
| 10 | No scheduled backups or log rotation | `scripts/backup.sh` (nightly, verified, keeps 14) and pm2-logrotate |

## Android

| State (emulator, API 36) | App CPU |
| --- | --- |
| Home, idle | about 18% of a core, was about 59% |
| In a match, between animations | about 3% |
| Background during a match | about 0% |

Added: Auto, Low, Medium, High and Ultra graphics (edge smoothing, effects, 30 fps cap on Low; the game
is identical on all); a Connected, Weak Connection, Reconnecting, Connection Lost indicator with a
small probe only during matches; cosmetics step down when the connection is weak; image caches released when
the app is hidden. The board was already drawn on demand and pauses with the screen.

**Not measured:** a real phone, battery drain, frames per second on low-end hardware, or a real weak
network. The emulator's delay setting appeared to have no effect, so the Weak Connection state itself
has not been seen on screen. Emulator CPU is only a comparison, not what a phone will do.

## Security review

Code reviewed, no critical issue found: parameterised SQL, ownership checks on routes, scrypt
passwords, HttpOnly SameSite session cookies with Secure in production, CSRF guard, per-IP and per-user
rate limits, request size limits, CSP and HSTS, server-decided dice, fees, prizes and gifts with
idempotent ledgers, no secrets in the repository or the app, minimal Android permissions, no cleartext
in release builds.

Remaining:

- `npm audit`: 2 moderate advisories in `react-router-dom`, used only by the admin panel. The fix is a
  major version upgrade, left for a separate change.
- No penetration test was done. This is a code review, not a guarantee of security.

## Google Play

This is an engineering review from knowledge of the policies, **not a compliance certification**. The
current policies were not re-read for this report; check them before submitting.

| Area | State |
| --- | --- |
| Account deletion | **Added**: in the app, and a public page at `/delete-account` for the Play Console |
| Diamonds | Sold through Google Play Billing, verified on the server |
| Coins | No cash value, cannot be withdrawn; not sold for money directly |
| Privacy policy, terms | Present at `/privacy`, `/terms` |
| Microphone | Requested only when turning the mic on, friends only |
| Reporting and blocking players | **Added**: on the player's card in the app, with a reports list in the admin panel |
| Paid match entry and prizes | Virtual coins only, but review how Play classifies it (simulated gambling, content rating) |
| Data safety form | Must be filled in by the owner in the Play Console |
| Children | Set the target audience and check the policy text against it |

## Open items

1. Harden SSH: root and password logins are enabled. It needs root access, which this review did not
   have, and should be done with a recovery path ready.
2. Bind MariaDB to localhost, and restrict CloudPanel's port to known addresses. The firewall already
   blocks MariaDB from outside.
3. Copy backups off the server: they are on the same disk today.
4. Test on real low-end phones, and run this load test against a staging copy of the VPS.
