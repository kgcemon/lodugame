/**
 * Gifts an administrator sends and the rewards the app animates. Everything here describes
 * something the server already decided: the app never works out an amount by itself.
 */

/** What a gift really contains: only what the wallet or the inventory can deliver. */
export type GiftKind = 'coins' | 'diamonds' | 'dice' | 'board' | 'frame';
export const GIFT_KINDS: GiftKind[] = ['coins', 'diamonds', 'dice', 'board', 'frame'];

export type GiftStatus = 'pending' | 'opened' | 'claimed' | 'expired' | 'cancelled';
export const GIFT_STATUSES: GiftStatus[] = ['pending', 'opened', 'claimed', 'expired', 'cancelled'];

/** Gift boxes are a presentation: the box hides its content until opened, and adds nothing to it. */
export const GIFT_BOXES = [
  { id: 'royal', label: 'Royal Box' },
  { id: 'legendary', label: 'Legendary Box' },
  { id: 'mystery', label: 'Mystery Box' },
  { id: 'festival', label: 'Festival Box' },
  { id: 'vip', label: 'VIP Box' },
  { id: 'custom', label: 'Gift Box' },
] as const;
export type GiftBox = (typeof GIFT_BOXES)[number]['id'];

export const MAX_GIFT_COINS = 10_000_000;
export const MAX_GIFT_DIAMONDS = 1_000_000;
export const MAX_GIFT_MESSAGE = 200;
/** Players that can be picked one by one for a single send (everyone goes through "all"). */
export const MAX_GIFT_RECIPIENTS = 100;
export const MAX_GIFT_EXPIRY_DAYS = 365;
/**
 * A cosmetic the player already owns is not granted twice: they get this share of its shop
 * price instead, in the currency the shop sells it for.
 */
export const DUPLICATE_REFUND_SHARE = 0.25;

export type RewardKind = 'coins' | 'diamonds';
export type RewardStyle = 'silver_coin' | 'gold_coin' | 'crown_coin' | 'diamond' | 'legendary_diamond';
export type RewardSource = 'daily_bonus' | 'daily_spin' | 'match_win' | 'gift';

/** A credit the ledger has confirmed. `id` is the ledger entry: one animation per entry, ever. */
export interface RewardEvent {
  id: string;
  source: RewardSource;
  kind: RewardKind;
  amount: number;
  before: number;
  after: number;
  style: RewardStyle;
  /** The match this reward was won in (match wins only). */
  matchId?: string;
}

export function rewardStyle(kind: RewardKind, amount: number): RewardStyle {
  if (kind === 'diamonds') return amount >= 100 ? 'legendary_diamond' : 'diamond';
  return amount >= 5000 ? 'crown_coin' : amount >= 100 ? 'gold_coin' : 'silver_coin';
}

/** How rare a currency gift feels, from its size (a cosmetic has the rarity of the item). */
export function currencyRarity(kind: RewardKind, amount: number): string {
  const n = kind === 'diamonds' ? amount * 50 : amount;
  return n >= 50_000 ? 'legendary' : n >= 10_000 ? 'epic' : n >= 1_000 ? 'rare' : 'common';
}
