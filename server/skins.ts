import type { Db, Queryable } from './db';
import { applyGemTx } from './gems';
import { HttpError } from './security';
import { DEFAULT_DICE_SKIN, diceSkin } from '../shared/skins';
import { NO_FRAME, profileFrame } from '../shared/frames';

/**
 * The shop: dice skins and profile frames, each bought once with diamonds and then shown
 * in every match. A purchase is one transaction: the diamond debit (idempotency key
 * `skin:<id>` / `frame:<id>`, so a retried tap never charges twice) and the ownership row
 * together, then it is put on. Prices come from the caller (game settings).
 */

export type ShopKind = 'dice' | 'frame';

const KINDS = {
  dice: { table: 'user_dice_skins', col: 'skin_id', profileCol: 'dice_skin', key: 'skin', label: 'dice', find: diceSkin, free: DEFAULT_DICE_SKIN },
  frame: { table: 'user_frames', col: 'frame_id', profileCol: 'frame', key: 'frame', label: 'frame', find: profileFrame, free: null },
} as const;

export interface SkinState {
  owned: string[];
  /** The one in use; for frames null means no frame. */
  equipped: string | null;
}

export async function shopState(q: Queryable, kind: ShopKind, userId: string): Promise<SkinState> {
  const k = KINDS[kind];
  const owned = await q.query<{ id: string }>(`select ${k.col} as id from ${k.table} where user_id = $1`, [userId]);
  const p = await q.query<{ on: string | null }>(`select ${k.profileCol} as "on" from profiles where user_id = $1`, [userId]);
  const ids = new Set([...(k.free ? [k.free] : []), ...owned.rows.map((r) => r.id)]);
  const on = p.rows[0]?.on;
  return { owned: [...ids], equipped: on && ids.has(on) ? on : k.free };
}

/** Buy an item with diamonds at `price` and put it on. Buying one you already own changes nothing. */
export async function buyItem(db: Db, kind: ShopKind, userId: string, id: string, price: number): Promise<SkinState> {
  const k = KINDS[kind];
  const item = k.find(id);
  if (!item) throw new HttpError(404, `No such ${k.label}`);
  if (id === k.free) return equipItem(db, kind, userId, id);
  return db.tx(async (q) => {
    const has = await q.query(`select 1 from ${k.table} where user_id = $1 and ${k.col} = $2`, [userId, item.id]);
    if (!has.rows.length) {
      const tx = price > 0
        ? await applyGemTx(q, { userId, type: 'skin_purchase', amount: -price, key: `${k.key}:${item.id}`, note: `${item.name} ${k.label}` })
        : null;
      await q.query(`insert into ${k.table} (user_id, ${k.col}, gem_tx_id) values ($1, $2, $3) on conflict do nothing`, [userId, item.id, tx?.id ?? null]);
    }
    await q.query(`update profiles set ${k.profileCol} = $2 where user_id = $1`, [userId, item.id]);
    return shopState(q, kind, userId);
  });
}

/** Show an item the player owns (or the free dice; `none` takes the frame off). */
export async function equipItem(db: Db, kind: ShopKind, userId: string, id: string): Promise<SkinState> {
  const k = KINDS[kind];
  const off = kind === 'frame' && id === NO_FRAME;
  if (!off && !k.find(id)) throw new HttpError(404, `No such ${k.label}`);
  return db.tx(async (q) => {
    const state = await shopState(q, kind, userId);
    if (!off && !state.owned.includes(id)) throw new HttpError(403, `Buy this ${k.label} first`);
    await q.query(`update profiles set ${k.profileCol} = $2 where user_id = $1`, [userId, off ? null : id]);
    return { ...state, equipped: off ? null : id };
  });
}

/** What a player shows at the table: their dice skin and frame. */
export async function cosmetics(q: Queryable, userId: string): Promise<{ diceSkin: string; frame: string | null }> {
  const [dice, frame] = await Promise.all([shopState(q, 'dice', userId), shopState(q, 'frame', userId)]);
  return { diceSkin: dice.equipped ?? DEFAULT_DICE_SKIN, frame: frame.equipped };
}
