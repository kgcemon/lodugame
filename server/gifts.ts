import { randomUUID } from 'node:crypto';
import type { Router } from 'express';
import type { Db, Queryable } from './db';
import { h, rateLimit, type AppCtx } from './http';
import { forgetBoardSales } from './boards';
import { forgetDiceSales } from './dice';
import { applyGemTx } from './gems';
import * as v from './security';
import { HttpError } from './security';
import { applyWalletTx, walletSummary, type WalletTxRow } from './wallet';
import {
  DUPLICATE_REFUND_SHARE, GIFT_BOXES, GIFT_KINDS, GIFT_STATUSES, MAX_GIFT_COINS, MAX_GIFT_DIAMONDS, MAX_GIFT_EXPIRY_DAYS,
  MAX_GIFT_MESSAGE, MAX_GIFT_RECIPIENTS, currencyRarity, rewardStyle,
  type GiftBox, type GiftKind, type GiftStatus, type RewardEvent, type RewardKind, type RewardSource,
} from '../shared/gifts';

/**
 * Gifts from administrators. Sending writes one row per recipient plus its notification and
 * audit record; nothing is credited until the player claims. A claim is one transaction that
 * locks the gift row: it credits through the coin or diamond ledger (key gift:<id>) or the
 * inventory, and writes gift_claims, whose primary key allows one claim per gift, ever.
 */

/** A product an administrator can give: from the shop's catalogues, with today's price. */
export interface GiftProduct {
  id: string;
  name: string;
  rarity: string;
  price: number;
  /** What the shop charges for it (diamonds unless a board says coins). */
  currency: RewardKind;
}

export type GiftCatalogue = Record<'dice' | 'board' | 'frame', () => Promise<GiftProduct[]>>;

interface GiftRow {
  id: string;
  batch_id: string;
  sender_id: string | null;
  recipient_id: string;
  kind: GiftKind;
  item_id: string | null;
  item_name: string;
  quantity: number;
  rarity: string;
  box_style: GiftBox | null;
  message: string;
  status: GiftStatus;
  expires_at: string | null;
  created_at: string;
  opened_at: string | null;
  claimed_at: string | null;
  cancelled_at: string | null;
}

interface ClaimRow {
  granted_kind: string;
  granted_item: string | null;
  granted_amount: number;
  duplicate: boolean | number;
}

const COSMETICS = {
  dice: { table: 'user_dice_skins', col: 'skin_id' },
  board: { table: 'user_boards', col: 'board_id' },
  frame: { table: 'user_frames', col: 'frame_id' },
} as const;

const SENT_CHUNK = 500;
/** "Everyone" may have grown a little between the preview the administrator confirmed and the send. */
const allowedGrowth = (n: number) => Math.max(10, Math.ceil(n * 0.1));

export function rewardFromWallet(tx: WalletTxRow, source: RewardSource, matchId?: string): RewardEvent {
  const amount = Number(tx.amount);
  return {
    id: tx.id,
    source,
    kind: 'coins',
    amount,
    before: Number(tx.balance_before),
    after: Number(tx.balance_after),
    style: rewardStyle('coins', amount),
    ...(matchId ? { matchId } : {}),
  };
}

export function rewardFromGems(id: string, amount: number, after: number, source: RewardSource): RewardEvent {
  return { id, source, kind: 'diamonds', amount, before: after - amount, after, style: rewardStyle('diamonds', amount) };
}

// ---------------------------------------------------------------- what the player sees

function giftOut(g: GiftRow, claim?: ClaimRow | null) {
  // A box keeps its secret until it is opened.
  const hidden = g.box_style !== null && !g.opened_at;
  const box = g.box_style ? GIFT_BOXES.find((b) => b.id === g.box_style) ?? null : null;
  return {
    id: g.id,
    status: g.status,
    from: 'LudoX',
    message: g.message,
    box: box ? { id: box.id, label: box.label } : null,
    content: hidden ? null : { kind: g.kind, itemId: g.item_id, name: g.item_name, quantity: Number(g.quantity), rarity: g.rarity },
    received: claim
      ? {
          kind: claim.granted_kind,
          itemId: claim.granted_item,
          amount: Number(claim.granted_amount),
          duplicate: !!claim.duplicate,
        }
      : null,
    createdAt: g.created_at,
    expiresAt: g.expires_at,
    openedAt: g.opened_at,
    claimedAt: g.claimed_at,
  };
}

/** Gifts past their expiry stop being claimable; written down whenever somebody looks. */
async function expireDue(q: Queryable, recipient?: string): Promise<void> {
  const params: unknown[] = [new Date()];
  let who = '';
  if (recipient) {
    params.push(recipient);
    who = ' and recipient_id = $2';
  }
  await q.query(`update gifts set status = 'expired' where status in ('pending', 'opened') and expires_at is not null and expires_at <= $1${who}`, params);
}

export async function unclaimedGifts(q: Queryable, userId: string): Promise<number> {
  const r = await q.query<{ n: number }>(
    `select count(*)::int as n from gifts where recipient_id = $1 and status in ('pending', 'opened') and (expires_at is null or expires_at > $2)`,
    [userId, new Date()],
  );
  return Number(r.rows[0].n);
}

// ---------------------------------------------------------------- claiming

export interface ClaimResult {
  gift: ReturnType<typeof giftOut>;
  /** This call credited nothing: an earlier one already did. */
  duplicate: boolean;
  reward: RewardEvent | null;
}

async function claimOf(q: Queryable, giftId: string): Promise<ClaimRow | null> {
  return (await q.query<ClaimRow>('select granted_kind, granted_item, granted_amount, duplicate from gift_claims where gift_id = $1', [giftId])).rows[0] ?? null;
}

/**
 * Claim once. A repeat (a retried tap, a second phone) answers with the first claim and moves
 * nothing. A cosmetic the player already owns pays DUPLICATE_REFUND_SHARE of its shop price
 * instead (nothing when the shop gives it away), through the same ledger key.
 */
export async function claimGift(db: Db, catalogue: GiftCatalogue, giftId: string, userId: string): Promise<ClaimResult> {
  // The price is read outside the transaction: the catalogue caches, and may query the database.
  const peek = await db.query<GiftRow>('select * from gifts where id = $1 and recipient_id = $2', [giftId, userId]);
  if (!peek.rows.length) throw new HttpError(404, 'Gift not found');
  const kind = peek.rows[0].kind;
  const product = kind === 'coins' || kind === 'diamonds' ? null : (await catalogue[kind]()).find((p) => p.id === peek.rows[0].item_id) ?? null;

  const out = await db.tx(async (q) => {
    // Locks first: a claim, a second claim and a cancel of this gift run one after the other.
    const r = await q.query<GiftRow>('select * from gifts where id = $1 and recipient_id = $2 for update', [giftId, userId]);
    const g = r.rows[0];
    if (!g) throw new HttpError(404, 'Gift not found');
    if (g.status === 'claimed') return { gift: giftOut(g, await claimOf(q, g.id)), duplicate: true, reward: null };
    if (g.status === 'cancelled') throw new HttpError(410, 'This gift was cancelled', 'GIFT_CANCELLED');
    if (g.status === 'expired') throw new HttpError(410, 'This gift has expired', 'GIFT_EXPIRED');
    // Not thrown here: the refusal must not roll back recording that it expired.
    if (g.expires_at && new Date(g.expires_at).getTime() <= Date.now()) {
      await q.query(`update gifts set status = 'expired' where id = $1`, [g.id]);
      return null;
    }
    const key = `gift:${g.id}`;
    const note = `Gift: ${g.item_name}`;
    const by = g.sender_id;
    const pay = async (cur: RewardKind, amount: number): Promise<{ txId: string; reward: RewardEvent }> => {
      if (cur === 'coins') {
        const tx = (await applyWalletTx(q, { userId, type: 'gift', amount, key, note, createdBy: by })).tx;
        return { txId: tx.id, reward: rewardFromWallet(tx, 'gift') };
      }
      const tx = await applyGemTx(q, { userId, type: 'gift', amount, key, note, createdBy: by });
      return { txId: tx.id, reward: rewardFromGems(tx.id, amount, tx.balance, 'gift') };
    };
    let grantedKind: string = g.kind;
    let grantedItem: string | null = g.item_id;
    let grantedAmount = Number(g.quantity);
    let duplicateItem = false;
    let txId: string | null = null;
    let reward: RewardEvent | null = null;
    if (g.kind === 'coins' || g.kind === 'diamonds') {
      const paid = await pay(g.kind, grantedAmount);
      txId = paid.txId;
      reward = paid.reward;
    } else {
      const c = COSMETICS[g.kind];
      const added = await q.query(`insert into ${c.table} (user_id, ${c.col}, gem_tx_id) values ($1, $2, null) on conflict do nothing`, [userId, g.item_id]);
      if (added.rowCount < 1) {
        duplicateItem = true;
        grantedAmount = product ? Math.round(product.price * DUPLICATE_REFUND_SHARE) : 0;
        grantedKind = product?.currency ?? 'coins';
        grantedItem = null;
        if (grantedAmount > 0) {
          const paid = await pay(product!.currency, grantedAmount);
          txId = paid.txId;
          reward = paid.reward;
        }
      }
    }
    await q.query(
      `insert into gift_claims (gift_id, user_id, granted_kind, granted_item, granted_amount, duplicate, tx_id) values ($1, $2, $3, $4, $5, $6, $7)`,
      [g.id, userId, grantedKind, grantedItem, grantedAmount, duplicateItem, txId],
    );
    await q.query(`update gifts set status = 'claimed', claimed_at = now(), opened_at = coalesce(opened_at, now()) where id = $1`, [g.id]);
    await q.query(`insert into audit_logs (actor_id, action, target_user_id, details) values ($1, 'gift.claim', $1, $2::jsonb)`, [
      userId,
      JSON.stringify({ giftId: g.id, kind: grantedKind, item: grantedItem, amount: grantedAmount, duplicate: duplicateItem }),
    ]);
    const fresh = (await q.query<GiftRow>('select * from gifts where id = $1', [g.id])).rows[0];
    return { gift: giftOut(fresh, await claimOf(q, g.id)), duplicate: false, reward };
  });
  if (!out) throw new HttpError(410, 'This gift has expired', 'GIFT_EXPIRED');
  if (!out.duplicate && kind === 'dice') forgetDiceSales();
  if (!out.duplicate && kind === 'board') forgetBoardSales();
  return out;
}

// ---------------------------------------------------------------- sending

export interface GiftSpec {
  kind: GiftKind;
  itemId: string | null;
  itemName: string;
  quantity: number;
  rarity: string;
  box: GiftBox | null;
  message: string;
  expiresAt: Date | null;
}

/** The checked content of a gift: only real products, and sane amounts. */
export async function parseGift(b: Record<string, unknown>, catalogue: GiftCatalogue): Promise<GiftSpec> {
  const kind = b.kind as GiftKind;
  if (!GIFT_KINDS.includes(kind)) throw new HttpError(400, 'Unknown gift type');
  let itemId: string | null = null;
  let itemName: string;
  let quantity = 1;
  let rarity: string;
  if (kind === 'coins' || kind === 'diamonds') {
    quantity = v.int(b.quantity, 'Amount', 1, kind === 'coins' ? MAX_GIFT_COINS : MAX_GIFT_DIAMONDS);
    itemName = kind === 'coins' ? 'Coins' : 'Diamonds';
    rarity = currencyRarity(kind, quantity);
  } else {
    if (typeof b.itemId !== 'string') throw new HttpError(400, 'Choose a product');
    const product = (await catalogue[kind]()).find((p) => p.id === b.itemId);
    // What everybody already has for free can't be a gift.
    if (!product || product.price <= 0) throw new HttpError(400, 'That product cannot be gifted');
    itemId = product.id;
    itemName = product.name;
    rarity = product.rarity;
  }
  let box: GiftBox | null = null;
  if (b.box !== undefined && b.box !== null && b.box !== '') {
    if (!GIFT_BOXES.some((x) => x.id === b.box)) throw new HttpError(400, 'Unknown gift box');
    box = b.box as GiftBox;
  }
  const message = b.message === undefined || b.message === null ? '' : v.str(b.message, 'Message', 0, MAX_GIFT_MESSAGE);
  if (/[\u0000-\u0008\u000b-\u001f\u007f<>]/.test(message)) throw new HttpError(400, 'Message contains invalid characters');
  let expiresAt: Date | null = null;
  if (b.expiresAt !== undefined && b.expiresAt !== null && b.expiresAt !== '') {
    expiresAt = new Date(String(b.expiresAt));
    const left = expiresAt.getTime() - Date.now();
    if (Number.isNaN(left) || left < 60_000) throw new HttpError(400, 'The expiry must be in the future');
    if (left > MAX_GIFT_EXPIRY_DAYS * 86_400_000) throw new HttpError(400, `The expiry can be at most ${MAX_GIFT_EXPIRY_DAYS} days away`);
  }
  return { kind, itemId, itemName, quantity, rarity, box, message, expiresAt };
}

/** Who a send reaches: the chosen players, or every player who isn't suspended. */
async function recipients(db: Queryable, b: Record<string, unknown>): Promise<{ ids: string[]; all: boolean }> {
  if (b.all === true) {
    const rows = await db.query<{ id: string }>(`select id from users where suspended_at is null and role = 'player'`);
    return { ids: rows.rows.map((r) => r.id), all: true };
  }
  if (!Array.isArray(b.userIds) || !b.userIds.length) throw new HttpError(400, 'Choose at least one player');
  if (b.userIds.length > MAX_GIFT_RECIPIENTS) throw new HttpError(400, `Choose at most ${MAX_GIFT_RECIPIENTS} players, or send to everyone`);
  const ids = [...new Set(b.userIds.map((x) => v.uuid(x, 'player')))];
  const list = ids.map((_, i) => `$${i + 1}`).join(', ');
  const found = await db.query<{ id: string }>(`select id from users where suspended_at is null and id in (${list})`, ids);
  if (found.rows.length !== ids.length) throw new HttpError(400, 'A chosen player does not exist or is suspended');
  return { ids, all: false };
}

/** How many of these players already own this cosmetic (they'd get the refund instead). */
async function alreadyOwn(db: Queryable, spec: GiftSpec, ids: string[]): Promise<number> {
  if (spec.kind === 'coins' || spec.kind === 'diamonds' || !ids.length) return 0;
  const c = COSMETICS[spec.kind];
  const owners = await db.query<{ user_id: string }>(`select user_id from ${c.table} where ${c.col} = $1`, [spec.itemId]);
  const set = new Set(ids);
  return owners.rows.filter((r) => set.has(r.user_id)).length;
}

const noticeFor = (s: GiftSpec) => ({
  // Older apps show `message` as a plain system notice; the current one opens the gift by its id.
  message: '🎁 You received a gift from LudoX! Update the app to open it.',
  title: s.box ? `🎁 ${GIFT_BOXES.find((b) => b.id === s.box)!.label} from LudoX` : '🎁 A gift from LudoX',
});

async function sendChunk(db: Db, actor: string, batchId: string, spec: GiftSpec, ids: string[], ctx: AppCtx): Promise<number> {
  const made = ids.map((recipient) => ({ giftId: randomUUID(), noteId: randomUUID(), recipient }));
  const notice = noticeFor(spec);
  try {
    await db.tx(async (q) => {
      const giftVals: unknown[] = [];
      const giftRows = made.map((m, i) => {
        giftVals.push(m.giftId, batchId, actor, m.recipient, spec.kind, spec.itemId, spec.itemName, spec.quantity, spec.rarity, spec.box, spec.message, spec.expiresAt);
        const n = i * 12;
        return `(${Array.from({ length: 12 }, (_, k) => `$${n + k + 1}`).join(', ')})`;
      });
      await q.query(
        `insert into gifts (id, batch_id, sender_id, recipient_id, kind, item_id, item_name, quantity, rarity, box_style, message, expires_at) values ${giftRows.join(', ')}`,
        giftVals,
      );
      const noteVals: unknown[] = [];
      const noteRows = made.map((m, i) => {
        noteVals.push(m.noteId, m.recipient, 'system', JSON.stringify({ ...notice, giftId: m.giftId }));
        const n = i * 4;
        return `($${n + 1}, $${n + 2}, $${n + 3}, $${n + 4}::jsonb)`;
      });
      await q.query(`insert into notifications (id, user_id, type, payload) values ${noteRows.join(', ')}`, noteVals);
      const auditVals: unknown[] = [];
      const auditRows = made.map((m, i) => {
        auditVals.push(actor, 'gift.send', m.recipient, JSON.stringify({
          giftId: m.giftId, batchId, kind: spec.kind, item: spec.itemId, quantity: spec.quantity, box: spec.box, message: spec.message, expiresAt: spec.expiresAt,
        }));
        const n = i * 4;
        return `($${n + 1}, $${n + 2}, $${n + 3}, $${n + 4}::jsonb)`;
      });
      await q.query(`insert into audit_logs (actor_id, action, target_user_id, details) values ${auditRows.join(', ')}`, auditVals);
    });
  } catch (e) {
    if ((e as { code?: string }).code === '23505') throw new HttpError(409, 'This send is already in progress');
    throw e;
  }
  // Live, for whoever is online; everyone else finds it in their inbox when they return.
  const now = new Date().toISOString();
  for (const m of made) {
    if (!ctx.presence.isOnline(m.recipient)) continue;
    ctx.presence.emit(m.recipient, 'notify', { id: m.noteId, type: 'system', payload: { ...notice, giftId: m.giftId }, createdAt: now, readAt: null });
    ctx.presence.emit(m.recipient, 'gift:new', { giftId: m.giftId, title: notice.title });
  }
  return made.length;
}

/**
 * Send one gift to each player, in chunks of one transaction each. The request id makes the
 * whole send repeatable: after a failure or a double click, players already served are skipped.
 */
export async function sendGifts(ctx: AppCtx, actor: string, batchId: string, spec: GiftSpec, ids: string[]): Promise<{ sent: number; skipped: number }> {
  const done = await ctx.db.query<{ recipient_id: string }>('select recipient_id from gifts where batch_id = $1', [batchId]);
  const served = new Set(done.rows.map((r) => r.recipient_id));
  const todo = ids.filter((id) => !served.has(id));
  let sent = 0;
  for (let i = 0; i < todo.length; i += SENT_CHUNK) sent += await sendChunk(ctx.db, actor, batchId, spec, todo.slice(i, i + SENT_CHUNK), ctx);
  return { sent, skipped: ids.length - todo.length };
}

// ---------------------------------------------------------------- routes

export function playerGiftRoutes(r: Router, ctx: AppCtx, catalogue: GiftCatalogue): void {
  const { db, settings, presence } = ctx;
  const PAGE = 20;

  const load = async (id: string, userId: string) => {
    const g = (await db.query<GiftRow>('select * from gifts where id = $1 and recipient_id = $2', [id, userId])).rows[0];
    if (!g) throw new HttpError(404, 'Gift not found');
    return g;
  };

  /** The inbox, newest first, with how many gifts still wait for a claim (the badge). */
  r.get(
    '/gifts',
    rateLimit(120, 60_000, (req) => req.user!.id),
    h(async (req) => {
      const me = req.user!.id;
      await expireDue(db, me);
      const p = Math.min(1000, Math.max(1, Number.parseInt(String(req.query.page ?? '1'), 10) || 1));
      const rows = (await db.query<GiftRow>('select * from gifts where recipient_id = $1 order by created_at desc, id desc limit $2 offset $3', [me, PAGE, (p - 1) * PAGE])).rows;
      const claimed = rows.filter((g) => g.status === 'claimed');
      const claims = new Map<string, ClaimRow>();
      for (const g of claimed) {
        const c = await claimOf(db, g.id);
        if (c) claims.set(g.id, c);
      }
      const total = (await db.query<{ n: number }>('select count(*)::int as n from gifts where recipient_id = $1', [me])).rows[0].n;
      return { ok: true, items: rows.map((g) => giftOut(g, claims.get(g.id))), unclaimed: await unclaimedGifts(db, me), total: Number(total), page: p, pageSize: PAGE };
    }),
  );

  r.get(
    '/gifts/:id',
    rateLimit(120, 60_000, (req) => req.user!.id),
    h(async (req) => {
      const me = req.user!.id;
      await expireDue(db, me);
      const g = await load(v.uuid(req.params.id, 'gift'), me);
      return { ok: true, gift: giftOut(g, await claimOf(db, g.id)) };
    }),
  );

  /** PENDING → OPENED: the box may reveal what is inside. Opening again changes nothing. */
  r.post(
    '/gifts/:id/open',
    rateLimit(60, 60_000, (req) => req.user!.id),
    h(async (req) => {
      const me = req.user!.id;
      const id = v.uuid(req.params.id, 'gift');
      await expireDue(db, me);
      await db.query(`update gifts set status = 'opened', opened_at = now() where id = $1 and recipient_id = $2 and status = 'pending'`, [id, me]);
      const g = await load(id, me);
      return { ok: true, gift: giftOut(g, await claimOf(db, g.id)) };
    }),
  );

  r.post(
    '/gifts/:id/claim',
    rateLimit(30, 60_000, (req) => req.user!.id),
    h(async (req) => {
      const me = req.user!.id;
      const res = await claimGift(db, catalogue, v.uuid(req.params.id, 'gift'), me);
      const wallet = await walletSummary(db, me, settings.get().dailyBonus);
      if (!res.duplicate) {
        presence.emit(me, 'wallet', wallet);
        presence.emit(me, 'gift:update', { giftId: res.gift.id, status: res.gift.status });
      }
      return { ok: true, ...res, wallet, unclaimed: await unclaimedGifts(db, me) };
    }),
  );
}

export function adminGiftRoutes(admin: Router, ctx: AppCtx, catalogue: GiftCatalogue): void {
  const { db, presence } = ctx;
  const PAGE = 20;
  const REQUEST_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

  /** What can be given right now, and how many players "everyone" is. */
  admin.get(
    '/gifts/options',
    h(async () => {
      const eligible = (await db.query<{ n: number }>(`select count(*)::int as n from users where suspended_at is null and role = 'player'`)).rows[0].n;
      const free = (list: GiftProduct[]) => list.filter((p) => p.price > 0);
      return {
        ok: true,
        eligible: Number(eligible),
        boxes: GIFT_BOXES,
        limits: { coins: MAX_GIFT_COINS, diamonds: MAX_GIFT_DIAMONDS, message: MAX_GIFT_MESSAGE, recipients: MAX_GIFT_RECIPIENTS, expiryDays: MAX_GIFT_EXPIRY_DAYS },
        duplicateRefundShare: DUPLICATE_REFUND_SHARE,
        products: { dice: free(await catalogue.dice()), board: free(await catalogue.board()), frame: free(await catalogue.frame()) },
      };
    }),
  );

  /** What a send would do, without doing it. */
  admin.post(
    '/gifts/preview',
    rateLimit(120, 60_000, (req) => req.user!.id),
    h(async (req) => {
      const b = (req.body ?? {}) as Record<string, unknown>;
      const spec = await parseGift(b, catalogue);
      const { ids } = await recipients(db, b);
      return {
        ok: true,
        recipients: ids.length,
        alreadyOwn: await alreadyOwn(db, spec, ids),
        gift: { kind: spec.kind, itemId: spec.itemId, name: spec.itemName, quantity: spec.quantity, rarity: spec.rarity, box: spec.box, message: spec.message, expiresAt: spec.expiresAt },
      };
    }),
  );

  admin.post(
    '/gifts',
    rateLimit(30, 60_000, (req) => req.user!.id),
    h(async (req) => {
      const b = (req.body ?? {}) as Record<string, unknown>;
      const actor = req.user!.id;
      if (typeof b.requestId !== 'string' || !REQUEST_ID.test(b.requestId)) throw new HttpError(400, 'A request id is required');
      const batchId = b.requestId.toLowerCase();
      const spec = await parseGift(b, catalogue);
      const { ids, all } = await recipients(db, b);
      if (!ids.length) throw new HttpError(400, 'There is nobody to send this to');
      // Bulk sends must be confirmed on purpose, not only by the panel's dialog.
      if (ids.length > 1 && b.confirm !== true) throw new HttpError(400, 'Confirm sending to several players', 'CONFIRM_REQUIRED');
      if (all) {
        const c = b.confirmCount;
        if (typeof c !== 'number' || c < 1 || ids.length > c + allowedGrowth(c)) {
          throw new HttpError(409, 'The number of players changed: check the preview and confirm again', 'CONFIRM_REQUIRED');
        }
      }
      const res = await sendGifts(ctx, actor, batchId, spec, ids);
      if (all || ids.length > 1) {
        await db.query(`insert into audit_logs (actor_id, action, details) values ($1, 'gift.send_bulk', $2::jsonb)`, [
          actor,
          JSON.stringify({ batchId, all, recipients: ids.length, sent: res.sent, kind: spec.kind, item: spec.itemId, quantity: spec.quantity, box: spec.box }),
        ]);
      }
      return { ok: true, batchId, ...res };
    }),
  );

  /** Gift history: filter by status (pending, claimed, expired...) or type (coins, diamonds, cosmetics). */
  admin.get(
    '/gifts',
    h(async (req) => {
      await expireDue(db);
      const p = Math.min(1000, Math.max(1, Number.parseInt(String(req.query.page ?? '1'), 10) || 1));
      const conds: string[] = [];
      const params: unknown[] = [PAGE, (p - 1) * PAGE];
      if (GIFT_STATUSES.includes(req.query.status as GiftStatus)) {
        params.push(req.query.status);
        conds.push(`g.status = $${params.length}`);
      }
      if (req.query.type === 'coins' || req.query.type === 'diamonds') {
        params.push(req.query.type);
        conds.push(`g.kind = $${params.length}`);
      } else if (req.query.type === 'cosmetics') {
        conds.push(`g.kind in ('dice', 'board', 'frame')`);
      }
      const q = String(req.query.q ?? '').trim().slice(0, 100);
      if (q) {
        params.push(`%${q.replace(/[\\%_]/g, (c) => `\\${c}`)}%`);
        conds.push(`(t.username ilike $${params.length} or t.public_id ilike $${params.length})`);
      }
      const where = conds.length ? `where ${conds.join(' and ')}` : '';
      const rows = (
        await db.query(
          `select g.id, g.kind, g.item_id, g.item_name, g.quantity, g.rarity, g.box_style, g.message, g.status, g.created_at, g.expires_at, g.opened_at, g.claimed_at, g.cancelled_at,
                  t.username as recipient, t.public_id as recipient_public_id, s.username as sender,
                  c.duplicate, c.granted_kind, c.granted_amount
           from gifts g join profiles t on t.user_id = g.recipient_id left join profiles s on s.user_id = g.sender_id
           left join gift_claims c on c.gift_id = g.id
           ${where} order by g.created_at desc, g.id desc limit $1 offset $2`,
          params,
        )
      ).rows;
      return { ok: true, page: p, pageSize: PAGE, items: rows };
    }),
  );

  /** Take back a gift that hasn't been claimed. It stays in the history as CANCELLED. */
  admin.post(
    '/gifts/:id/cancel',
    rateLimit(60, 60_000, (req) => req.user!.id),
    h(async (req) => {
      const id = v.uuid(req.params.id, 'gift');
      const recipient = await db.tx(async (q) => {
        const g = (await q.query<GiftRow>('select * from gifts where id = $1 for update', [id])).rows[0];
        if (!g) throw new HttpError(404, 'Gift not found');
        if (g.status !== 'pending' && g.status !== 'opened') throw new HttpError(409, `This gift is already ${g.status}`);
        await q.query(`update gifts set status = 'cancelled', cancelled_at = now() where id = $1`, [id]);
        await q.query(`insert into audit_logs (actor_id, action, target_user_id, details) values ($1, 'gift.cancel', $2, $3::jsonb)`, [
          req.user!.id,
          g.recipient_id,
          JSON.stringify({ giftId: id, kind: g.kind, item: g.item_id, quantity: g.quantity }),
        ]);
        return g.recipient_id;
      });
      presence.emit(recipient, 'gift:update', { giftId: id, status: 'cancelled' });
      return { ok: true };
    }),
  );
}
