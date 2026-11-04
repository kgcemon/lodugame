import { randomUUID } from 'node:crypto';
import { RECONNECT_GRACE_MS, type ForfeitReason, type MatchResult } from '../shared/protocol';
import type { Db, Queryable } from './db';
import { notify, type Presence } from './presence';
import { HttpError } from './security';
import type { MatchMode, Settings } from './settings';
import { applyWalletTx, walletSummary } from './wallet';
import { MAX_REROLLS, REROLL_COSTS, applyGemTx, gemBalance, rerollsUsed } from './gems';
import { cosmetics } from './skins';
import { rewardFromWallet } from './gifts';
import type { RewardEvent } from '../shared/gifts';

/**
 * Money side of a match. Each step is one database transaction that locks the
 * match row first, so concurrent joins/leaves/settlements serialise and every
 * coin movement is idempotent:
 *
 *   entry   key entry:<match>:<attempt>    (deducted when a player takes a seat)
 *   refund  key refund:<match>:<attempt>   (leaving before start, cancellations, server faults)
 *   reward  key reward:<match>             + match_settlements primary key (exactly once)
 *
 * start() is the point of no return: it checks that everyone's fee is in the ledger exactly once,
 * fixes the prize pool and starts the match, in one transaction. Only then do clients see coins fly.
 *
 * REFUND POLICY
 *   - Leaving a room (or being removed / timing out) before the match starts: full refund.
 *   - Room cancelled before start (host left everyone, creation failure, empty room): full refund.
 *   - Server restart/crash while waiting or in progress: full refund to everyone still in the
 *     match (not the players' fault); players who had already forfeited are not refunded.
 *   - Leaving after the match started, or staying offline past the reconnection deadline: a
 *     forfeit, no refund, and no reward whatever happens next.
 *   - Every human leaving a started match with nobody left to win: abandoned, no refunds.
 *   - Losing: no refund.
 */

export interface LedgerCtx {
  db: Db;
  settings: Settings;
  presence: Presence;
  requireEmailVerification: boolean;
}

export interface MatchInfo {
  matchId: string;
  entryFee: number;
  payoutMultiplier: number;
}

/** What start() confirmed. */
export interface ConfirmedMatch {
  /** What everyone paid in. */
  pool: number;
  /** What each winner will be credited. */
  winnerReward: number;
}

export interface SettleResult {
  winnerUserId: string | null;
  payout: number;
  pot: number;
  house: number;
}

/** Who sat down for a match, bots included. */
export interface LineupSeat {
  color: string;
  name: string;
  kind: string;
}

interface ResultRow {
  id: string;
  mode: string;
  status: string;
  end_reason: string | null;
  lineup: string | null;
  winner: string | null;
  winner_color: string | null;
  color: string | null;
  forfeit_reason: ForfeitReason | null;
  entry_paid: number;
  payout: number;
  refunded: number;
}

const RESULT_SELECT = `
  select m.id, m.mode, m.status, m.end_reason, m.lineup, w.username as winner, m.winner_color,
         mp.color, mp.forfeit_reason, mp.entry_paid, mp.payout, mp.refunded
  from match_players mp
  join matches m on m.id = mp.match_id
  left join profiles w on w.user_id = m.winner_user_id`;

/** End reason of a match whose last player also ran out of time, so nobody could win it. */
export const NO_ELIGIBLE_PLAYERS = 'no eligible players';

/** A restart's "match cancelled" notice stops being news after this. */
const RESULT_MAX_AGE_MS = 24 * 60 * 60_000;

function resultMessage(r: ResultRow, players: number): string {
  const secs = RECONNECT_GRACE_MS / 1000;
  if (!r.forfeit_reason) return 'The server restarted during your match, so it was cancelled and your entry fee was refunded.';
  if (r.forfeit_reason === 'left') return 'You voluntarily left the match.';
  if (r.forfeit_reason === 'removed') return 'You were removed from the match by an administrator.';
  if (players > 2) return `You were eliminated because you did not reconnect within the ${secs}-second grace period.`;
  if (r.status === 'completed') return `Your connection was lost for more than ${secs} seconds. Your opponent won the match.`;
  if (r.end_reason === NO_ELIGIBLE_PLAYERS) return `Your connection was lost for more than ${secs} seconds, and so was your opponent's. Nobody won the match.`;
  return `Your connection was lost for more than ${secs} seconds.`;
}

async function lockMatch(q: Queryable, matchId: string) {
  const m = await q.query<{ status: string; entry_fee: number; payout_multiplier: number }>(
    'select status, entry_fee, payout_multiplier from matches where id = $1 for update',
    [matchId],
  );
  if (!m.rows.length) throw new HttpError(404, 'Match not found');
  return m.rows[0];
}

async function refundPlayer(q: Queryable, matchId: string, userId: string, note: string): Promise<boolean> {
  const p = await q.query<{ attempt: number; entry_paid: number; refunded: number; status: string }>(
    'select attempt, entry_paid, refunded, status from match_players where match_id = $1 and user_id = $2 for update',
    [matchId, userId],
  );
  const row = p.rows[0];
  if (!row) return false;
  const due = row.entry_paid - row.refunded;
  if (due > 0) {
    await applyWalletTx(q, { userId, type: 'match_refund', amount: due, key: `refund:${matchId}:${row.attempt}`, matchId, note });
  }
  await q.query(
    `update match_players set refunded = entry_paid, status = 'refunded', left_at = coalesce(left_at, now()) where match_id = $1 and user_id = $2`,
    [matchId, userId],
  );
  return due > 0;
}

export class MatchLedger {
  constructor(private ctx: LedgerCtx) {}

  /** Push fresh wallet numbers to the user's open tabs. */
  async pushWallet(userIds: Iterable<string>): Promise<void> {
    const daily = this.ctx.settings.get().dailyBonus;
    for (const id of new Set(userIds)) {
      try {
        this.ctx.presence.emit(id, 'wallet', await walletSummary(this.ctx.db, id, daily));
      } catch {
        /* user may have been deleted */
      }
    }
  }

  /** Tell a player about a gift, in their notifications: it keeps until they read it. */
  notifyGift(userId: string, giftId: string, message: string): void {
    notify(this.ctx.db, this.ctx.presence, userId, 'system', { message, playerGiftId: giftId }).catch((e) => console.error('[ledger] gift notice failed', e));
  }

  async assertCanPlay(userId: string, fee: number): Promise<void> {
    if (this.ctx.requireEmailVerification && fee > 0) {
      const u = await this.ctx.db.query<{ email_verified_at: string | null }>('select email_verified_at from users where id = $1', [userId]);
      if (!u.rows[0]?.email_verified_at) throw new HttpError(403, 'Verify your email address before joining paid matches');
    }
    if (fee <= 0) return;
    const w = await this.ctx.db.query<{ balance: number }>('select balance from wallets where user_id = $1', [userId]);
    if ((w.rows[0]?.balance ?? 0) < fee) throw new HttpError(402, 'Insufficient Coins! Play more matches or collect your free bonus.', 'INSUFFICIENT_COINS');
  }

  async createMatch(p: { roomCode: string; mode: MatchMode; maxPlayers: number; isPrivate: boolean; createdBy: string }): Promise<MatchInfo> {
    const multiplier = this.ctx.settings.get().payoutMultiplier;
    const matchId = randomUUID();
    await this.ctx.db.query(
      `insert into matches (id, room_code, mode, entry_fee, payout_multiplier, max_players, is_private, created_by)
       values ($1, $2, $3, $4, $5, $6, $7, $8)`,
      [matchId, p.roomCode, p.mode.id, p.mode.entryFee, multiplier, p.maxPlayers, p.isPrivate, p.createdBy],
    );
    return { matchId, entryFee: p.mode.entryFee, payoutMultiplier: multiplier };
  }

  /** Seat a player and charge the official entry fee (from the match row, never from the client). */
  async join(matchId: string, userId: string): Promise<{ charged: number }> {
    const charged = await this.ctx.db.tx(async (q) => {
      const m = await lockMatch(q, matchId);
      if (m.status !== 'waiting') throw new HttpError(409, 'This match has already started');
      const ex = await q.query<{ status: string; attempt: number }>(
        'select status, attempt from match_players where match_id = $1 and user_id = $2 for update',
        [matchId, userId],
      );
      if (ex.rows[0]?.status === 'joined') return 0; // duplicate request: already paid for this seat
      const attempt = ex.rows.length ? ex.rows[0].attempt + 1 : 1;
      await q.query(
        `insert into match_players (match_id, user_id, attempt) values ($1, $2, $3)
         on conflict (match_id, user_id) do update
           set status = 'joined', attempt = excluded.attempt, entry_paid = 0, refunded = 0, payout = 0,
               "rank" = null, joined_at = now(), left_at = null`,
        [matchId, userId, attempt],
      );
      if (m.entry_fee > 0) {
        await applyWalletTx(q, {
          userId,
          type: 'match_entry',
          amount: -m.entry_fee,
          key: `entry:${matchId}:${attempt}`,
          matchId,
          note: 'Match entry fee',
        });
        await q.query('update match_players set entry_paid = $3 where match_id = $1 and user_id = $2', [matchId, userId, m.entry_fee]);
      }
      return m.entry_fee;
    });
    if (charged) await this.pushWallet([userId]);
    return { charged };
  }

  /** Player left (or was removed) before the start: refund in full. */
  async leaveBeforeStart(matchId: string, userId: string): Promise<void> {
    const refunded = await this.ctx.db.tx(async (q) => {
      const m = await lockMatch(q, matchId);
      if (m.status !== 'waiting') return false;
      return refundPlayer(q, matchId, userId, 'Refund: left before the match started');
    });
    if (refunded) await this.pushWallet([userId]);
  }

  /**
   * Confirm the match, all or nothing: every seated player has paid the official fee exactly once
   * (seat record and ledger agree) and nobody else is waiting on this match. Then the pool is fixed
   * and the match is on. If any check fails nothing has changed and the fees can still be refunded.
   */
  async start(matchId: string, seats: { userId: string; color: string }[], lineup: LineupSeat[] = []): Promise<ConfirmedMatch> {
    return this.ctx.db.tx(async (q) => {
      const m = await lockMatch(q, matchId);
      if (m.status !== 'waiting') throw new HttpError(409, 'Match cannot be started');
      for (const s of seats) {
        const r = await q.query(
          `update match_players set status = 'playing', color = $3
           where match_id = $1 and user_id = $2 and status = 'joined' and entry_paid = $4 and refunded = 0`,
          [matchId, s.userId, s.color, m.entry_fee],
        );
        if (!r.rowCount) throw new HttpError(409, 'A player is not properly seated');
        const paid = await q.query<{ net: number | string }>(
          `select coalesce(sum(amount), 0) as net from wallet_transactions
           where match_id = $1 and user_id = $2 and type in ('match_entry', 'match_refund')`,
          [matchId, s.userId],
        );
        if (Number(paid.rows[0].net) !== -m.entry_fee) throw new HttpError(409, 'A player is not properly seated');
      }
      const strays = await q.query(`select user_id from match_players where match_id = $1 and status = 'joined'`, [matchId]);
      if (strays.rows.length) throw new HttpError(409, 'A player is not properly seated');
      const pool = seats.length * m.entry_fee;
      await q.query(`update matches set status = 'in_progress', started_at = now(), lineup = $2, prize_pool = $3 where id = $1`, [matchId, JSON.stringify(lineup), pool]);
      return { pool, winnerReward: Math.round(m.entry_fee * m.payout_multiplier) };
    });
  }

  /** Leaderboard places (by wins, as on the Ranks tab) of players who have won at least once. */
  async ranks(userIds: string[]): Promise<Map<string, number>> {
    if (!userIds.length) return new Map();
    const marks = userIds.map((_, i) => `$${i + 1}`).join(', ');
    const rows = (await this.ctx.db.query<{ user_id: string; wins: number | string; place: number | string }>(
      `with stats as (
         select mp.user_id, sum(case when mp.won then 1 else 0 end) as wins
         from match_players mp
         join matches m on m.id = mp.match_id
         join users u on u.id = mp.user_id and u.suspended_at is null
         where m.status = 'completed' and mp.status in ('finished', 'forfeited')
         group by mp.user_id
       )
       select me.user_id, me.wins, 1 + (select count(*) from stats o where o.wins > me.wins) as place
       from stats me where me.user_id in (${marks})`,
      userIds,
    )).rows;
    return new Map(rows.filter((r) => Number(r.wins) > 0).map((r) => [r.user_id, Number(r.place)]));
  }

  /** The server saw this player's connection drop; they have until `deadline` to come back. */
  async markDisconnected(matchId: string, userId: string, at: number, deadline: number): Promise<void> {
    await this.ctx.db.query(
      `update match_players set connection_status = 'disconnected', disconnected_at = $3, reconnect_deadline = $4
       where match_id = $1 and user_id = $2 and status = 'playing'`,
      [matchId, userId, new Date(at), new Date(deadline)],
    );
  }

  async markReconnected(matchId: string, userId: string, at: number): Promise<void> {
    await this.ctx.db.query(
      `update match_players set connection_status = 'reconnected', reconnected_at = $3, reconnect_deadline = null
       where match_id = $1 and user_id = $2 and status = 'playing'`,
      [matchId, userId, new Date(at)],
    );
  }

  /** Out of the match, once: asking again changes nothing. No refund, and settle() pays them nothing. */
  async forfeit(matchId: string, userId: string, reason: ForfeitReason = 'left', at = Date.now(), connection: 'forfeited' | 'eliminated' = 'forfeited'): Promise<void> {
    await this.ctx.db.query(
      `update match_players set status = 'forfeited', left_at = $3, forfeited_at = $3, forfeit_reason = $4,
              connection_status = $5, reconnect_deadline = null
       where match_id = $1 and user_id = $2 and status = 'playing'`,
      [matchId, userId, new Date(at), reason, connection],
    );
  }

  /**
   * Settle a finished match exactly once. `ranking` lists finishing positions
   * as user ids (null = a bot seat). The first `winners` places win (2 for both
   * partners in 2 vs 2), and each human winner gets multiplier × entry fee.
   */
  async settle(matchId: string, ranking: (string | null)[], winnerColor: string, winners = 1, endReason = 'finished'): Promise<SettleResult | null> {
    const res = await this.ctx.db.tx(async (q) => {
      const m = await lockMatch(q, matchId);
      if (m.status !== 'in_progress') return null;
      const players = (await q.query<{ user_id: string; status: string; entry_paid: number; refunded: number }>(
        'select user_id, status, entry_paid, refunded from match_players where match_id = $1',
        [matchId],
      )).rows;
      const pot = players.reduce((a, p) => a + (p.entry_paid - p.refunded), 0);
      // Winners who forfeited (left mid-match) get nothing; a bot seat is null.
      const paid = ranking
        .slice(0, winners)
        .filter((uid): uid is string => !!uid && players.some((p) => p.user_id === uid && p.status === 'playing'));
      const winner = paid[0] ?? null;
      const payout = paid.length ? Math.round(m.entry_fee * m.payout_multiplier) : 0;
      const totalPayout = payout * paid.length;
      const policy = `winner_total_payout = ${m.payout_multiplier} x entry_fee${winners > 1 ? ' per winning partner' : ''}; unpaid remainder retained by house`;

      const ins = await q.query(
        `insert into match_settlements (match_id, winner_user_id, total_pot, payout, house_amount, policy)
         values ($1, $2, $3, $4, $5, $6) on conflict (match_id) do nothing`,
        [matchId, winner, pot, totalPayout, pot - totalPayout, policy],
      );
      if (!ins.rowCount) return null; // already settled

      const rewards: { userId: string; reward: RewardEvent }[] = [];
      if (payout > 0) {
        for (const uid of paid) {
          const key = uid === winner ? `reward:${matchId}` : `reward:${matchId}:${uid}`;
          const r = await applyWalletTx(q, { userId: uid, type: 'match_reward', amount: payout, key, matchId, note: 'Match win payout' });
          if (!r.duplicate) rewards.push({ userId: uid, reward: rewardFromWallet(r.tx, 'match_win', matchId) });
        }
      }
      for (let i = 0; i < ranking.length; i++) {
        const uid = ranking[i];
        if (!uid) continue;
        await q.query(
          `update match_players set "rank" = $3, won = $4, payout = case when $4 then $5::bigint else 0 end,
                  status = case when status = 'playing' then 'finished' else status end,
                  connection_status = case when $4 then 'winner' else connection_status end
           where match_id = $1 and user_id = $2`,
          [matchId, uid, i + 1, paid.includes(uid), payout],
        );
      }
      await q.query(
        `update matches set status = 'completed', ended_at = now(), winner_user_id = $2, winner_color = $3, end_reason = $4 where id = $1`,
        [matchId, winner, winnerColor, endReason],
      );
      return { winnerUserId: winner, winnerUserIds: paid, payout, pot, house: pot - totalPayout, players: players.map((p) => p.user_id), rewards };
    });
    if (!res) return null;
    await this.pushWallet(res.players);
    // The coins are in the ledger: the winner's app can show them flying to the wallet.
    for (const r of res.rewards) this.ctx.presence.emit(r.userId, 'reward', r.reward);
    for (const uid of res.players) {
      const won = res.winnerUserIds.includes(uid);
      await notify(this.ctx.db, this.ctx.presence, uid, 'match_result', {
        matchId,
        won,
        payout: won ? res.payout : 0,
        rank: ranking.indexOf(uid) + 1 || null,
      }).catch(() => {});
    }
    return { winnerUserId: res.winnerUserId, payout: res.payout, pot: res.pot, house: res.house };
  }

  /** Cancel a match that never started: everyone who paid is refunded. */
  async cancel(matchId: string, reason: string): Promise<void> {
    const ids = await this.ctx.db.tx(async (q) => {
      const m = await lockMatch(q, matchId);
      if (m.status !== 'waiting') return [] as string[];
      const ps = (await q.query<{ user_id: string }>(`select user_id from match_players where match_id = $1 and status = 'joined'`, [matchId])).rows;
      for (const p of ps) await refundPlayer(q, matchId, p.user_id, `Refund: match cancelled (${reason})`);
      await q.query(`update matches set status = 'cancelled', ended_at = now(), end_reason = $2 where id = $1`, [matchId, reason]);
      return ps.map((p) => p.user_id);
    });
    if (ids.length) await this.pushWallet(ids);
  }

  /** End a started match that cannot finish. Refunds only when the platform is at fault. */
  async abandon(matchId: string, reason: string, refund: boolean): Promise<void> {
    const ids = await this.ctx.db.tx(async (q) => {
      const m = await lockMatch(q, matchId);
      if (m.status !== 'in_progress') return [] as string[];
      // Only players still in the match are refunded; anyone who already forfeited gave up their fee.
      const ps = (await q.query<{ user_id: string }>(`select user_id from match_players where match_id = $1 and status = 'playing'`, [matchId])).rows;
      if (refund) for (const p of ps) await refundPlayer(q, matchId, p.user_id, `Refund: match abandoned (${reason})`);
      await q.query(`update matches set status = 'abandoned', ended_at = now(), end_reason = $2 where id = $1`, [matchId, reason]);
      return ps.map((p) => p.user_id);
    });
    if (refund && ids.length) await this.pushWallet(ids);
  }

  /** The result screen for one player, from the match record as it stands now. */
  async matchResult(matchId: string, userId: string): Promise<MatchResult | null> {
    const row = (await this.ctx.db.query<ResultRow>(`${RESULT_SELECT} where mp.match_id = $1 and mp.user_id = $2`, [matchId, userId])).rows[0];
    return row ? this.describeResult(row, userId) : null;
  }

  /**
   * A result this player hasn't confirmed seeing: they forfeited, or a restart cut their match
   * short. Recorded in the database, so it still reaches them after a restart of the server.
   */
  async pendingResult(userId: string): Promise<MatchResult | null> {
    const row = (await this.ctx.db.query<ResultRow>(
      `${RESULT_SELECT}
       where mp.user_id = $1 and mp.result_seen_at is null
         and (mp.forfeited_at is not null
              or (m.status = 'abandoned' and m.end_reason = 'server restart' and mp.status = 'refunded' and m.ended_at >= $2))
       order by coalesce(mp.forfeited_at, m.ended_at) desc limit 1`,
      [userId, new Date(Date.now() - RESULT_MAX_AGE_MS)],
    )).rows[0];
    return row ? this.describeResult(row, userId) : null;
  }

  async resultSeen(matchId: string, userId: string): Promise<void> {
    await this.ctx.db.query('update match_players set result_seen_at = now() where match_id = $1 and user_id = $2 and result_seen_at is null', [matchId, userId]);
  }

  private async describeResult(r: ResultRow, userId: string): Promise<MatchResult> {
    const lineup = r.lineup ? (JSON.parse(r.lineup) as LineupSeat[]) : [];
    const opponents = lineup.filter((s) => s.color !== r.color).map((s) => s.name);
    const interrupted = !r.forfeit_reason;
    const paid = Number(r.entry_paid);
    const received = Number(r.payout) + Number(r.refunded);
    const mode = this.ctx.settings.get().modes.find((m) => m.id === r.mode && m.enabled && !m.privateOnly);
    const balance = (await this.ctx.db.query<{ balance: number }>('select balance from wallets where user_id = $1', [userId])).rows[0]?.balance ?? 0;
    return {
      matchId: r.id,
      outcome: interrupted ? 'cancelled' : 'lost',
      reason: r.forfeit_reason ?? 'server_restart',
      message: resultMessage(r, lineup.length),
      opponents,
      winnerName: r.winner ?? lineup.find((s) => s.color === r.winner_color)?.name ?? null,
      status: r.status as MatchResult['status'],
      coinsPaid: paid,
      coinsReceived: received,
      net: received - paid,
      playAgain: mode && balance >= mode.entryFee ? { modeId: mode.id, name: mode.name, entryFee: mode.entryFee } : null,
    };
  }

  /** The dice skin and profile frame a player shows in matches. */
  async cosmetics(userId: string): Promise<{ diceSkin: string; frame: string | null; board: string }> {
    return cosmetics(this.ctx.db, userId);
  }

  /** Current gem balance (0 for unknown users). */
  async gems(userId: string): Promise<number> {
    return gemBalance(this.ctx.db, userId);
  }

  /**
   * Charge the next re-roll of this match: 5, 15, then 45 gems, at most three per match.
   * Keyed by match and attempt number, so a retried request never charges twice.
   */
  async chargeReroll(matchId: string, userId: string): Promise<{ attempt: number; cost: number; gems: number }> {
    const res = await this.ctx.db.tx(async (q) => {
      const used = await rerollsUsed(q, userId, matchId);
      if (used >= MAX_REROLLS) throw new HttpError(409, `You can re-roll at most ${MAX_REROLLS} times per match`);
      const attempt = used + 1;
      const cost = REROLL_COSTS[used];
      const r = await applyGemTx(q, { userId, type: 'reroll', amount: -cost, key: `reroll:${matchId}:${attempt}`, matchId, note: `Dice re-roll #${attempt}` });
      return { attempt, cost, gems: r.balance };
    });
    await this.pushWallet([userId]);
    return res;
  }

  /** Give a re-roll back when it could not be applied (the game moved on meanwhile). */
  async refundReroll(matchId: string, userId: string, attempt: number, cost: number): Promise<void> {
    await this.ctx.db.tx((q) =>
      applyGemTx(q, { userId, type: 'reroll_refund', amount: cost, key: `reroll-refund:${matchId}:${attempt}`, matchId, note: 'Re-roll refunded' }),
    );
    await this.pushWallet([userId]);
  }

  /** After a restart no in-memory room survives: refund every open match. */
  async recover(): Promise<{ cancelled: number; abandoned: number }> {
    const open = (await this.ctx.db.query<{ id: string; status: string }>(`select id, status from matches where status in ('waiting', 'in_progress')`)).rows;
    let cancelled = 0;
    let abandoned = 0;
    for (const m of open) {
      if (m.status === 'waiting') {
        await this.cancel(m.id, 'server restart');
        cancelled++;
      } else {
        await this.abandon(m.id, 'server restart', true);
        abandoned++;
      }
    }
    return { cancelled, abandoned };
  }
}
