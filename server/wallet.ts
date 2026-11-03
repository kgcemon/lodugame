import { randomUUID } from 'node:crypto';
import type { Db, Queryable } from './db';
import { HttpError } from './security';

/**
 * The coin ledger. Every balance change is one call to applyWalletTx() inside
 * a database transaction: it locks the wallet row, checks the idempotency key,
 * updates the balance (which a CHECK constraint keeps >= 0) and writes the
 * matching ledger row. Retrying with the same key never moves coins twice.
 */

export type TxType =
  | 'welcome_bonus'
  | 'match_entry'
  | 'match_reward'
  | 'match_refund'
  | 'daily_bonus'
  | 'referral_reward'
  | 'admin_adjustment'
  /** Coins spent in the shop (boards). */
  | 'shop_purchase';

export interface WalletTxRow {
  id: string;
  user_id: string;
  type: TxType;
  amount: number;
  balance_before: number;
  balance_after: number;
  match_id: string | null;
  idempotency_key: string;
  note: string | null;
  created_at: string;
}

export const INSUFFICIENT = 'Insufficient Coins! Play more matches or collect your free bonus.';

export interface WalletTxInput {
  userId: string;
  type: TxType;
  /** Positive credits, negative debits. */
  amount: number;
  /** Unique per user for this logical operation, e.g. `entry:<match>:<attempt>`. */
  key: string;
  matchId?: string | null;
  note?: string | null;
  createdBy?: string | null;
}

export async function applyWalletTx(q: Queryable, t: WalletTxInput): Promise<{ tx: WalletTxRow; duplicate: boolean }> {
  if (!Number.isSafeInteger(t.amount) || t.amount === 0) throw new Error('Invalid wallet amount');
  // Lock first, then check the key: concurrent retries of the same operation serialise here.
  const w = await q.query<{ balance: number }>('select balance from wallets where user_id = $1 for update', [t.userId]);
  if (!w.rows.length) throw new HttpError(404, 'Wallet not found');
  const existing = await q.query<WalletTxRow>('select * from wallet_transactions where user_id = $1 and idempotency_key = $2', [
    t.userId,
    t.key,
  ]);
  if (existing.rows.length) return { tx: existing.rows[0], duplicate: true };

  const before = w.rows[0].balance;
  const after = before + t.amount;
  if (after < 0) throw new HttpError(402, INSUFFICIENT, 'INSUFFICIENT_COINS');

  // Refunds give back spending; other credits count as earnings; debits count as spending.
  const earned = t.amount > 0 && t.type !== 'match_refund' ? t.amount : 0;
  const spentDelta = t.amount < 0 ? -t.amount : t.type === 'match_refund' ? -t.amount : 0;
  await q.query(
    `update wallets set balance = $2, total_earned = total_earned + $3,
            total_spent = greatest(0, total_spent + $4), updated_at = now()
     where user_id = $1`,
    [t.userId, after, earned, spentDelta],
  );
  // Ids are generated here (not by the database) so this works without `returning` on MySQL.
  const id = randomUUID();
  await q.query(
    `insert into wallet_transactions (id, user_id, type, amount, balance_before, balance_after, match_id, idempotency_key, note, created_by)
     values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)`,
    [id, t.userId, t.type, t.amount, before, after, t.matchId ?? null, t.key, t.note ?? null, t.createdBy ?? null],
  );
  const ins = await q.query<WalletTxRow>('select * from wallet_transactions where id = $1', [id]);
  return { tx: ins.rows[0], duplicate: false };
}

export interface WalletSummary {
  balance: number;
  gems: number;
  totalEarned: number;
  totalSpent: number;
  dailyBonus: { amount: number; claimedToday: boolean; nextAt: string };
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function nextUtcMidnight(): string {
  const d = new Date();
  d.setUTCHours(24, 0, 0, 0);
  return d.toISOString();
}

export async function walletSummary(db: Queryable, userId: string, dailyAmount: number): Promise<WalletSummary> {
  const w = await db.query<{ balance: number; gems: number; total_earned: number; total_spent: number }>(
    'select balance, gems, total_earned, total_spent from wallets where user_id = $1',
    [userId],
  );
  if (!w.rows.length) throw new HttpError(404, 'Wallet not found');
  const claimed = await db.query('select 1 from wallet_transactions where user_id = $1 and idempotency_key = $2', [userId, `daily:${today()}`]);
  return {
    balance: w.rows[0].balance,
    gems: Number(w.rows[0].gems),
    totalEarned: w.rows[0].total_earned,
    totalSpent: w.rows[0].total_spent,
    dailyBonus: { amount: dailyAmount, claimedToday: claimed.rows.length > 0, nextAt: nextUtcMidnight() },
  };
}

/** Once per UTC day; the date in the idempotency key makes double claims impossible. */
export async function claimDailyBonus(db: Db, userId: string, amount: number): Promise<WalletTxRow> {
  if (amount <= 0) throw new HttpError(400, 'The daily bonus is currently disabled');
  return db.tx(async (q) => {
    const r = await applyWalletTx(q, { userId, type: 'daily_bonus', amount, key: `daily:${today()}`, note: 'Daily bonus' });
    if (r.duplicate) throw new HttpError(409, 'You already collected today’s bonus. Come back tomorrow!');
    return r.tx;
  });
}

export async function listTransactions(
  db: Queryable,
  userId: string,
  opts: { limit: number; offset: number; type?: TxType },
): Promise<{ items: WalletTxRow[]; total: number }> {
  const params: unknown[] = [userId];
  let where = 'user_id = $1';
  if (opts.type) {
    params.push(opts.type);
    where += ` and type = $${params.length}`;
  }
  const total = (await db.query<{ n: number }>(`select count(*)::int as n from wallet_transactions where ${where}`, params)).rows[0].n;
  params.push(opts.limit, opts.offset);
  const items = (
    await db.query<WalletTxRow>(
      `select * from wallet_transactions where ${where} order by created_at desc, id desc limit $${params.length - 1} offset $${params.length}`,
      params,
    )
  ).rows;
  return { items, total };
}
