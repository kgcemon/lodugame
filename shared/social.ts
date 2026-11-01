import type { GameEvent, PlayerState } from './types';

/**
 * Emotes, quick messages and chat validation, shared by client and server so
 * both sides accept exactly the same things.
 */

/** Which 3D reaction an emote plays. */
export type EmoteEffect = 'laugh' | 'love' | 'fire' | 'clap' | 'wow' | 'cry' | 'party' | 'cool' | 'pop';

export interface EmoteDef {
  emoji: string;
  label: string;
  effect: EmoteEffect;
}

/** The approved emote list. Ids are the only thing that travels over the network. */
export const EMOTES = {
  grin: { emoji: '😀', label: 'Grin', effect: 'pop' },
  beam: { emoji: '😁', label: 'Beam', effect: 'pop' },
  squint: { emoji: '😆', label: 'Laugh', effect: 'laugh' },
  joy: { emoji: '😂', label: 'Tears of joy', effect: 'laugh' },
  rofl: { emoji: '🤣', label: 'Rolling', effect: 'laugh' },
  blush: { emoji: '😊', label: 'Smile', effect: 'pop' },
  cool: { emoji: '😎', label: 'Cool', effect: 'cool' },
  party: { emoji: '🥳', label: 'Party', effect: 'party' },

  heart: { emoji: '❤️', label: 'Love', effect: 'love' },
  sparkle_heart: { emoji: '💖', label: 'Sparkling heart', effect: 'love' },
  fire: { emoji: '🔥', label: 'Fire', effect: 'fire' },
  clap: { emoji: '👏', label: 'Clap', effect: 'clap' },
  thumbs_up: { emoji: '👍', label: 'Thumbs up', effect: 'pop' },
  thumbs_down: { emoji: '👎', label: 'Thumbs down', effect: 'pop' },
  raise_hands: { emoji: '🙌', label: 'Celebrate', effect: 'clap' },
  hundred: { emoji: '💯', label: 'Hundred', effect: 'fire' },

  clown: { emoji: '🤡', label: 'Clown', effect: 'pop' },
  zany: { emoji: '🤪', label: 'Zany', effect: 'laugh' },
  wink_tongue: { emoji: '😜', label: 'Cheeky', effect: 'laugh' },
  smirk: { emoji: '😏', label: 'Smirk', effect: 'cool' },
  giggle: { emoji: '🤭', label: 'Giggle', effect: 'pop' },
  see_no_evil: { emoji: '🙈', label: 'Oops', effect: 'pop' },

  cry: { emoji: '😢', label: 'Sad', effect: 'cry' },
  sob: { emoji: '😭', label: 'Crying', effect: 'cry' },
  pleading: { emoji: '🥺', label: 'Pleading', effect: 'cry' },
  broken_heart: { emoji: '💔', label: 'Heartbroken', effect: 'cry' },
  pensive: { emoji: '😔', label: 'Pensive', effect: 'cry' },

  devil: { emoji: '😈', label: 'Devilish', effect: 'fire' },
  skull: { emoji: '💀', label: 'Skull', effect: 'wow' },
  zap: { emoji: '⚡', label: 'Zap', effect: 'wow' },
  trophy: { emoji: '🏆', label: 'Trophy', effect: 'party' },
  crown: { emoji: '👑', label: 'Crown', effect: 'party' },

  open_mouth: { emoji: '😮', label: 'Wow', effect: 'wow' },
  astonished: { emoji: '😲', label: 'Astonished', effect: 'wow' },
  mind_blown: { emoji: '🤯', label: 'Mind blown', effect: 'wow' },
  scream: { emoji: '😱', label: 'Scream', effect: 'wow' },
  flushed: { emoji: '😳', label: 'Flushed', effect: 'wow' },
} as const satisfies Record<string, EmoteDef>;

export type EmoteId = keyof typeof EMOTES;

export const EMOTE_CATEGORIES: { id: string; label: string; icon: string; emotes: EmoteId[] }[] = [
  { id: 'happy', label: 'Happy', icon: '😀', emotes: ['grin', 'beam', 'squint', 'joy', 'rofl', 'blush', 'cool', 'party'] },
  { id: 'reactions', label: 'Reactions', icon: '❤️', emotes: ['heart', 'sparkle_heart', 'fire', 'clap', 'thumbs_up', 'thumbs_down', 'raise_hands', 'hundred'] },
  { id: 'funny', label: 'Funny', icon: '🤪', emotes: ['clown', 'zany', 'wink_tongue', 'smirk', 'giggle', 'see_no_evil'] },
  { id: 'sad', label: 'Sad', icon: '😢', emotes: ['cry', 'sob', 'pleading', 'broken_heart', 'pensive'] },
  { id: 'competitive', label: 'Competitive', icon: '🏆', emotes: ['devil', 'smirk', 'skull', 'zap', 'trophy', 'crown'] },
  { id: 'surprised', label: 'Surprised', icon: '😮', emotes: ['open_mouth', 'astonished', 'mind_blown', 'scream', 'flushed'] },
];

/** One-tap reactions shown next to the gameplay controls. */
export const INSTANT_REACTIONS: EmoteId[] = ['joy', 'heart', 'fire', 'clap', 'sob', 'cool'];

/** One-tap chat lines. Edit this list to change the quick messages everywhere. */
export const QUICK_MESSAGES: string[] = [
  'Good luck! 🍀',
  'Good game! 🎮',
  'Nice move! 👏',
  'Well played! 🏆',
  'Thank you! ❤️',
  'Oops! 😅',
  'Oh no! 😭',
  'Wow! 😮',
  'Your turn! 🎲',
  "Let's go! 🔥",
  'Haha! 😂',
  'GG! 🤝',
];

/** Emoji offered by the chat's emoji picker (insertion only, no effects). */
export const CHAT_EMOJI: string[] = [
  '😀', '😂', '🤣', '😊', '😍', '😎', '🥳', '😅', '😜', '🤔', '😏', '😮', '😱', '😢', '😭', '😡',
  '❤️', '💖', '🔥', '👏', '👍', '👎', '🙌', '🙏', '💯', '🎉', '🎲', '🏆', '👑', '🍀', '⭐', '🤝',
];

export function isEmoteId(id: unknown): id is EmoteId {
  return typeof id === 'string' && Object.prototype.hasOwnProperty.call(EMOTES, id);
}

export const CHAT_MAX_CHARS = 200;
/** Raw payloads larger than this are rejected before any processing. */
export const CHAT_MAX_RAW = 1000;

// Rate limits (enforced on the server; the client mirrors them for instant feedback).
export const CHAT_LIMIT = { burst: 5, windowMs: 8000, minGapMs: 500 };
export const EMOTE_LIMIT = { burst: 4, windowMs: 8000, cooldownMs: 1200 };
export const CHAT_HISTORY = 60;

/**
 * Normalise a chat message: Unicode NFC (keeps Bangla and emoji intact),
 * no control/format characters except emoji joiners, single spaces, and a
 * limit counted in characters (code points), not UTF-16 units.
 * Returns null when the message is empty or invalid.
 */
export function normalizeChat(raw: unknown): string | null {
  if (typeof raw !== 'string' || raw.length > CHAT_MAX_RAW) return null;
  let s = raw.normalize('NFC');
  // Strip C0/C1 controls, bidi overrides and other invisible formatting — but keep
  // ZWJ (U+200D) and variation selectors, which emoji sequences need.
  s = s.replace(/[\u0000-\u001f\u007f-\u009f‪-‮⁦-⁩​‎‏﻿]/g, ' ');
  s = s.replace(/\s+/g, ' ').trim();
  if (!s) return null;
  const chars = Array.from(s);
  if (chars.length > CHAT_MAX_CHARS) s = chars.slice(0, CHAT_MAX_CHARS).join('');
  return s;
}

export function charCount(s: string): number {
  return Array.from(s).length;
}

/** Sliding-window limiter used on the server (and mirrored on the client). */
export class RateLimiter {
  private hits: number[] = [];
  private last = -Infinity;
  constructor(private burst: number, private windowMs: number, private gapMs: number) {}

  /** Milliseconds until the next action is allowed (0 = allowed now). */
  wait(now = Date.now()): number {
    this.hits = this.hits.filter((t) => now - t < this.windowMs);
    const gap = Math.max(0, this.last + this.gapMs - now);
    const full = this.hits.length >= this.burst ? this.hits[0] + this.windowMs - now : 0;
    return Math.max(gap, full);
  }

  /** Record an action if allowed; returns false when rate-limited. */
  take(now = Date.now()): boolean {
    if (this.wait(now) > 0) return false;
    this.hits.push(now);
    this.last = now;
    return true;
  }
}

/** A bot's spontaneous reaction to what just happened, or null. Used by local play and the server. */
export function botReaction(
  events: GameEvent[],
  players: PlayerState[],
  rng: () => number,
): { player: number; id: EmoteId } | null {
  const pick = <T,>(xs: T[]) => xs[Math.floor(rng() * xs.length)];
  for (const e of events) {
    if (e.type === 'playerFinished' && players[e.player].kind === 'ai' && rng() < 0.8) {
      return { player: e.player, id: pick<EmoteId>(['party', 'crown', 'trophy']) };
    }
    if (e.type === 'captured') {
      if (players[e.by].kind === 'ai' && rng() < 0.45) return { player: e.by, id: pick<EmoteId>(['joy', 'cool', 'devil', 'smirk']) };
      if (players[e.player].kind === 'ai' && rng() < 0.35) return { player: e.player, id: pick<EmoteId>(['sob', 'cry', 'broken_heart']) };
    }
  }
  return null;
}
