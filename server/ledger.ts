import { randomUUID } from 'node:crypto';
import type { Db, Queryable } from './db';
import { notify, type Presence } from './presence';
import { HttpError } from './security';
import type { MatchMode, Settings } from './settings';
import { applyWalletTx, walletSummary } from './wallet';

/**
 * Money side of a match. Each step is one database transaction that locks the
 * match row first, so concurrent joins/leaves/settlements serialise and every
 * coin movement is idempotent:
 *
 *   entry   key entry:<match>:<attempt>    (deducted when a player takes a seat)
 *   refund  key refund:<match>:<attempt>   (leaving before start, cancellations, server faults)
 *   reward  key reward:<match>             + match_settlements primary key (exactly once)
 *
 * REFUND POLICY
 *   - Leaving a room (or being removed / timing out) before the match starts: full refund.
 *   - Room cancelled before start (host left everyone, creation failure, empty room): full refund.
 *   - Server restart/crash while waiting or in progress: full refund to everyone still in the
 *     match (not the players' fault); players who had already forfeited are not refunded.
 *   - Leaving or disconnecting after the match started: no refund (a bot plays the seat).
 *   - Every human leaving a started match: abandoned, no refunds.
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

export interface SettleResult {
  winnerUserId: string | null;
  payout: number;
  pot: number;
  house: number;
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

  async start(matchId: string, seats: { userId: string; color: string }[]): Promise<void> {
    await this.ctx.db.tx(async (q) => {
      const m = await lockMatch(q, matchId);
      if (m.status !== 'waiting') throw new HttpError(409, 'Match cannot be started');
      for (const s of seats) {
        const r = await q.query(
          `update match_players set status = 'playing', color = $3 where match_id = $1 and user_id = $2 and status = 'joined'`,
          [matchId, s.userId, s.color],
        );
        if (!r.rowCount) throw new HttpError(409, 'A player is not properly seated');
      }
      await q.query(`update matches set status = 'in_progress', started_at = now() where id = $1`, [matchId]);
    });
  }

  async forfeit(matchId: string, userId: string): Promise<void> {
    await this.ctx.db.query(
      `update match_players set status = 'forfeited', left_at = now() where match_id = $1 and user_id = $2 and status = 'playing'`,
      [matchId, userId],
    );
  }

  /**
   * Settle a finished match exactly once. `ranking` lists finishing positions
   * as user ids (null = a bot seat). The winner gets multiplier × entry fee.
   */
  async settle(matchId: string, ranking: (string | null)[], winnerColor: string): Promise<SettleResult | null> {
    const res = await this.ctx.db.tx(async (q) => {
      const m = await lockMatch(q, matchId);
      if (m.status !== 'in_progress') return null;
      const players = (await q.query<{ user_id: string; status: string; entry_paid: number; refunded: number }>(
        'select user_id, status, entry_paid, refunded from match_players where match_id = $1',
        [matchId],
      )).rows;
      const pot = players.reduce((a, p) => a + (p.entry_paid - p.refunded), 0);
      const winner = ranking[0] ?? null;
      const winnerRow = winner ? players.find((p) => p.user_id === winner && p.status === 'playing') : undefined;
      const payout = winnerRow ? Math.round(m.entry_fee * m.payout_multiplier) : 0;
      const policy = `winner_total_payout = ${m.payout_multiplier} x entry_fee; unpaid remainder retained by house`;

      const ins = await q.query(
        `insert into match_settlements (match_id, winner_user_id, total_pot, payout, house_amount, policy)
         values ($1, $2, $3, $4, $5, $6) on conflict (match_id) do nothing`,
        [matchId, winnerRow ? winner : null, pot, payout, pot - payout, policy],
      );
      if (!ins.rowCount) return null; // already settled

      if (payout > 0) {
        await applyWalletTx(q, { userId: winner!, type: 'match_reward', amount: payout, key: `reward:${matchId}`, matchId, note: 'Match win payout' });
      }
      for (let i = 0; i < ranking.length; i++) {
        const uid = ranking[i];
        if (!uid) continue;
        await q.query(
          `update match_players set "rank" = $3, payout = case when $4 then $5::bigint else 0 end,
                  status = case when status = 'playing' then 'finished' else status end
           where match_id = $1 and user_id = $2`,
          [matchId, uid, i + 1, uid === winner && !!winnerRow, payout],
        );
      }
      await q.query(
        `update matches set status = 'completed', ended_at = now(), winner_user_id = $2, winner_color = $3, end_reason = 'finished' where id = $1`,
        [matchId, winnerRow ? winner : null, winnerColor],
      );
      return { winnerUserId: winnerRow ? winner : null, payout, pot, house: pot - payout, players: players.map((p) => p.user_id) };
    });
    if (!res) return null;
    await this.pushWallet(res.players);
    for (const uid of res.players) {
      const won = uid === res.winnerUserId;
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
