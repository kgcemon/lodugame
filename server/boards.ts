import type { Queryable } from './db';
import { HttpError } from './security';
import {
  BOARD_AMBIENTS, BOARD_COLLECTIONS, BOARD_DECORS, BOARD_EMBLEMS, BOARD_PATTERNS, BOARD_RARITIES, BOARD_SAFE_MARKS, BOARDS,
  CUSTOM_BOARDS, DEFAULT_BOARD,
  type BoardCollection, type BoardLook, type BoardRarity, type BoardTheme,
} from '../shared/boards';

/**
 * The board shop's products: the catalogue (shared/boards.ts) plus boards an administrator
 * created, with what an administrator changed (name, rarity, collection, ribbon, look,
 * publishing, featuring) and real sales. Shop labels are only shown when data backs them:
 * NEW ARRIVAL for 30 days after a board went on sale, BEST SELLER / MOST POPULAR from real
 * purchases, EXCLUSIVE / LEGENDARY from the board. Boards are paid in coins.
 */

export interface BoardOverride {
  name?: string;
  blurb?: string;
  badge?: string | null;
  rarity?: BoardRarity;
  collection?: BoardCollection;
  /** Unpublished boards are not sold or listed (owners keep and can use them). */
  published?: boolean;
  featured?: boolean;
  /** Changes to the board's look (colours, emblem, pattern, decorations, ambient effect). */
  look?: Partial<BoardLook>;
}

export type BoardLabel = 'NEW ARRIVAL' | 'BEST SELLER' | 'MOST POPULAR' | 'EXCLUSIVE' | 'LEGENDARY';

export interface BoardProduct extends BoardTheme {
  published: boolean;
  featured: boolean;
  custom: boolean;
  labels: BoardLabel[];
}

export interface BoardSales {
  owners: number;
  recent: number;
  coins: number;
}

const NEW_DAYS = 30;
const BEST_SELLER_MIN = 5;
const POPULAR_MIN = 10;
/** Shown first until an administrator features others: the couple collection's best. */
const DEFAULT_FEATURED = ['love_story', 'moonlight_love', 'heart_kingdom', 'paris_romance', 'royal_palace', 'dragon_kingdom'];

const COLOR = /^#([0-9a-f]{6}|[0-9a-f]{8})$/i;

function text(v: unknown, name: string, min: number, max: number): string {
  if (typeof v !== 'string' || v.trim().length < min || v.trim().length > max) throw new HttpError(400, `${name} must be ${min}–${max} characters`);
  return v.trim();
}

function oneOf<T extends string>(v: unknown, list: readonly T[], name: string): T {
  if (!list.includes(v as T)) throw new HttpError(400, `Unknown ${name}`);
  return v as T;
}

function rgb(v: unknown, name: string): [number, number, number] {
  if (!Array.isArray(v) || v.length !== 3 || !v.every((x) => typeof x === 'number' && x >= 0 && x <= 1)) {
    throw new HttpError(400, `${name} must be three numbers from 0 to 1`);
  }
  return [v[0], v[1], v[2]];
}

/** A checked (partial) look: colours, known emblems/patterns/decor/effects, sane numbers. */
export function validateLook(v: unknown, partial: boolean): Partial<BoardLook> {
  if (!v || typeof v !== 'object' || Array.isArray(v)) throw new HttpError(400, 'look must be an object');
  const o = v as Record<string, unknown>;
  const out: Partial<BoardLook> = {};
  for (const k of ['base', 'panel', 'panelEdge', 'track', 'grid', 'safe', 'edge', 'sky', 'decorColor'] as const) {
    if (o[k] === undefined) continue;
    if (typeof o[k] !== 'string' || !COLOR.test(o[k] as string)) throw new HttpError(400, `${k} must be a colour like #ffcc00`);
    out[k] = o[k] as string;
  }
  if (o.trim !== undefined) {
    if (typeof o.trim !== 'string' || (o.trim !== '' && !COLOR.test(o.trim))) throw new HttpError(400, 'trim must be a colour or empty');
    out.trim = o.trim;
  }
  if (o.emblem !== undefined) out.emblem = oneOf(o.emblem, BOARD_EMBLEMS, 'emblem');
  if (o.pattern !== undefined) out.pattern = oneOf(o.pattern, BOARD_PATTERNS, 'pattern');
  if (o.safeMark !== undefined) out.safeMark = oneOf(o.safeMark, BOARD_SAFE_MARKS, 'safe-cell mark');
  if (o.decor !== undefined) out.decor = oneOf(o.decor, BOARD_DECORS, 'decoration');
  if (o.ambient !== undefined) out.ambient = oneOf(o.ambient, BOARD_AMBIENTS, 'ambient effect');
  if (o.wood !== undefined) out.wood = rgb(o.wood, 'wood');
  if (o.rim !== undefined) out.rim = rgb(o.rim, 'rim');
  if (o.rimSpec !== undefined) {
    if (typeof o.rimSpec !== 'number' || o.rimSpec < 0 || o.rimSpec > 2) throw new HttpError(400, 'rimSpec must be from 0 to 2');
    out.rimSpec = o.rimSpec;
  }
  if (!partial) {
    const need: (keyof BoardLook)[] = ['base', 'panel', 'panelEdge', 'track', 'grid', 'safe', 'edge', 'trim', 'emblem', 'pattern', 'safeMark', 'wood', 'rim', 'rimSpec', 'sky', 'decor', 'decorColor', 'ambient'];
    for (const k of need) if (out[k] === undefined) throw new HttpError(400, `look.${k} is required`);
  }
  return out;
}

export function validateBoardOverrides(v: unknown, current: Record<string, BoardOverride>): Record<string, BoardOverride> {
  if (!v || typeof v !== 'object' || Array.isArray(v)) throw new HttpError(400, 'boardProducts must be an object');
  const out: Record<string, BoardOverride> = { ...current };
  for (const [id, raw] of Object.entries(v as Record<string, unknown>)) {
    if (!BOARDS.some((b) => b.id === id)) throw new HttpError(400, `Unknown board ${id}`);
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new HttpError(400, `Settings for ${id} must be an object`);
    const o = raw as Record<string, unknown>;
    const next: BoardOverride = { ...(current[id] ?? {}) };
    if (o.name !== undefined) next.name = text(o.name, 'Board name', 1, 30);
    if (o.blurb !== undefined) next.blurb = text(o.blurb, 'Description', 1, 140);
    if (o.badge !== undefined) next.badge = o.badge === null || o.badge === '' ? null : text(o.badge, 'Ribbon', 1, 24);
    if (o.rarity !== undefined) next.rarity = oneOf(o.rarity, BOARD_RARITIES, 'rarity');
    if (o.collection !== undefined) next.collection = oneOf(o.collection, BOARD_COLLECTIONS, 'collection');
    for (const k of ['published', 'featured'] as const) {
      if (o[k] === undefined) continue;
      if (typeof o[k] !== 'boolean') throw new HttpError(400, `${k} must be true or false`);
      next[k] = o[k] as boolean;
    }
    if (o.look !== undefined) next.look = { ...(next.look ?? {}), ...validateLook(o.look, true) };
    if (id === DEFAULT_BOARD && next.published === false) throw new HttpError(400, 'The classic board is always available');
    out[id] = next;
  }
  return out;
}

/** Boards an administrator made: a full board each, with an id that can't clash with the catalogue. */
export function validateCustomBoards(v: unknown): BoardTheme[] {
  if (!Array.isArray(v)) throw new HttpError(400, 'boardCustom must be a list');
  if (v.length > 100) throw new HttpError(400, 'At most 100 custom boards');
  const seen = new Set<string>();
  return v.map((raw) => {
    if (!raw || typeof raw !== 'object') throw new HttpError(400, 'Each custom board must be an object');
    const o = raw as Record<string, unknown>;
    const id = typeof o.id === 'string' ? o.id : '';
    if (!/^custom_[a-z0-9_]{2,30}$/.test(id)) throw new HttpError(400, 'Custom board ids look like custom_my_board');
    if (seen.has(id) || BOARDS.some((b) => b.id === id)) throw new HttpError(400, `Board id ${id} is taken`);
    seen.add(id);
    const price = o.price;
    if (typeof price !== 'number' || !Number.isInteger(price) || price < 0 || price > 10_000_000) throw new HttpError(400, 'Price must be a whole number of coins');
    return {
      id,
      name: text(o.name, 'Board name', 1, 30),
      price,
      blurb: typeof o.blurb === 'string' && o.blurb.trim() ? text(o.blurb, 'Description', 1, 140) : 'A LudoX special board.',
      royal: price > 0,
      badge: typeof o.badge === 'string' && o.badge.trim() ? text(o.badge, 'Ribbon', 1, 24) : undefined,
      rarity: oneOf(o.rarity ?? 'epic', BOARD_RARITIES, 'rarity'),
      collection: oneOf(o.collection ?? 'exclusive', BOARD_COLLECTIONS, 'collection'),
      added: typeof o.added === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(o.added) ? o.added : new Date().toISOString().slice(0, 10),
      look: validateLook(o.look, false) as BoardLook,
    };
  });
}

/** Make the administrator's boards known everywhere boards are looked up. */
export function useCustomBoards(list: BoardTheme[]): void {
  CUSTOM_BOARDS.splice(0, CUSTOM_BOARDS.length, ...list);
}

let salesCache: { at: number; byId: Map<string, BoardSales> } | null = null;

export function forgetBoardSales(): void {
  salesCache = null;
}

export async function boardSales(q: Queryable): Promise<Map<string, BoardSales>> {
  if (salesCache && Date.now() - salesCache.at < 60_000) return salesCache.byId;
  const since = new Date(Date.now() - NEW_DAYS * 86_400_000);
  const rows = (
    await q.query<{ id: string; owners: number; recent: number; coins: number }>(
      `select b.board_id as id, count(*)::int as owners,
              sum(case when b.created_at >= $1 then 1 else 0 end)::int as recent,
              coalesce(sum(-w.amount), 0)::int as coins
       from user_boards b left join wallet_transactions w on w.user_id = b.user_id and w.idempotency_key = concat('board:', b.board_id)
       group by b.board_id`,
      [since],
    )
  ).rows;
  const byId = new Map(rows.map((r) => [r.id, { owners: Number(r.owners), recent: Number(r.recent), coins: Number(r.coins) }]));
  salesCache = { at: Date.now(), byId };
  return byId;
}

/** Every board (catalogue and custom) with the administrator's changes, prices and labels. */
export function boardProducts(
  overrides: Record<string, BoardOverride>,
  custom: BoardTheme[],
  price: (id: string) => number,
  sales: Map<string, BoardSales>,
  now = Date.now(),
): BoardProduct[] {
  const all = [...BOARDS, ...custom];
  const paid = all.filter((b) => b.id !== DEFAULT_BOARD);
  const top = (score: (id: string) => number, min: number) => {
    let best: string | null = null;
    for (const b of paid) if (score(b.id) >= min && (best === null || score(b.id) > score(best))) best = b.id;
    return best;
  };
  const bestSeller = top((id) => sales.get(id)?.recent ?? 0, BEST_SELLER_MIN);
  const popular = top((id) => sales.get(id)?.owners ?? 0, POPULAR_MIN);
  const anyFeatured = Object.values(overrides).some((o) => o.featured !== undefined);
  return all.map((b) => {
    const isCustom = !BOARDS.includes(b);
    const o = overrides[b.id] ?? {};
    const rarity = o.rarity ?? b.rarity;
    const collection = o.collection ?? b.collection;
    const labels: BoardLabel[] = [];
    if (now - Date.parse(b.added) < NEW_DAYS * 86_400_000) labels.push('NEW ARRIVAL');
    if (b.id === bestSeller) labels.push('BEST SELLER');
    if (b.id === popular) labels.push('MOST POPULAR');
    if (collection === 'exclusive') labels.push('EXCLUSIVE');
    if (rarity === 'legendary') labels.push('LEGENDARY');
    const badge = o.badge === null ? undefined : o.badge ?? b.badge;
    return {
      ...b,
      name: o.name ?? b.name,
      blurb: o.blurb ?? b.blurb,
      badge,
      rarity,
      collection,
      look: { ...b.look, ...(o.look ?? {}) },
      price: b.id === DEFAULT_BOARD ? 0 : isCustom ? b.price : price(b.id),
      published: b.id === DEFAULT_BOARD || o.published !== false,
      featured: anyFeatured ? o.featured === true : o.featured ?? DEFAULT_FEATURED.includes(b.id),
      custom: isCustom,
      labels,
    };
  });
}
