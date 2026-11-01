import { randomUUID } from 'node:crypto';
import { Router } from 'express';
import { publicUser } from './auth';
import { jsonValue, type Queryable } from './db';
import { h, rateLimit, requireAdmin, requireUser, type AppCtx } from './http';
import { notify } from './presence';
import type { RoomManager } from './rooms';
import * as v from './security';
import { HttpError } from './security';
import { payoutPolicy } from './settings';
import { applyWalletTx, claimDailyBonus, listTransactions, walletSummary, type TxType } from './wallet';

/** Player-facing and administrator REST API (everything under /api except auth). */

const PAGE = 20;
const TX_TYPES: TxType[] = ['welcome_bonus', 'match_entry', 'match_reward', 'match_refund', 'daily_bonus', 'referral_reward', 'admin_adjustment'];
const MIN_WINRATE_MATCHES = 5;

function page(q: unknown): { limit: number; offset: number; page: number } {
  const p = Math.min(1000, Math.max(1, Number.parseInt(String(q ?? '1'), 10) || 1));
  return { limit: PAGE, offset: (p - 1) * PAGE, page: p };
}

/** Start of the current season (calendar month, UTC). */
function seasonStart(): Date {
  const d = new Date();
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1));
}

/**
 * Per-player statistics derived only from settled database records. `season` is the
 * placeholder (e.g. '$2') bound to seasonStart(). Portable SQL: runs on PostgreSQL and MySQL.
 */
const statsCte = (season: string) => `
  stats as (
    select mp.user_id,
           count(*)::int as matches,
           sum(case when m.winner_user_id = mp.user_id then 1 else 0 end)::int as wins,
           sum(case when m.ended_at >= ${season} then 1 else 0 end)::int as season_matches,
           sum(case when m.winner_user_id = mp.user_id and m.ended_at >= ${season} then 1 else 0 end)::int as season_wins
    from match_players mp join matches m on m.id = mp.match_id
    where m.status = 'completed' and mp.status in ('finished', 'forfeited')
    group by mp.user_id
  ),
  ranked as (
    select p.user_id, p.username, p.public_id, p.avatar_hue, w.balance,
           coalesce(s.matches, 0) as matches, coalesce(s.wins, 0) as wins,
           coalesce(s.season_wins, 0) * 3 + coalesce(s.season_matches, 0) as season_points,
           case when coalesce(s.matches, 0) = 0 then 0 else round(100.0 * s.wins / s.matches, 1) end as win_rate,
           rank() over (order by coalesce(s.wins, 0) desc) as "rank"
    from profiles p
    join users u on u.id = p.user_id and u.suspended_at is null
    join wallets w on w.user_id = p.user_id
    left join stats s on s.user_id = p.user_id
  )`;

interface StatsRow {
  user_id: string;
  username: string;
  public_id: string;
  avatar_hue: number;
  balance: number;
  matches: number;
  wins: number;
  season_points: number;
  win_rate: number;
  rank: number;
}

async function statsFor(q: Queryable, userId: string) {
  const r = await q.query<StatsRow>(`with ${statsCte('$2')} select * from ranked where user_id = $1`, [userId, seasonStart()]);
  const s = r.rows[0];
  if (!s) throw new HttpError(404, 'Player not found');
  return {
    matches: s.matches,
    wins: s.wins,
    losses: s.matches - s.wins,
    winRate: Number(s.win_rate),
    rank: Number(s.rank),
    seasonPoints: s.season_points,
  };
}

function profileOut(r: { username: string; public_id: string; avatar_hue: number }) {
  return { username: r.username, publicId: r.public_id, avatarHue: r.avatar_hue };
}

export function apiRouter(ctx: AppCtx, rooms: () => RoomManager): Router {
  const r = Router();
  const { db, settings, presence } = ctx;

  // ---------------------------------------------------------------- public config

  r.get(
    '/config',
    h(async () => {
      const s = settings.get();
      return {
        ok: true,
        appBaseUrl: ctx.cfg.appBaseUrl,
        welcomeBonus: s.welcomeBonus,
        dailyBonus: s.dailyBonus,
        payoutMultiplier: s.payoutMultiplier,
        payoutPolicy: payoutPolicy(s),
        modes: s.modes.filter((m) => m.enabled),
      };
    }),
  );

  r.use(requireUser);

  // ---------------------------------------------------------------- profile & players

  r.patch(
    '/profile',
    rateLimit(20, 60_000, (req) => req.user!.id),
    h(async (req) => {
      const b = req.body ?? {};
      const sets: string[] = [];
      const params: unknown[] = [req.user!.id];
      if (b.fullName !== undefined) params.push(v.fullName(b.fullName)), sets.push(`full_name = $${params.length}`);
      if (b.username !== undefined) params.push(v.username(b.username)), sets.push(`username = $${params.length}`);
      if (b.avatarHue !== undefined) params.push(v.int(b.avatarHue, 'Avatar colour', 0, 359)), sets.push(`avatar_hue = $${params.length}`);
      if (!sets.length) throw new HttpError(400, 'Nothing to update');
      try {
        await db.query(`update profiles set ${sets.join(', ')}, updated_at = now() where user_id = $1`, params);
      } catch (e) {
        if ((e as { code?: string }).code === '23505') throw new HttpError(409, 'That username is taken');
        throw e;
      }
      return { ok: true };
    }),
  );

  r.get(
    '/players/search',
    rateLimit(60, 60_000, (req) => req.user!.id),
    h(async (req) => {
      const q = String(req.query.q ?? '').trim();
      if (q.length < 2 || q.length > 20) return { ok: true, players: [] };
      const me = req.user!.id;
      const rows = (
        await db.query<{ user_id: string; username: string; public_id: string; avatar_hue: number; friend: boolean; pending: string | null }>(
          `select p.user_id, p.username, p.public_id, p.avatar_hue,
                  exists (select 1 from friendships f where f.user_a = least($1::uuid, p.user_id) and f.user_b = greatest($1::uuid, p.user_id)) as friend,
                  (select case when fr.from_user = $1 then 'outgoing' else 'incoming' end from friend_requests fr
                    where fr.status = 'pending' and least(fr.from_user, fr.to_user) = least($1::uuid, p.user_id)
                      and greatest(fr.from_user, fr.to_user) = greatest($1::uuid, p.user_id)) as pending
           from profiles p join users u on u.id = p.user_id and u.suspended_at is null
           where p.user_id <> $1 and (p.public_id = upper($2) or lower(p.username) like lower($3))
           order by (p.public_id = upper($2)) desc, p.username limit 10`,
          // Backslash is the default LIKE escape character in both PostgreSQL and MySQL.
          [me, q, `${q.replace(/[\\%_]/g, (c) => `\\${c}`)}%`],
        )
      ).rows;
      return {
        ok: true,
        players: rows.map((p) => ({ ...profileOut(p), isFriend: !!p.friend, pending: p.pending, online: presence.isOnline(p.user_id) })),
      };
    }),
  );

  r.get(
    '/players/:publicId',
    h(async (req) => {
      const p = await db.query<{ user_id: string; username: string; public_id: string; avatar_hue: number; created_at: string }>(
        `select p.user_id, p.username, p.public_id, p.avatar_hue, p.created_at from profiles p join users u on u.id = p.user_id
         where p.public_id = $1 and u.suspended_at is null`,
        [String(req.params.publicId).toUpperCase()],
      );
      if (!p.rows.length) throw new HttpError(404, 'Player not found');
      const row = p.rows[0];
      return { ok: true, player: { ...profileOut(row), joinedAt: row.created_at, online: presence.isOnline(row.user_id), stats: await statsFor(db, row.user_id) } };
    }),
  );

  // ---------------------------------------------------------------- wallet

  r.get('/wallet', h(async (req) => ({ ok: true, wallet: await walletSummary(db, req.user!.id, settings.get().dailyBonus) })));

  r.get(
    '/wallet/transactions',
    h(async (req) => {
      const pg = page(req.query.page);
      const type = TX_TYPES.includes(req.query.type as TxType) ? (req.query.type as TxType) : undefined;
      const res = await listTransactions(db, req.user!.id, { limit: pg.limit, offset: pg.offset, type });
      return { ok: true, ...res, page: pg.page, pageSize: PAGE };
    }),
  );

  r.post(
    '/wallet/daily-bonus',
    rateLimit(5, 60_000, (req) => req.user!.id),
    h(async (req) => {
      const tx = await claimDailyBonus(db, req.user!.id, settings.get().dailyBonus);
      const wallet = await walletSummary(db, req.user!.id, settings.get().dailyBonus);
      presence.emit(req.user!.id, 'wallet', wallet);
      return { ok: true, tx, wallet };
    }),
  );

  // ---------------------------------------------------------------- rooms, matches, stats

  r.get('/rooms/:code', h(async (req) => ({ ok: true, room: rooms().preview(req.params.code) })));

  r.get(
    '/me/stats',
    h(async (req) => {
      const stats = await statsFor(db, req.user!.id);
      const w = await walletSummary(db, req.user!.id, settings.get().dailyBonus);
      const recent = (
        await db.query<{ won: boolean }>(
          `select (m.winner_user_id = mp.user_id) as won from match_players mp join matches m on m.id = mp.match_id
           where mp.user_id = $1 and m.status = 'completed' and mp.status in ('finished', 'forfeited')
           order by m.ended_at desc limit 10`,
          [req.user!.id],
        )
      ).rows.map((x) => (x.won ? 'W' : 'L'));
      return { ok: true, stats: { ...stats, coinsEarned: w.totalEarned, recent } };
    }),
  );

  r.get(
    '/matches/history',
    h(async (req) => {
      const pg = page(req.query.page);
      const me = req.user!.id;
      const total = (await db.query<{ n: number }>('select count(*)::int as n from match_players where user_id = $1', [me])).rows[0].n;
      const rows = (
        await db.query<{
          id: string; mode: string; entry_fee: number; status: string; created_at: string; ended_at: string | null;
          winner_user_id: string | null; mp_status: string; rank: number | null; entry_paid: number; payout: number; refunded: number;
          players: number; disputed: boolean; end_reason: string | null;
        }>(
          `select m.id, m.mode, m.entry_fee, m.status, m.created_at, m.ended_at, m.winner_user_id, m.end_reason,
                  mp.status as mp_status, mp."rank", mp.entry_paid, mp.payout, mp.refunded,
                  (select count(*)::int from match_players x where x.match_id = m.id and x.status <> 'refunded') as players,
                  exists (select 1 from match_disputes d where d.match_id = m.id and d.user_id = mp.user_id) as disputed
           from match_players mp join matches m on m.id = mp.match_id
           where mp.user_id = $1 order by m.created_at desc limit $2 offset $3`,
          [me, pg.limit, pg.offset],
        )
      ).rows;
      const modes = new Map(settings.get().modes.map((m) => [m.id, m.name]));
      return {
        ok: true,
        total,
        page: pg.page,
        pageSize: PAGE,
        items: rows.map((m) => {
          const result =
            m.mp_status === 'forfeited' && m.status !== 'completed' ? 'forfeited'
            : m.status === 'completed'
              ? m.winner_user_id === me ? 'won' : m.mp_status === 'forfeited' ? 'forfeited' : 'lost'
              : m.status === 'cancelled' || m.mp_status === 'refunded' ? 'refunded'
              : m.status === 'abandoned' ? 'abandoned'
              : 'in_progress';
          const paid = m.entry_paid;
          const received = m.payout + m.refunded;
          return {
            id: m.id,
            mode: m.mode,
            modeName: modes.get(m.mode) ?? m.mode,
            entryFee: m.entry_fee,
            date: m.ended_at ?? m.created_at,
            result,
            rank: m.rank,
            players: m.players,
            coinsPaid: paid,
            coinsReceived: received,
            net: received - paid,
            disputed: !!m.disputed,
            endReason: m.end_reason,
          };
        }),
      };
    }),
  );

  r.post(
    '/matches/:id/dispute',
    rateLimit(5, 60 * 60_000, (req) => req.user!.id),
    h(async (req) => {
      const matchId = v.uuid(req.params.id, 'match');
      const reason = v.str(req.body?.reason, 'Reason', 5, 500);
      const part = await db.query('select 1 from match_players where match_id = $1 and user_id = $2', [matchId, req.user!.id]);
      if (!part.rows.length) throw new HttpError(404, 'Match not found');
      try {
        await db.query('insert into match_disputes (match_id, user_id, reason) values ($1, $2, $3)', [matchId, req.user!.id, reason]);
      } catch (e) {
        if ((e as { code?: string }).code === '23505') throw new HttpError(409, 'You already reported this match');
        throw e;
      }
      return { ok: true };
    }),
  );

  r.get(
    '/leaderboard',
    h(async (req) => {
      const pg = page(req.query.page);
      const sort = req.query.sort === 'winrate' ? 'winrate' : req.query.sort === 'season' ? 'season' : 'wins';
      const where = sort === 'winrate' ? `where matches >= ${MIN_WINRATE_MATCHES}` : sort === 'season' ? 'where season_points > 0' : '';
      const order =
        sort === 'winrate' ? 'win_rate desc, wins desc' : sort === 'season' ? 'season_points desc, wins desc' : 'wins desc, win_rate desc';
      const total = (await db.query<{ n: number }>(`with ${statsCte('$1')} select count(*)::int as n from ranked ${where}`, [seasonStart()])).rows[0].n;
      const rows = (
        await db.query<StatsRow>(`with ${statsCte('$3')} select * from ranked ${where} order by ${order}, username limit $1 offset $2`, [
          pg.limit,
          pg.offset,
          seasonStart(),
        ])
      ).rows;
      return {
        ok: true,
        sort,
        minMatches: MIN_WINRATE_MATCHES,
        total,
        page: pg.page,
        pageSize: PAGE,
        items: rows.map((x, i) => ({
          position: pg.offset + i + 1,
          ...profileOut(x),
          wins: x.wins,
          matches: x.matches,
          winRate: Number(x.win_rate),
          seasonPoints: x.season_points,
          balance: x.balance,
          isYou: x.user_id === req.user!.id,
        })),
      };
    }),
  );

  // ---------------------------------------------------------------- friends

  const pair = (a: string, b: string) => (a < b ? [a, b] : [b, a]);
  const touchFriends = (...ids: string[]) => ids.forEach((id) => presence.emit(id, 'friends:changed', null));

  r.get(
    '/friends',
    h(async (req) => {
      const me = req.user!.id;
      const rows = (
        await db.query<{ user_id: string; username: string; public_id: string; avatar_hue: number; since: string }>(
          `select p.user_id, p.username, p.public_id, p.avatar_hue, f.created_at as since
           from friendships f join profiles p on p.user_id = case when f.user_a = $1 then f.user_b else f.user_a end
           where f.user_a = $1 or f.user_b = $1 order by p.username`,
          [me],
        )
      ).rows;
      return {
        ok: true,
        friends: rows.map((f) => {
          const code = presence.roomOf(f.user_id);
          let room = null;
          if (code) {
            try {
              const p = rooms().preview(code);
              room = { code: p.code, modeName: p.mode.name, entryFee: p.entryFee, players: p.players, maxPlayers: p.maxPlayers, joinable: p.joinable, status: p.status };
            } catch {
              room = null;
            }
          }
          return { ...profileOut(f), since: f.since, online: presence.isOnline(f.user_id), room };
        }),
      };
    }),
  );

  r.get(
    '/friends/requests',
    h(async (req) => {
      const me = req.user!.id;
      const rows = (
        await db.query<{ id: string; from_user: string; created_at: string; username: string; public_id: string; avatar_hue: number }>(
          `select fr.id, fr.from_user, fr.created_at, p.username, p.public_id, p.avatar_hue
           from friend_requests fr join profiles p on p.user_id = case when fr.from_user = $1 then fr.to_user else fr.from_user end
           where fr.status = 'pending' and (fr.from_user = $1 or fr.to_user = $1) order by fr.created_at desc`,
          [me],
        )
      ).rows;
      const out = rows.map((x) => ({ id: x.id, createdAt: x.created_at, player: profileOut(x) }));
      return {
        ok: true,
        incoming: out.filter((_, i) => rows[i].from_user !== me),
        outgoing: out.filter((_, i) => rows[i].from_user === me),
      };
    }),
  );

  r.post(
    '/friends/requests',
    rateLimit(30, 60 * 60_000, (req) => req.user!.id),
    h(async (req) => {
      const me = req.user!.id;
      const key = v.str(req.body?.player, 'Player', 3, 20);
      const t = await db.query<{ user_id: string; username: string }>(
        `select p.user_id, p.username from profiles p join users u on u.id = p.user_id and u.suspended_at is null
         where p.public_id = upper($1) or lower(p.username) = lower($1) limit 1`,
        [key],
      );
      const target = t.rows[0];
      if (!target) throw new HttpError(404, 'No player with that username or ID');
      if (target.user_id === me) throw new HttpError(400, 'You cannot add yourself');
      const [a, b] = pair(me, target.user_id);
      if ((await db.query('select 1 from friendships where user_a = $1 and user_b = $2', [a, b])).rows.length) {
        throw new HttpError(409, `You and ${target.username} are already friends`);
      }
      // They already asked you: accepting is what you meant.
      const reverse = await db.query<{ id: string }>(
        `select id from friend_requests where from_user = $1 and to_user = $2 and status = 'pending'`,
        [target.user_id, me],
      );
      if (reverse.rows.length) {
        await acceptRequest(reverse.rows[0].id, me);
        return { ok: true, accepted: true };
      }
      try {
        await db.query('insert into friend_requests (from_user, to_user) values ($1, $2)', [me, target.user_id]);
      } catch (e) {
        if ((e as { code?: string }).code === '23505') throw new HttpError(409, 'A friend request is already pending');
        throw e;
      }
      await notify(db, presence, target.user_id, 'friend_request', { from: req.user!.username, fromPublicId: req.user!.publicId });
      touchFriends(me, target.user_id);
      return { ok: true, accepted: false };
    }),
  );

  async function acceptRequest(id: string, me: string): Promise<void> {
    const fr = await db.tx(async (q) => {
      const r2 = await q.query<{ from_user: string; to_user: string }>(
        `select from_user, to_user from friend_requests where id = $1 and to_user = $2 and status = 'pending' for update`,
        [id, me],
      );
      if (!r2.rows.length) throw new HttpError(404, 'Friend request not found');
      await q.query(`update friend_requests set status = 'accepted', responded_at = now() where id = $1`, [id]);
      const [a, b] = pair(r2.rows[0].from_user, r2.rows[0].to_user);
      await q.query('insert into friendships (user_a, user_b) values ($1, $2) on conflict do nothing', [a, b]);
      return r2.rows[0];
    });
    const meP = (await db.query<{ username: string; public_id: string }>('select username, public_id from profiles where user_id = $1', [me])).rows[0];
    await notify(db, presence, fr.from_user, 'friend_accepted', { by: meP.username, byPublicId: meP.public_id });
    touchFriends(fr.from_user, fr.to_user);
  }

  r.post(
    '/friends/requests/:id/:action',
    h(async (req) => {
      const id = v.uuid(req.params.id, 'request');
      const me = req.user!.id;
      const action = req.params.action;
      if (action === 'accept') {
        await acceptRequest(id, me);
        return { ok: true };
      }
      if (action !== 'reject' && action !== 'cancel') throw new HttpError(404, 'Unknown action');
      const col = action === 'reject' ? 'to_user' : 'from_user';
      const fr = await db.tx(async (q) => {
        const r2 = await q.query<{ from_user: string; to_user: string }>(
          `select from_user, to_user from friend_requests where id = $1 and ${col} = $2 and status = 'pending' for update`,
          [id, me],
        );
        if (!r2.rows.length) throw new HttpError(404, 'Friend request not found');
        await q.query('update friend_requests set status = $2, responded_at = now() where id = $1', [id, action === 'reject' ? 'rejected' : 'cancelled']);
        return r2.rows[0];
      });
      touchFriends(fr.from_user, fr.to_user);
      return { ok: true };
    }),
  );

  r.delete(
    '/friends/:publicId',
    h(async (req) => {
      const me = req.user!.id;
      const t = await db.query<{ user_id: string }>('select user_id from profiles where public_id = $1', [String(req.params.publicId).toUpperCase()]);
      if (!t.rows.length) throw new HttpError(404, 'Player not found');
      const [a, b] = pair(me, t.rows[0].user_id);
      const d = await db.query('delete from friendships where user_a = $1 and user_b = $2', [a, b]);
      if (!d.rowCount) throw new HttpError(404, 'You are not friends');
      touchFriends(me, t.rows[0].user_id);
      return { ok: true };
    }),
  );

  r.post(
    '/friends/invite',
    rateLimit(20, 10 * 60_000, (req) => req.user!.id),
    h(async (req) => {
      const me = req.user!;
      const t = await db.query<{ user_id: string }>('select user_id from profiles where public_id = $1', [
        v.str(req.body?.publicId, 'Player', 8, 8).toUpperCase(),
      ]);
      if (!t.rows.length) throw new HttpError(404, 'Player not found');
      const [a, b] = pair(me.id, t.rows[0].user_id);
      if (!(await db.query('select 1 from friendships where user_a = $1 and user_b = $2', [a, b])).rows.length) {
        throw new HttpError(403, 'You can only invite friends');
      }
      const code = presence.roomOf(me.id);
      if (!code) throw new HttpError(400, 'Create or join a room first');
      const room = rooms().preview(code);
      if (!room.joinable) throw new HttpError(400, 'Your room is not open for new players');
      await notify(db, presence, t.rows[0].user_id, 'room_invite', {
        from: me.username,
        code: room.code,
        modeName: room.mode.name,
        entryFee: room.entryFee,
      });
      return { ok: true };
    }),
  );

  // ---------------------------------------------------------------- notifications

  r.get(
    '/notifications',
    h(async (req) => {
      const rows = (
        await db.query<{ id: string; type: string; payload: Record<string, unknown>; read_at: string | null; created_at: string }>(
          'select id, type, payload, read_at, created_at from notifications where user_id = $1 order by created_at desc limit 30',
          [req.user!.id],
        )
      ).rows;
      const unread = (await db.query<{ n: number }>('select count(*)::int as n from notifications where user_id = $1 and read_at is null', [req.user!.id])).rows[0].n;
      return { ok: true, unread, items: rows.map((n) => ({ id: n.id, type: n.type, payload: jsonValue(n.payload), readAt: n.read_at, createdAt: n.created_at })) };
    }),
  );

  r.post(
    '/notifications/read',
    h(async (req) => {
      const ids = Array.isArray(req.body?.ids) ? (req.body.ids as unknown[]).slice(0, 100).map((x) => v.uuid(x, 'notification')) : null;
      if (ids?.length) {
        const list = ids.map((_, i) => `$${i + 2}`).join(', ');
        await db.query(`update notifications set read_at = now() where user_id = $1 and id in (${list}) and read_at is null`, [req.user!.id, ...ids]);
      } else if (!ids) await db.query('update notifications set read_at = now() where user_id = $1 and read_at is null', [req.user!.id]);
      return { ok: true };
    }),
  );

  // ---------------------------------------------------------------- administration

  const admin = Router();
  admin.use(requireAdmin);

  admin.get(
    '/overview',
    h(async () => {
      const one = async (sql: string, params?: unknown[]) => (await db.query<{ n: number }>(sql, params)).rows[0].n ?? 0;
      const byStatus = (await db.query<{ status: string; n: number }>('select status, count(*)::int as n from matches group by status')).rows;
      return {
        ok: true,
        users: await one('select count(*)::int as n from users'),
        suspended: await one('select count(*)::int as n from users where suspended_at is not null'),
        activeToday: await one('select count(distinct user_id)::int as n from sessions where last_seen_at > $1', [new Date(Date.now() - 24 * 3600_000)]),
        coinsInCirculation: await one('select coalesce(sum(balance), 0)::bigint as n from wallets'),
        totalPayouts: await one('select coalesce(sum(payout), 0)::bigint as n from match_settlements'),
        houseTotal: await one('select coalesce(sum(house_amount), 0)::bigint as n from match_settlements'),
        openDisputes: await one(`select count(*)::int as n from match_disputes where status = 'open'`),
        matchesByStatus: Object.fromEntries(byStatus.map((x) => [x.status, x.n])),
        liveRooms: rooms().roomCount,
      };
    }),
  );

  admin.get(
    '/users',
    h(async (req) => {
      const pg = page(req.query.page);
      const q = String(req.query.q ?? '').trim().slice(0, 100);
      const params: unknown[] = [pg.limit, pg.offset];
      let where = '';
      if (q) {
        params.push(`%${q.replace(/[\\%_]/g, (c) => `\\${c}`)}%`);
        where = `where u.email ilike $3 or p.username ilike $3 or p.public_id ilike $3 or p.full_name ilike $3`;
      }
      const rows = (
        await db.query(
          `select u.id, u.email, u.role, u.suspended_at, u.suspended_reason, u.email_verified_at, u.created_at,
                  p.full_name, p.username, p.public_id, w.balance,
                  (select count(*)::int from match_players mp where mp.user_id = u.id) as matches
           from users u join profiles p on p.user_id = u.id join wallets w on w.user_id = u.id
           ${where} order by u.created_at desc limit $1 offset $2`,
          params,
        )
      ).rows;
      return { ok: true, page: pg.page, items: rows };
    }),
  );

  const audit = (q: Queryable, actor: string, action: string, details: object, targetUser?: string | null, targetMatch?: string | null) =>
    q.query('insert into audit_logs (actor_id, action, target_user_id, target_match_id, details) values ($1, $2, $3, $4, $5::jsonb)', [
      actor,
      action,
      targetUser ?? null,
      targetMatch ?? null,
      JSON.stringify(details),
    ]);

  admin.post(
    '/users/:id/suspend',
    h(async (req) => {
      const id = v.uuid(req.params.id, 'user');
      if (id === req.user!.id) throw new HttpError(400, 'You cannot suspend yourself');
      const reason = v.str(req.body?.reason, 'Reason', 3, 300);
      await db.tx(async (q) => {
        const u = await q.query(`update users set suspended_at = now(), suspended_reason = $2, updated_at = now() where id = $1 and role <> 'admin'`, [id, reason]);
        if (!u.rowCount) throw new HttpError(404, 'User not found (administrators cannot be suspended here)');
        await q.query('update sessions set revoked_at = now() where user_id = $1 and revoked_at is null', [id]);
        await audit(q, req.user!.id, 'user.suspend', { reason }, id);
      });
      await rooms().evict(id);
      presence.kick(id);
      return { ok: true };
    }),
  );

  admin.post(
    '/users/:id/unsuspend',
    h(async (req) => {
      const id = v.uuid(req.params.id, 'user');
      await db.tx(async (q) => {
        const u = await q.query('update users set suspended_at = null, suspended_reason = null, updated_at = now() where id = $1', [id]);
        if (!u.rowCount) throw new HttpError(404, 'User not found');
        await audit(q, req.user!.id, 'user.unsuspend', {}, id);
      });
      return { ok: true };
    }),
  );

  admin.post(
    '/users/:id/adjust',
    rateLimit(30, 60_000, (req) => req.user!.id),
    h(async (req) => {
      const id = v.uuid(req.params.id, 'user');
      const amount = v.int(req.body?.amount, 'Amount', -10_000_000, 10_000_000);
      if (amount === 0) throw new HttpError(400, 'Amount cannot be zero');
      const reason = v.str(req.body?.reason, 'Reason', 5, 300);
      // The admin UI sends a fresh request id per action, so a double click can't apply it twice.
      const requestId = typeof req.body?.requestId === 'string' && /^[A-Za-z0-9-]{8,64}$/.test(req.body.requestId) ? req.body.requestId : randomUUID();
      const tx = await db.tx(async (q) => {
        const r2 = await applyWalletTx(q, {
          userId: id,
          type: 'admin_adjustment',
          amount,
          key: `admin:${requestId}`,
          note: reason,
          createdBy: req.user!.id,
        });
        if (!r2.duplicate) await audit(q, req.user!.id, 'wallet.adjust', { amount, reason, transactionId: r2.tx.id }, id);
        return r2.tx;
      });
      const wallet = await walletSummary(db, id, settings.get().dailyBonus);
      presence.emit(id, 'wallet', wallet);
      await notify(db, presence, id, 'wallet', { amount, reason }).catch(() => {});
      return { ok: true, tx, wallet };
    }),
  );

  admin.get(
    '/transactions',
    h(async (req) => {
      const pg = page(req.query.page);
      const params: unknown[] = [pg.limit, pg.offset];
      const conds: string[] = [];
      if (req.query.suspicious === '1') {
        params.push(settings.get().suspiciousAmount);
        conds.push(`(abs(t.amount) >= $${params.length} or t.type = 'admin_adjustment')`);
      }
      if (typeof req.query.userId === 'string' && req.query.userId) {
        params.push(v.uuid(req.query.userId, 'user'));
        conds.push(`t.user_id = $${params.length}`);
      }
      const rows = (
        await db.query(
          `select t.*, p.username, a.username as created_by_name from wallet_transactions t
           join profiles p on p.user_id = t.user_id left join profiles a on a.user_id = t.created_by
           ${conds.length ? `where ${conds.join(' and ')}` : ''} order by t.created_at desc limit $1 offset $2`,
          params,
        )
      ).rows;
      return { ok: true, page: pg.page, items: rows };
    }),
  );

  admin.get(
    '/matches',
    h(async (req) => {
      const pg = page(req.query.page);
      const params: unknown[] = [pg.limit, pg.offset];
      let where = '';
      if (typeof req.query.status === 'string' && ['waiting', 'in_progress', 'completed', 'cancelled', 'abandoned'].includes(req.query.status)) {
        params.push(req.query.status);
        where = `where m.status = $3`;
      }
      const rows = (
        await db.query(
          `select m.id, m.room_code, m.mode, m.entry_fee, m.status, m.created_at, m.started_at, m.ended_at, m.end_reason,
                  w.username as winner, s.total_pot, s.payout, s.house_amount,
                  (select count(*)::int from match_players x where x.match_id = m.id) as players
           from matches m left join profiles w on w.user_id = m.winner_user_id left join match_settlements s on s.match_id = m.id
           ${where} order by m.created_at desc limit $1 offset $2`,
          params,
        )
      ).rows;
      return { ok: true, page: pg.page, items: rows };
    }),
  );

  admin.get(
    '/disputes',
    h(async (req) => {
      const status = ['open', 'resolved', 'rejected'].includes(String(req.query.status)) ? String(req.query.status) : 'open';
      const rows = (
        await db.query(
          `select d.*, p.username, m.mode, m.entry_fee, m.status as match_status, w.username as winner
           from match_disputes d join profiles p on p.user_id = d.user_id join matches m on m.id = d.match_id
           left join profiles w on w.user_id = m.winner_user_id
           where d.status = $1 order by d.created_at desc limit 100`,
          [status],
        )
      ).rows;
      return { ok: true, items: rows };
    }),
  );

  admin.post(
    '/disputes/:id',
    h(async (req) => {
      const id = v.uuid(req.params.id, 'dispute');
      const status = req.body?.status;
      if (status !== 'resolved' && status !== 'rejected') throw new HttpError(400, 'Status must be resolved or rejected');
      const note = v.str(req.body?.note, 'Note', 3, 500);
      await db.tx(async (q) => {
        const d = await q.query<{ match_id: string; user_id: string }>(
          `select match_id, user_id from match_disputes where id = $1 and status = 'open' for update`,
          [id],
        );
        if (!d.rows.length) throw new HttpError(404, 'Open dispute not found');
        await q.query('update match_disputes set status = $2, admin_note = $3, resolved_by = $4, resolved_at = now() where id = $1', [
          id,
          status,
          note,
          req.user!.id,
        ]);
        await audit(q, req.user!.id, `dispute.${status}`, { note, disputeId: id }, d.rows[0].user_id, d.rows[0].match_id);
      });
      return { ok: true };
    }),
  );

  admin.get('/settings', h(async () => ({ ok: true, settings: settings.get(), policy: payoutPolicy(settings.get()) })));

  admin.put(
    '/settings',
    h(async (req) => {
      const s = await settings.update(req.body ?? {}, req.user!.id);
      return { ok: true, settings: s, policy: payoutPolicy(s) };
    }),
  );

  admin.get(
    '/audit',
    h(async (req) => {
      const pg = page(req.query.page);
      const rows = (
        await db.query(
          `select l.id, l.action, l.details, l.created_at, a.username as actor, t.username as target
           from audit_logs l left join profiles a on a.user_id = l.actor_id left join profiles t on t.user_id = l.target_user_id
           order by l.created_at desc limit $1 offset $2`,
          [pg.limit, pg.offset],
        )
      ).rows.map((l) => ({ ...l, details: jsonValue(l.details) }));
      return { ok: true, page: pg.page, items: rows };
    }),
  );

  r.use('/admin', admin);

  // Expose the shape of the signed-in user for convenience.
  r.get('/me/profile', h(async (req) => ({ ok: true, user: publicUser(req.user!) })));

  return r;
}
