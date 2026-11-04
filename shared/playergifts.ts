/**
 * Gifts players send each other during a match, paid from their own coins or diamonds. These are
 * cosmetic: the recipient gets a collectible, never currency, and the match is not affected.
 * (Gifts from administrators are a different thing: see shared/gifts.ts.)
 */

export type GiftCurrency = 'coins' | 'diamonds';
export const GIFT_CURRENCIES: GiftCurrency[] = ['coins', 'diamonds'];

export const GIFT_RARITIES = ['common', 'rare', 'epic', 'legendary'] as const;
export type GiftRarity = (typeof GIFT_RARITIES)[number];

export const GIFT_CATEGORIES = ['romance', 'cute', 'sweet', 'celebration', 'royal', 'legendary'] as const;

/** The 3D models the app can draw (app/.../gl/GiftModels.java). A new product picks one of these. */
export const GIFT_MODELS = [
  'rose', 'bear', 'letter', 'chocolates', 'bouquet', 'heart_gold', 'cat', 'cake', 'balloon', 'trophy',
  'heart_diamond', 'royal_bouquet', 'butterfly', 'balloon_heart', 'crown', 'bear_diamond', 'peacock',
  'phoenix', 'dragon', 'chest', 'car', 'palace',
] as const;

/** How it travels from the sender to the recipient. */
export const GIFT_SEND_ANIMS = ['float', 'arc', 'flutter', 'hop', 'rise', 'swoop', 'drive'] as const;
/** What it does when it arrives. */
export const GIFT_RECEIVE_ANIMS = ['pop', 'bounce', 'expand', 'unfold', 'open', 'bob', 'hover', 'wings', 'land'] as const;
export const GIFT_PARTICLES = ['sparks', 'hearts', 'petals', 'stars', 'confetti', 'magic', 'fire', 'diamonds'] as const;

/** How long a gift's journey takes on every screen: the rarer, the grander. */
export const GIFT_DURATION_MS: Record<GiftRarity, number> = { common: 3_000, rare: 3_600, epic: 4_400, legendary: 5_600 };

/** Gifts one player can send per minute. */
export const GIFT_SENDS_PER_MIN = 12;
/** Collectibles a player can show on their profile. */
export const MAX_DISPLAYED_GIFTS = 6;
export const MAX_GIFT_PRICE = 10_000_000;

export interface GiftProductView {
  id: string;
  name: string;
  currency: GiftCurrency;
  price: number;
  category: string;
  rarity: GiftRarity;
  model: string;
  sendAnim: string;
  receiveAnim: string;
  particle: string;
  featured: boolean;
  /** Event name for an event-exclusive gift, else null. */
  eventName: string | null;
  availableUntil: string | null;
}

/** One seat in a gift's journey. */
export interface GiftSeat {
  seatId: string;
  name: string;
  color: string;
}

/** A gift delivered in a match. `id` is its record: one journey per id, on every screen. */
export interface PlayerGiftEvent {
  id: string;
  matchId: string;
  name: string;
  rarity: GiftRarity;
  model: string;
  sendAnim: string;
  receiveAnim: string;
  particle: string;
  from: GiftSeat;
  to: GiftSeat;
  durationMs: number;
}

/** A gift waiting for its recipient to see it (they were away when it arrived). */
export interface MissedGift {
  id: string;
  name: string;
  from: string;
}
