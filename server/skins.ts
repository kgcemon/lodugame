import type { Db, Queryable } from './db';
import { applyGemTx } from './gems';
import { HttpError } from './security';
import { DEFAULT_DICE_SKIN, diceSkin } from '../shared/skins';

/**
 * Dice skins: bought once with diamonds, then shown on the player's die in every match.
 * A purchase is one transaction: the diamond debit (idempotency key `skin:<id>`, so a
 * retried tap never charges twice) and the ownership row together, then it is equipped.
 */

export interface SkinState {
  owned: string[];
  equipped: string;
}

export async function skinState(q: Queryable, userId: string): Promise<SkinState> {
  const owned = await q.query<{ skin_id: string }>('select skin_id from user_dice_skins where user_id = $1', [userId]);
  const p = await q.query<{ dice_skin: string }>('select dice_skin from profiles where user_id = $1', [userId]);
  const ids = new Set([DEFAULT_DICE_SKIN, ...owned.rows.map((r) => r.skin_id)]);
  const equipped = p.rows[0]?.dice_skin;
  return { owned: [...ids], equipped: equipped && ids.has(equipped) ? equipped : DEFAULT_DICE_SKIN };
}

/** Buy a skin with diamonds and put it on. Buying one you already own changes nothing. */
export async function buySkin(db: Db, userId: string, skinId: string): Promise<SkinState> {
  const skin = diceSkin(skinId);
  if (!skin) throw new HttpError(404, 'No such dice');
  return db.tx(async (q) => {
    const has = await q.query('select 1 from user_dice_skins where user_id = $1 and skin_id = $2', [userId, skin.id]);
    if (!has.rows.length && skin.price > 0) {
      const tx = await applyGemTx(q, { userId, type: 'skin_purchase', amount: -skin.price, key: `skin:${skin.id}`, note: `${skin.name} dice` });
      await q.query('insert into user_dice_skins (user_id, skin_id, gem_tx_id) values ($1, $2, $3) on conflict do nothing', [userId, skin.id, tx.id]);
    }
    await q.query('update profiles set dice_skin = $2 where user_id = $1', [userId, skin.id]);
    return skinState(q, userId);
  });
}

/** Show a skin the player owns (or the free one). */
export async function equipSkin(db: Db, userId: string, skinId: string): Promise<SkinState> {
  const skin = diceSkin(skinId);
  if (!skin) throw new HttpError(404, 'No such dice');
  return db.tx(async (q) => {
    const state = await skinState(q, userId);
    if (!state.owned.includes(skin.id)) throw new HttpError(403, 'Buy this dice first');
    await q.query('update profiles set dice_skin = $2 where user_id = $1', [userId, skin.id]);
    return { ...state, equipped: skin.id };
  });
}
