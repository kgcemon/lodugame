import { randomInt } from 'node:crypto';
import type { Db, Queryable } from './db';
import { applyGemTx } from './gems';
import { HttpError } from './security';

/**
 * Daily spin: once per UTC day every player spins a wheel of ten slices worth 1 to 10
 * diamonds. The server picks the slice (small prizes are likelier) and pays it in the same
 * transaction; the date in the idempotency key makes a second spin that day impossible.
 */

/** The wheel's slices, clockwise from the top. Each prize appears once. */
export const SPIN_PRIZES = [1, 6, 2, 8, 3, 10, 4, 7, 5, 9];

/** Relative chance of each prize (index = diamonds - 1): 1 is 12× likelier than 10. */
const WEIGHTS = [24, 20, 16, 12, 9, 7, 5, 3, 2, 2];

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function nextUtcMidnight(): string {
  const d = new Date();
  d.setUTCHours(24, 0, 0, 0);
  return d.toISOString();
}

function pickPrize(): number {
  let r = randomInt(WEIGHTS.reduce((a, b) => a + b, 0));
  for (let i = 0; i < WEIGHTS.length; i++) {
    r -= WEIGHTS[i];
    if (r < 0) return i + 1;
  }
  return 1;
}

export interface SpinState {
  available: boolean;
  prizes: number[];
  /** Diamonds won today, if the player already spun. */
  wonToday: number | null;
  nextAt: string;
}

export async function spinState(q: Queryable, userId: string): Promise<SpinState> {
  const r = await q.query<{ amount: number }>('select amount from gem_transactions where user_id = $1 and idempotency_key = $2', [userId, `spin:${today()}`]);
  const won = r.rows.length ? Number(r.rows[0].amount) : null;
  return { available: won === null, prizes: SPIN_PRIZES, wonToday: won, nextAt: nextUtcMidnight() };
}

/** Spin once for today: returns the prize and the slice it landed on. */
export async function spin(db: Db, userId: string): Promise<{ prize: number; index: number; gems: number; txId: string }> {
  const prize = pickPrize();
  const r = await db.tx((q) => applyGemTx(q, { userId, type: 'daily_spin', amount: prize, key: `spin:${today()}`, note: `Daily spin: ${prize} diamonds` }));
  if (r.duplicate) throw new HttpError(409, 'You already spun today. Come back tomorrow!', 'ALREADY_SPUN');
  return { prize, index: SPIN_PRIZES.indexOf(prize), gems: r.balance, txId: r.id };
}
