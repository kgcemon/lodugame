import type { Queryable } from './db';
import { HttpError } from './security';
import {
  COLLECTIONS, DEFAULT_DICE_SKIN, DEFAULT_FEATURED, DICE_SKINS, EFFECTS, RARITIES,
  type DiceEffect, type DiceRarity, type DiceSkin,
} from '../shared/skins';

/**
 * The dice shop's products: the catalogue (shared/skins.ts) with what an administrator
 * changed (name, rarity, effect, publishing, featuring, a limited stock) and what really
 * happened in the shop (sales). Shop labels are only shown when this data backs them:
 * NEW ARRIVAL for 30 days after an item went on sale, BEST SELLER / MOST POPULAR from real
 * purchases, LIMITED EDITION only with a stock limit, EXCLUSIVE / LEGENDARY from the item.
 */

/** What an administrator can change about a die (price lives with the other shop prices). */
export interface DiceOverride {
  name?: string;
  rarity?: DiceRarity;
  effect?: DiceEffect;
  /** Unpublished dice are not sold or listed (owners keep and can use them). */
  published?: boolean;
  featured?: boolean;
  /** Most that will ever be sold; null/absent = unlimited. */
  limit?: number | null;
}

export type DiceLabel = 'NEW ARRIVAL' | 'BEST SELLER' | 'MOST POPULAR' | 'LIMITED EDITION' | 'EXCLUSIVE' | 'LEGENDARY';

export interface DiceProduct extends DiceSkin {
  published: boolean;
  featured: boolean;
  limit: number | null;
  /** Copies left of a limited die (null = unlimited). */
  left: number | null;
  labels: DiceLabel[];
}

export interface DiceSales {
  /** Players who own it (bought or got it free). */
  owners: number;
  /** Bought in the last 30 days. */
  recent: number;
  /** Diamonds spent on it, ever. */
  diamonds: number;
}

const NEW_DAYS = 30;
/** BEST SELLER needs at least this many sales in 30 days; MOST POPULAR this many owners. */
const BEST_SELLER_MIN = 5;
const POPULAR_MIN = 10;

export function validateDiceOverrides(v: unknown, current: Record<string, DiceOverride>): Record<string, DiceOverride> {
  if (!v || typeof v !== 'object' || Array.isArray(v)) throw new HttpError(400, 'diceProducts must be an object');
  const out: Record<string, DiceOverride> = { ...current };
  for (const [id, raw] of Object.entries(v as Record<string, unknown>)) {
    if (!DICE_SKINS.some((s) => s.id === id)) throw new HttpError(400, `Unknown dice ${id}`);
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new HttpError(400, `Settings for ${id} must be an object`);
    const o = raw as Record<string, unknown>;
    const next: DiceOverride = { ...(current[id] ?? {}) };
    if (o.name !== undefined) {
      if (typeof o.name !== 'string' || !o.name.trim() || o.name.trim().length > 30) throw new HttpError(400, 'Dice name must be 1–30 characters');
      next.name = o.name.trim();
    }
    if (o.rarity !== undefined) {
      if (!RARITIES.includes(o.rarity as DiceRarity)) throw new HttpError(400, 'Unknown rarity');
      next.rarity = o.rarity as DiceRarity;
    }
    if (o.effect !== undefined) {
      if (!EFFECTS.includes(o.effect as DiceEffect)) throw new HttpError(400, 'Unknown effect');
      next.effect = o.effect as DiceEffect;
    }
    for (const k of ['published', 'featured'] as const) {
      if (o[k] === undefined) continue;
      if (typeof o[k] !== 'boolean') throw new HttpError(400, `${k} must be true or false`);
      next[k] = o[k] as boolean;
    }
    if (id === DEFAULT_DICE_SKIN && next.published === false) throw new HttpError(400, 'The classic dice is always available');
    if (o.limit !== undefined) {
      if (o.limit !== null && (typeof o.limit !== 'number' || !Number.isInteger(o.limit) || o.limit < 1 || o.limit > 1_000_000)) {
        throw new HttpError(400, 'Limit must be empty or a whole number from 1 to 1,000,000');
      }
      next.limit = o.limit as number | null;
    }
    out[id] = next;
  }
  return out;
}

/** Sales per die, cached briefly (the shop is opened often, purchases are rare). */
let salesCache: { at: number; byId: Map<string, DiceSales> } | null = null;

export function forgetDiceSales(): void {
  salesCache = null;
}

export async function diceSales(q: Queryable): Promise<Map<string, DiceSales>> {
  if (salesCache && Date.now() - salesCache.at < 60_000) return salesCache.byId;
  const since = new Date(Date.now() - NEW_DAYS * 86_400_000);
  const rows = (
    await q.query<{ id: string; owners: number; recent: number; diamonds: number }>(
      `select s.skin_id as id, count(*)::int as owners,
              sum(case when s.created_at >= $1 and s.gem_tx_id is not null then 1 else 0 end)::int as recent,
              coalesce(sum(-g.amount), 0)::int as diamonds
       from user_dice_skins s left join gem_transactions g on g.id = s.gem_tx_id
       group by s.skin_id`,
      [since],
    )
  ).rows;
  const byId = new Map(rows.map((r) => [r.id, { owners: Number(r.owners), recent: Number(r.recent), diamonds: Number(r.diamonds) }]));
  salesCache = { at: Date.now(), byId };
  return byId;
}

/** How many of each limited die have been sold. */
export async function stockSold(q: Queryable): Promise<Map<string, number>> {
  const rows = (await q.query<{ item: string; sold: number }>(`select item, sold from shop_stock where item like 'dice:%'`)).rows;
  return new Map(rows.map((r) => [r.item.slice(5), Number(r.sold)]));
}

/** Every die with the administrator's changes, prices, stock and labels. */
export function diceProducts(
  overrides: Record<string, DiceOverride>,
  price: (id: string) => number,
  sales: Map<string, DiceSales>,
  sold: Map<string, number>,
  now = Date.now(),
): DiceProduct[] {
  const paid = DICE_SKINS.filter((s) => s.id !== DEFAULT_DICE_SKIN);
  const top = (score: (id: string) => number, min: number) => {
    let best: string | null = null;
    for (const s of paid) if (score(s.id) >= min && (best === null || score(s.id) > score(best))) best = s.id;
    return best;
  };
  const bestSeller = top((id) => sales.get(id)?.recent ?? 0, BEST_SELLER_MIN);
  const popular = top((id) => sales.get(id)?.owners ?? 0, POPULAR_MIN);
  const anyFeatured = Object.values(overrides).some((o) => o.featured !== undefined);
  return DICE_SKINS.map((s) => {
    const o = overrides[s.id] ?? {};
    const limit = o.limit ?? null;
    const rarity = o.rarity ?? s.rarity;
    const labels: DiceLabel[] = [];
    if (now - Date.parse(s.added) < NEW_DAYS * 86_400_000) labels.push('NEW ARRIVAL');
    if (s.id === bestSeller) labels.push('BEST SELLER');
    if (s.id === popular) labels.push('MOST POPULAR');
    if (limit !== null) labels.push('LIMITED EDITION');
    if (s.collection === 'exclusive') labels.push('EXCLUSIVE');
    if (rarity === 'legendary') labels.push('LEGENDARY');
    return {
      ...s,
      name: o.name ?? s.name,
      rarity,
      effect: o.effect ?? s.effect,
      price: s.id === DEFAULT_DICE_SKIN ? 0 : price(s.id),
      published: s.id === DEFAULT_DICE_SKIN || o.published !== false,
      featured: anyFeatured ? o.featured === true : o.featured ?? DEFAULT_FEATURED.includes(s.id),
      limit,
      left: limit === null ? null : Math.max(0, limit - (sold.get(s.id) ?? 0)),
      labels,
    };
  });
}

/**
 * Inside a purchase transaction: take one copy of a limited die, or refuse when none are
 * left. The counter row is updated in place, so concurrent buyers can't both get the last.
 */
export async function takeStock(q: Queryable, id: string, limit: number): Promise<void> {
  await q.query(`insert into shop_stock (item, sold) values ($1, 0) on conflict (item) do nothing`, [`dice:${id}`]);
  const r = await q.query(`update shop_stock set sold = sold + 1 where item = $1 and sold < $2`, [`dice:${id}`, limit]);
  if (r.rowCount < 1) throw new HttpError(409, 'Sold out — every copy of this limited dice has been bought.');
}

export { COLLECTIONS, RARITIES, EFFECTS };
