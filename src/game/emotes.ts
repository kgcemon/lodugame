import { create } from 'zustand';
import { EMOTE_LIMIT, EMOTES, RateLimiter, type EmoteEffect, type EmoteId } from '../../shared/social';
import type { Color } from '../../shared/types';
import { sfx } from '../audio/audio';
import { useGame } from '../store/game';
import { net } from './net';

/**
 * Active emote reactions. Kept in their own tiny store so that showing a
 * reaction re-renders only the 3D effect layer and the player panels — never
 * the board or the HUD.
 */

export interface Reaction {
  key: number;
  id: EmoteId;
  effect: EmoteEffect;
  emoji: string;
  color: Color;
  /** Seconds the effect lives. */
  life: number;
}

export const EFFECT_LIFE: Record<EmoteEffect, number> = {
  pop: 1.8,
  laugh: 2.4,
  love: 2.6,
  fire: 2.4,
  clap: 2.2,
  wow: 2.0,
  cry: 2.6,
  party: 2.8,
  cool: 2.4,
};

/** At most this many 3D reactions at once; the oldest makes room for a new one. */
export const MAX_REACTIONS = 4;
const IMPORTANT: EmoteEffect[] = ['love', 'fire', 'party'];

export const useReactions = create<{ list: Reaction[] }>()(() => ({ list: [] }));

let keySeq = 0;
const timers = new Set<ReturnType<typeof setTimeout>>();
// Client mirror of the server limiter so buttons can grey out immediately.
let limiter = new RateLimiter(EMOTE_LIMIT.burst, EMOTE_LIMIT.windowMs, EMOTE_LIMIT.cooldownMs);

/** Display a reaction for a colour (called for local emotes and for every server broadcast). */
export function showEmote(id: EmoteId, color: Color): void {
  const def = EMOTES[id];
  const effect = def.effect;
  const r: Reaction = { key: ++keySeq, id, effect, emoji: def.emoji, color, life: EFFECT_LIFE[effect] };
  useReactions.setState((s) => {
    // One reaction per player at a time: a new one replaces that player's previous one.
    const others = s.list.filter((x) => x.color !== color);
    return { list: [...others, r].slice(-MAX_REACTIONS) };
  });
  const t = setTimeout(() => {
    timers.delete(t);
    useReactions.setState((s) => ({ list: s.list.filter((x) => x.key !== r.key) }));
  }, r.life * 1000);
  timers.add(t);
  sfx('emote');
  if (IMPORTANT.includes(effect)) sfx('sparkle');
}

/** Remove every reaction and pending timer (leaving or restarting a match). */
export function clearReactions(): void {
  for (const t of timers) clearTimeout(t);
  timers.clear();
  useReactions.setState({ list: [] });
  limiter = new RateLimiter(EMOTE_LIMIT.burst, EMOTE_LIMIT.windowMs, EMOTE_LIMIT.cooldownMs);
  useGame.getState().set({ emoteBlockedUntil: 0 });
}

let unblockTimer: ReturnType<typeof setTimeout> | null = null;
function blockFor(ms: number): void {
  const until = Date.now() + ms;
  useGame.getState().set({ emoteBlockedUntil: until });
  if (unblockTimer) clearTimeout(unblockTimer);
  unblockTimer = setTimeout(() => useGame.getState().set({ emoteBlockedUntil: 0 }), ms);
}

/** Which colour a local (offline) emote comes from. */
function localSender(): Color | null {
  const s = useGame.getState();
  const v = s.view;
  if (!v || !s.localColors.length) return null;
  const cur = v.players[v.current];
  // Pass & play: whoever holds the device right now; vs AI: the human.
  if (s.matchKind === 'local' && s.localColors.includes(cur.color)) return cur.color;
  return s.localColors[0];
}

/** Send an emote: online through the server (which echoes it to everyone), offline directly. */
export async function sendEmote(id: EmoteId): Promise<void> {
  const s = useGame.getState();
  if (s.screen !== 'game' || !s.view) return;
  const wait = limiter.wait();
  if (wait > 0) {
    blockFor(wait);
    sfx('error');
    return;
  }
  limiter.take();
  blockFor(EMOTE_LIMIT.cooldownMs);
  if (s.matchKind === 'online') {
    await net.emote(id);
  } else {
    const color = localSender();
    if (color) showEmote(id, color);
  }
}
