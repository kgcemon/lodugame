import { randomUUID } from 'node:crypto';
import type { Router } from 'express';
import {
  GIFT_CATEGORIES, GIFT_CURRENCIES, GIFT_MODELS, GIFT_PARTICLES, GIFT_RARITIES, GIFT_RECEIVE_ANIMS, GIFT_SEND_ANIMS,
  MAX_DISPLAYED_GIFTS, MAX_GIFT_PRICE, type GiftCurrency, type GiftProductView, type GiftRarity, type MissedGift,
} from '../shared/playergifts';
import type { Db, Queryable } from './db';
import { applyGemTx } from './gems';
import { h, rateLimit, type AppCtx } from './http';
import * as v from './security';
import { HttpError } from './security';
import { applyWalletTx } from './wallet';

/**
 * Gifts players send each other in a match. One transaction locks the sender's wallet, checks the
 * retry key, the product and that both players are in the match, charges the official price through
 * the coin or diamond ledger (their rows are the payment record, key player-gift:<key>) and writes
 * player_gifts, which is the gift, its delivery and the recipient's collection in one row. Nothing
 * here touches the recipient's balance.
 */

interface ProductRow {
  id: string;
  name: string;
  currency: GiftCurrency;
  price: number | string;
  category: string;
  rarity: GiftRarity;
  model: string;
  send_anim: string;
  receive_anim: string;
  particle: string;
  enabled: boolean | number;
  featured: boolean | number;
  event_name: string | null;
  available_until: string | Date | null;
  sort_order: number;
}

export interface PlayerGiftRow {
  id: string;
  sender_id: string | null;
  recipient_id: string;
  product_id: string;
  name: string;
  currency: GiftCurrency;
  price: number | string;
  rarity: GiftRarity;
  model: string;
  send_anim: string;
  receive_anim: string;
  particle: string;
  match_id: string | null;
  created_at: string | Date;
  seen_at: string | Date | null;
  displayed: boolean | number;
  reaction: string | null;
}

const KEY = /^[A-Za-z0-9-]{8,64}$/;

const iso = (d: string | Date | null) => (d ? new Date(d).toISOString() : null);

function isAvailable(p: ProductRow, now = Date.now()): boolean {
  return !!p.enabled && (!p.available_until || new Date(p.available_until).getTime() > now);
}

function productOut(p: ProductRow): GiftProductView {
  return {
    id: p.id,
    name: p.name,
    currency: p.currency,
    price: Number(p.price),
    category: p.category,
    rarity: p.rarity,
    model: p.model,
    sendAnim: p.send_anim,
    receiveAnim: p.receive_anim,
    particle: p.particle,
    featured: !!p.featured,
    eventName: p.event_name,
    availableUntil: iso(p.available_until),
  };
}

export interface SendGift {
  matchId: string;
  senderId: string;
  recipientId: string;
  productId: string;
  key: string;
}

/**
 * Charge and record a gift, all or nothing. Asking again with the same key changes nothing and
 * answers with the gift already sent (`duplicate`), whatever else the retry asks for.
 */
export async function sendPlayerGift(db: Db, s: SendGift): Promise<{ gift: PlayerGiftRow; duplicate: boolean; balance: number }> {
  if (!KEY.test(s.key)) throw new HttpError(400, 'Bad request');
  return db.tx(async (q) => {
    // Lock first: concurrent retries of the same key, in either currency, wait here.
    const w = await q.query<{ balance: number; gems: number }>('select balance, gems from wallets where user_id = $1 for update', [s.senderId]);
    if (!w.rows.length) throw new HttpError(404, 'Wallet not found');
    const seen = await q.query<PlayerGiftRow>('select * from player_gifts where sender_id = $1 and idempotency_key = $2', [s.senderId, s.key]);
    if (seen.rows.length) {
      const g = seen.rows[0];
      return { gift: g, duplicate: true, balance: Number(g.currency === 'coins' ? w.rows[0].balance : w.rows[0].gems) };
    }

    const p = (await q.query<ProductRow>('select * from gift_products where id = $1', [s.productId])).rows[0];
    if (!p || !isAvailable(p)) throw new HttpError(404, 'This gift is not available');
    const m = (await q.query<{ status: string }>('select status from matches where id = $1', [s.matchId])).rows[0];
    if (m?.status !== 'in_progress') throw new HttpError(409, 'Gifts can only be sent during a match');
    const seats = await q.query<{ user_id: string }>(
      `select user_id from match_players where match_id = $1 and status = 'playing' and user_id in ($2, $3)`,
      [s.matchId, s.senderId, s.recipientId],
    );
    if (s.senderId === s.recipientId || seats.rows.length !== 2) throw new HttpError(409, 'That player cannot receive a gift');

    const price = Number(p.price);
    const ledgerKey = `player-gift:${s.key}`;
    const note = `Gift: ${p.name}`;
    let balance: number;
    if (p.currency === 'coins') {
      balance = Number((await applyWalletTx(q, { userId: s.senderId, type: 'player_gift', amount: -price, key: ledgerKey, matchId: s.matchId, note })).tx.balance_after);
    } else {
      balance = (await applyGemTx(q, { userId: s.senderId, type: 'player_gift', amount: -price, key: ledgerKey, matchId: s.matchId, note })).balance;
    }
    const id = randomUUID();
    await q.query(
      `insert into player_gifts (id, sender_id, recipient_id, product_id, name, currency, price, rarity, model, send_anim, receive_anim, particle, match_id, idempotency_key)
       values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)`,
      [id, s.senderId, s.recipientId, p.id, p.name, p.currency, price, p.rarity, p.model, p.send_anim, p.receive_anim, p.particle, s.matchId, s.key],
    );
    const gift = (await q.query<PlayerGiftRow>('select * from player_gifts where id = $1', [id])).rows[0];
    return { gift, duplicate: false, balance };
  });
}

/** Gifts the recipient hasn't seen yet: they get a notice when they come back. */
export async function unseenGifts(db: Queryable, userId: string): Promise<MissedGift[]> {
  const rows = (await db.query<{ id: string; name: string; sender: string | null }>(
    `select g.id, g.name, s.username as sender from player_gifts g left join profiles s on s.user_id = g.sender_id
     where g.recipient_id = $1 and g.seen_at is null order by g.created_at desc limit 20`,
    [userId],
  )).rows;
  return rows.map((r) => ({ id: r.id, name: r.name, from: r.sender ?? 'A player' }));
}

export async function markGiftsSeen(db: Queryable, userId: string, ids: string[]): Promise<void> {
  for (const id of ids.slice(0, 20)) {
    await db.query('update player_gifts set seen_at = now() where id = $1 and recipient_id = $2 and seen_at is null', [id, userId]);
  }
}

/** The recipient thanks the sender, once. Returns who to tell, or null if there's nothing to do. */
export async function reactToGift(db: Db, userId: string, giftId: string, reaction: string): Promise<{ senderId: string | null; name: string } | null> {
  return db.tx(async (q) => {
    const g = (await q.query<{ sender_id: string | null; name: string; reaction: string | null }>(
      'select sender_id, name, reaction from player_gifts where id = $1 and recipient_id = $2 for update',
      [giftId, userId],
    )).rows[0];
    if (!g) throw new HttpError(404, 'Gift not found');
    if (g.reaction) return null;
    await q.query('update player_gifts set reaction = $2, seen_at = coalesce(seen_at, now()) where id = $1', [giftId, reaction]);
    return { senderId: g.sender_id, name: g.name };
  });
}

/** The collectibles a player chose to show on their profile. */
export async function displayedGifts(db: Queryable, userId: string): Promise<{ id: string; name: string; model: string; rarity: string; particle: string; receiveAnim: string }[]> {
  const rows = (await db.query<PlayerGiftRow>(
    'select * from player_gifts where recipient_id = $1 and displayed = true order by created_at desc limit ' + MAX_DISPLAYED_GIFTS,
    [userId],
  )).rows;
  return rows.map((g) => ({ id: g.id, name: g.name, model: g.model, rarity: g.rarity, particle: g.particle, receiveAnim: g.receive_anim }));
}

type HistoryRow = PlayerGiftRow & { other_name: string | null; other_public: string | null; other_hue: number | null; other_avatar: string | null };

function giftOut(g: HistoryRow, dir: 'sent' | 'received') {
  return {
    id: g.id,
    productId: g.product_id,
    name: g.name,
    rarity: g.rarity,
    model: g.model,
    sendAnim: g.send_anim,
    receiveAnim: g.receive_anim,
    particle: g.particle,
    createdAt: iso(g.created_at),
    player: g.other_name ? { username: g.other_name, publicId: g.other_public, avatarHue: g.other_hue, avatarUrl: g.other_avatar } : null,
    reaction: g.reaction,
    // What was paid is the sender's business.
    ...(dir === 'sent' ? { currency: g.currency, price: Number(g.price) } : { displayed: !!g.displayed, seen: !!g.seen_at }),
  };
}

export function playerGiftApi(r: Router, ctx: AppCtx): void {
  const { db } = ctx;
  const PAGE = 30;
  const limited = rateLimit(120, 60_000, (req) => req.user!.id);

  /** What can be sent right now. Prices here are for showing: the server charges from its own record. */
  r.get(
    '/player-gifts/catalog',
    limited,
    h(async () => {
      const rows = (await db.query<ProductRow>('select * from gift_products where enabled = true order by sort_order, price, id')).rows;
      return { ok: true, products: rows.filter((p) => isAvailable(p)).map(productOut) };
    }),
  );

  r.get(
    '/player-gifts',
    limited,
    h(async (req) => {
      const me = req.user!.id;
      const dir = req.query.dir === 'sent' ? 'sent' : 'received';
      const page = Math.min(1000, Math.max(1, Number.parseInt(String(req.query.page ?? '1'), 10) || 1));
      const mine = dir === 'sent' ? 'sender_id' : 'recipient_id';
      const other = dir === 'sent' ? 'recipient_id' : 'sender_id';
      const rows = (await db.query<HistoryRow>(
        `select g.*, o.username as other_name, o.public_id as other_public, o.avatar_hue as other_hue, o.avatar_url as other_avatar
         from player_gifts g left join profiles o on o.user_id = g.${other}
         where g.${mine} = $1 order by g.created_at desc, g.id desc limit $2 offset $3`,
        [me, PAGE, (page - 1) * PAGE],
      )).rows;
      const total = (await db.query<{ n: number }>(`select count(*)::int as n from player_gifts where ${mine} = $1`, [me])).rows[0].n;
      return { ok: true, items: rows.map((g) => giftOut(g, dir)), total: Number(total), page, pageSize: PAGE, maxDisplayed: MAX_DISPLAYED_GIFTS };
    }),
  );

  /** Show a received gift on my profile, or stop showing it. */
  r.post(
    '/player-gifts/:id/display',
    limited,
    h(async (req) => {
      const me = req.user!.id;
      const id = v.uuid(req.params.id);
      const on = (req.body as { displayed?: unknown } | undefined)?.displayed === true;
      await db.tx(async (q) => {
        const g = await q.query('select id from player_gifts where id = $1 and recipient_id = $2 for update', [id, me]);
        if (!g.rows.length) throw new HttpError(404, 'Gift not found');
        if (on) {
          const n = (await q.query<{ n: number }>('select count(*)::int as n from player_gifts where recipient_id = $1 and displayed = true and id <> $2', [me, id])).rows[0].n;
          if (Number(n) >= MAX_DISPLAYED_GIFTS) throw new HttpError(409, `You can show ${MAX_DISPLAYED_GIFTS} gifts at a time`);
        }
        await q.query('update player_gifts set displayed = $2 where id = $1', [id, on]);
      });
      return { ok: true, displayed: on };
    }),
  );
}

function oneOf<T extends string>(val: unknown, list: readonly T[], field: string): T {
  if (typeof val !== 'string' || !list.includes(val as T)) throw new HttpError(400, `Unknown ${field}`);
  return val as T;
}

/** The fields an administrator can set, checked. Only those present are returned. */
function parseProduct(b: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  if (b.name !== undefined) out.name = v.str(b.name, 'Name', 2, 40);
  if (b.price !== undefined) out.price = v.int(b.price, 'Price', 1, MAX_GIFT_PRICE);
  if (b.category !== undefined) out.category = oneOf(b.category, GIFT_CATEGORIES, 'category');
  if (b.rarity !== undefined) out.rarity = oneOf(b.rarity, GIFT_RARITIES, 'rarity');
  if (b.model !== undefined) out.model = oneOf(b.model, GIFT_MODELS, 'model');
  if (b.sendAnim !== undefined) out.send_anim = oneOf(b.sendAnim, GIFT_SEND_ANIMS, 'send animation');
  if (b.receiveAnim !== undefined) out.receive_anim = oneOf(b.receiveAnim, GIFT_RECEIVE_ANIMS, 'receive animation');
  if (b.particle !== undefined) out.particle = oneOf(b.particle, GIFT_PARTICLES, 'particle effect');
  if (b.enabled !== undefined) out.enabled = b.enabled === true;
  if (b.featured !== undefined) out.featured = b.featured === true;
  if (b.sortOrder !== undefined) out.sort_order = v.int(b.sortOrder, 'Order', 0, 100_000);
  if (b.eventName !== undefined) out.event_name = b.eventName === null || b.eventName === '' ? null : v.str(b.eventName, 'Event', 2, 40);
  if (b.availableUntil !== undefined) {
    if (b.availableUntil === null || b.availableUntil === '') out.available_until = null;
    else {
      const d = new Date(String(b.availableUntil));
      if (Number.isNaN(d.getTime())) throw new HttpError(400, 'Bad end date');
      out.available_until = d;
    }
  }
  return out;
}

export function adminPlayerGiftApi(admin: Router, ctx: AppCtx): void {
  const { db } = ctx;
  const audit = (q: Queryable, actor: string, action: string, details: object) =>
    q.query('insert into audit_logs (actor_id, action, details) values ($1, $2, $3::jsonb)', [actor, action, JSON.stringify(details)]);

  /** Both catalogues with what each gift has sold, plus the choices the form offers. */
  admin.get(
    '/player-gifts',
    h(async () => {
      const rows = (await db.query<ProductRow>('select * from gift_products order by currency, sort_order, price, id')).rows;
      const stats = (await db.query<{ product_id: string; sent: number | string; spent: number | string }>(
        'select product_id, count(*) as sent, sum(price) as spent from player_gifts group by product_id',
      )).rows;
      const byId = new Map(stats.map((s) => [s.product_id, { sent: Number(s.sent), spent: Number(s.spent) }]));
      return {
        ok: true,
        products: rows.map((p) => ({ ...productOut(p), enabled: !!p.enabled, sortOrder: p.sort_order, ...(byId.get(p.id) ?? { sent: 0, spent: 0 }) })),
        options: { models: GIFT_MODELS, sendAnims: GIFT_SEND_ANIMS, receiveAnims: GIFT_RECEIVE_ANIMS, particles: GIFT_PARTICLES, rarities: GIFT_RARITIES, categories: GIFT_CATEGORIES },
      };
    }),
  );

  admin.post(
    '/player-gifts',
    rateLimit(60, 60_000, (req) => req.user!.id),
    h(async (req) => {
      const b = (req.body ?? {}) as Record<string, unknown>;
      const currency = oneOf(b.currency, GIFT_CURRENCIES, 'currency');
      const f = parseProduct(b);
      for (const k of ['name', 'price', 'category', 'rarity', 'model', 'send_anim', 'receive_anim', 'particle']) {
        if (f[k] === undefined) throw new HttpError(400, `Missing ${k}`);
      }
      const id = `custom_${randomUUID().slice(0, 13)}`;
      await db.tx(async (q) => {
        await q.query(
          `insert into gift_products (id, name, currency, price, category, rarity, model, send_anim, receive_anim, particle, enabled, featured, event_name, available_until, sort_order)
           values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15)`,
          [id, f.name, currency, f.price, f.category, f.rarity, f.model, f.send_anim, f.receive_anim, f.particle, f.enabled ?? true, f.featured ?? false, f.event_name ?? null, f.available_until ?? null, f.sort_order ?? 1000],
        );
        await audit(q, req.user!.id, 'player_gift.create', { id, name: f.name, currency, price: f.price });
      });
      return { ok: true, id };
    }),
  );

  /** Currency stays what it was: a price change can't quietly turn coins into diamonds. Gifts already sent keep their own record. */
  admin.post(
    '/player-gifts/:id',
    rateLimit(120, 60_000, (req) => req.user!.id),
    h(async (req) => {
      const id = String(req.params.id).slice(0, 40);
      const f = parseProduct((req.body ?? {}) as Record<string, unknown>);
      const cols = Object.keys(f);
      if (!cols.length) throw new HttpError(400, 'Nothing to change');
      await db.tx(async (q) => {
        const found = await q.query('select id from gift_products where id = $1 for update', [id]);
        if (!found.rows.length) throw new HttpError(404, 'Gift not found');
        await q.query(`update gift_products set ${cols.map((c, i) => `${c} = $${i + 2}`).join(', ')}, updated_at = now() where id = $1`, [id, ...cols.map((c) => f[c])]);
        await audit(q, req.user!.id, 'player_gift.update', { id, changes: f });
      });
      return { ok: true };
    }),
  );
}
