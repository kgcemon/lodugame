import { create } from 'zustand';
import type { ChatMessage, MatchSettled, RoomView } from '../../shared/protocol';
import type { Color, GameState } from '../../shared/types';

export type Screen = 'menu' | 'setup' | 'lobby' | 'game';
export type MatchKind = 'ai' | 'local' | 'online';
export type Modal = null | 'rules' | 'settings';
export type Connection = 'offline' | 'connecting' | 'online' | 'reconnecting';

export interface Toast {
  id: number;
  text: string;
  color?: Color;
}

interface GameStore {
  screen: Screen;
  setupPreset: 'ai' | 'local';
  matchKind: MatchKind | null;
  /** Authoritative state (local engine result or latest server update). */
  game: GameState | null;
  /** What the board currently shows; trails `game` while animations play. */
  view: GameState | null;
  busy: boolean;
  /** A roll was requested and its result has not been animated yet. */
  rollPending: boolean;
  /** Colours controlled from this device. */
  localColors: Color[];
  /** Colour whose yard the camera keeps at the bottom-left. */
  viewColor: Color;
  /** Local-clock epoch ms when the online turn auto-plays. */
  deadline: number | null;
  room: RoomView | null;
  connection: Connection;
  toasts: Toast[];
  paused: boolean;
  modal: Modal;
  lastRoll: { player: number; value: number } | null;
  /** Online room chat (most recent last). */
  chat: ChatMessage[];
  unread: number;
  chatOpen: boolean;
  emoteOpen: boolean;
  /** Seat ids whose chat and emotes this player has muted (this session only). */
  muted: string[];
  /** Message ids hidden locally. */
  hidden: number[];
  /** Local echo of the server's emote cooldown, for instant button feedback. */
  emoteBlockedUntil: number;
  /** Server's payout result for the match that just ended. */
  settlement: MatchSettled | null;
  /** A route the app should navigate to (set from outside React, e.g. by the socket). */
  pendingNav: string | null;
  set: (patch: Partial<GameStore>) => void;
  toast: (text: string, color?: Color) => void;
}

let toastId = 0;

export const useGame = create<GameStore>()((set) => ({
  screen: 'menu',
  setupPreset: 'ai',
  matchKind: null,
  game: null,
  view: null,
  busy: false,
  rollPending: false,
  localColors: [],
  viewColor: 'blue',
  deadline: null,
  room: null,
  connection: 'offline',
  toasts: [],
  paused: false,
  modal: null,
  lastRoll: null,
  chat: [],
  unread: 0,
  chatOpen: false,
  emoteOpen: false,
  muted: [],
  hidden: [],
  emoteBlockedUntil: 0,
  settlement: null,
  pendingNav: null,
  set: (patch) => set(patch),
  toast: (text, color) => {
    const id = ++toastId;
    set((s) => ({ toasts: [...s.toasts.slice(-2), { id, text, color }] }));
    setTimeout(() => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })), 2200);
  },
}));

/** Can this device act for the current player right now? */
export function isLocalTurn(s: Pick<GameStore, 'view' | 'localColors' | 'matchKind'>): boolean {
  const v = s.view;
  if (!v || v.phase === 'over') return false;
  const p = v.players[v.current];
  if (s.matchKind !== 'online' && p.kind === 'ai') return false;
  return s.localColors.includes(p.color);
}
