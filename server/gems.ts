import { randomUUID } from 'node:crypto';
import type { Queryable } from './db';
import { HttpError } from './security';

/**
 * Gems: a premium currency bought through Google Play and spent on dice re-rolls.
 * Every change is one call to applyGemTx() inside a database transaction: it locks the
 * wallet row, checks the idempotency key, updates the balance (a CHECK keeps it >= 0)
 * and writes the ledger row. Retrying with the same key never moves gems twice.
 */

export type GemTxType = 'purchase' | 'reroll' | 'reroll_refund' | 'admin_adjustment' | 'skin_purchase' | 'daily_spin' | 'gift' | 'player_gift';

/** Re-roll prices within one match: the first costs 5, then 15, then 45. */
export const REROLL_COSTS = [5, 15, 45];
export const MAX_REROLLS = REROLL_COSTS.length;

/** Google Play in-app products (consumables) and the gems each grants. */
export const GEM_PACKS: { productId: string; gems: number }[] = [
  { productId: 'gems_50', gems: 50 },
  { productId: 'gems_150', gems: 150 },
  { productId: 'gems_500', gems: 500 },
];

export const NOT_ENOUGH_GEMS = 'Not enough diamonds! Buy more in the diamond shop.';

export interface GemTxInput {
  userId: string;
  type: GemTxType;
  /** Positive credits, negative debits. */
  amount: number;
  key: string;
  matchId?: string | null;
  productId?: string | null;
  purchaseToken?: string | null;
  note?: string | null;
  createdBy?: string | null;
}

export async function applyGemTx(q: Queryable, t: GemTxInput): Promise<{ balance: number; duplicate: boolean; id: string }> {
  if (!Number.isSafeInteger(t.amount) || t.amount === 0) throw new Error('Invalid diamond amount');
  const w = await q.query<{ gems: number }>('select gems from wallets where user_id = $1 for update', [t.userId]);
  if (!w.rows.length) throw new HttpError(404, 'Wallet not found');
  const existing = await q.query<{ id: string; balance_after: number }>(
    'select id, balance_after from gem_transactions where user_id = $1 and idempotency_key = $2',
    [t.userId, t.key],
  );
  if (existing.rows.length) return { balance: Number(w.rows[0].gems), duplicate: true, id: existing.rows[0].id };

  const before = Number(w.rows[0].gems);
  const after = before + t.amount;
  if (after < 0) throw new HttpError(402, NOT_ENOUGH_GEMS, 'NOT_ENOUGH_GEMS');
  await q.query('update wallets set gems = $2, updated_at = now() where user_id = $1', [t.userId, after]);
  const id = randomUUID();
  await q.query(
    `insert into gem_transactions (id, user_id, type, amount, balance_before, balance_after, match_id, idempotency_key, product_id, purchase_token, note, created_by)
     values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)`,
    [id, t.userId, t.type, t.amount, before, after, t.matchId ?? null, t.key, t.productId ?? null, t.purchaseToken ?? null, t.note ?? null, t.createdBy ?? null],
  );
  return { balance: after, duplicate: false, id };
}

export async function gemBalance(q: Queryable, userId: string): Promise<number> {
  const r = await q.query<{ gems: number }>('select gems from wallets where user_id = $1', [userId]);
  return r.rows.length ? Number(r.rows[0].gems) : 0;
}

/** How many re-rolls this user has already paid for in this match (from the ledger). */
export async function rerollsUsed(q: Queryable, userId: string, matchId: string): Promise<number> {
  const r = await q.query<{ n: number }>(
    `select count(*)::int as n from gem_transactions where user_id = $1 and match_id = $2 and type = 'reroll'`,
    [userId, matchId],
  );
  const refunded = await q.query<{ n: number }>(
    `select count(*)::int as n from gem_transactions where user_id = $1 and match_id = $2 and type = 'reroll_refund'`,
    [userId, matchId],
  );
  return Number(r.rows[0].n) - Number(refunded.rows[0].n);
}
