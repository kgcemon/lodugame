import type { EmoteId } from './social';
import type { Color, GameEvent, GameState, PlayerKind } from './types';

/** Socket.IO contract shared by the client and the authoritative server. */

export type RoomStatus = 'lobby' | 'playing' | 'over';

export interface SeatView {
  id: string;
  name: string;
  color: Color;
  kind: PlayerKind;
  /** Public player id (for friend requests); null for bots. */
  publicId: string | null;
  avatarHue: number;
  /** Profile picture URL; null for bots and players without one. */
  avatarUrl: string | null;
  /** Profile frame (shared/frames.ts) around the picture, or null. */
  frame: string | null;
  /** Two-letter country of the player; null for bots and unknown (a globe). */
  country: string | null;
  ready: boolean;
  connected: boolean;
  isHost: boolean;
  /** 2 vs 2 team (0 = red + yellow, 1 = green + blue); null outside team rooms. */
  team: 0 | 1 | null;
  /** The dice skin this player shows (shared/skins.ts). */
  diceSkin: string;
}

export interface RoomView {
  code: string;
  isPublic: boolean;
  maxPlayers: number;
  /** 2 vs 2: seats 1 + 3 play against seats 2 + 4. */
  teams: boolean;
  status: RoomStatus;
  seats: SeatView[];
  /** Index of the receiving client's seat (-1 when not seated). */
  you: number;
  /** Quick play: ms until bots fill the empty seats and the match starts (null when not counting). */
  waitMsLeft: number | null;
  mode: { id: string; name: string };
  /** Official fee every player in this room paid (set by the server from game settings). */
  entryFee: number;
  payoutMultiplier: number;
}

/** What a player sees before confirming a join (and paying). */
export interface RoomPreview {
  code: string;
  mode: { id: string; name: string; description: string };
  entryFee: number;
  payoutMultiplier: number;
  players: number;
  maxPlayers: number;
  status: RoomStatus;
  isPublic: boolean;
  hostName: string | null;
  joinable: boolean;
}

export interface MatchSettled {
  /** The winner, or both partners ("A & B") in 2 vs 2. */
  winnerName: string;
  winnerColor: Color;
  /** Every winning colour (two in 2 vs 2). */
  winnerColors: Color[];
  /** Coins credited to each human winner (0 for free rooms or a bot winner). */
  payout: number;
  entryFee: number;
}

export interface GameUpdate {
  state: GameState;
  events: GameEvent[];
  /** Epoch ms when the current player's turn will be auto-played, or null. */
  deadline: number | null;
  serverTime: number;
  /**
   * The roll that can still be re-rolled with gems: whose it is, what the next re-roll
   * costs (null when the 3 re-rolls of this match are used up) and, when the turn has
   * already passed, how long the offer stays open. Null when nothing can be re-rolled.
   */
  reroll: { color: Color; nextCost: number | null; msLeft: number | null } | null;
}

/** A chat line. Sender identity is filled in by the server from the socket's seat. */
export interface ChatMessage {
  id: number;
  seatId: string;
  color: Color;
  name: string;
  text: string;
  at: number;
  quick: boolean;
  /** Team-only message for this 2 vs 2 team (only partners receive it); null = everyone. */
  team: 0 | 1 | null;
}

export interface EmoteMessage {
  id: EmoteId;
  seatId: string;
  color: Color;
  name: string;
  at: number;
}

export type Ack<T extends object = object> = ({ ok: true } & T) | { ok: false; error: string; code?: string };
export type AckFn<T extends object = object> = (res: Ack<T>) => void;

export interface ClientToServerEvents {
  'session:hello': (p: Record<string, never>, ack: AckFn<{ room: RoomView | null }>) => void;
  'room:create': (p: { modeId: string; maxPlayers: number; teams?: boolean }, ack: AckFn<{ room: RoomView }>) => void;
  'room:join': (p: { code: string }, ack: AckFn<{ room: RoomView }>) => void;
  'room:quick': (p: { modeId: string }, ack: AckFn<{ room: RoomView }>) => void;
  'room:ready': (p: { ready: boolean }, ack: AckFn) => void;
  'room:addBot': (p: Record<string, never>, ack: AckFn) => void;
  'room:kick': (p: { seat: number }, ack: AckFn) => void;
  'room:start': (p: Record<string, never>, ack: AckFn) => void;
  'room:leave': (p: Record<string, never>, ack: AckFn) => void;
  /** `seq`: the state version the action was made on (stale actions are refused). */
  'game:roll': (p: { seq?: number }, ack: AckFn) => void;
  /** Pay gems to roll your last roll again (5, 15, 45 per match; at most 3). */
  'game:reroll': (p: Record<string, never>, ack: AckFn<{ cost: number; gems: number }>) => void;
  'game:move': (p: { token: number; seq?: number }, ack: AckFn) => void;
  'game:rematch': (p: Record<string, never>, ack: AckFn) => void;
  /** `team: true` sends to your 2 vs 2 partner only. */
  'chat:send': (p: { text: string; quick?: boolean; team?: boolean }, ack: AckFn) => void;
  'emote:send': (p: { id: string }, ack: AckFn) => void;
  /** A packet of compressed voice (binary), heard by your friends in the room. */
  'voice:frame': (data: ArrayBuffer | Uint8Array) => void;
}

export interface ServerToClientEvents {
  'room:state': (room: RoomView) => void;
  'room:closed': (reason: string) => void;
  'chat:message': (m: ChatMessage) => void;
  'chat:history': (m: ChatMessage[]) => void;
  'emote': (e: EmoteMessage) => void;
  'game:update': (u: GameUpdate) => void;
  'match:settled': (s: MatchSettled) => void;
  /** Wallet numbers changed (entry fee, payout, refund, admin adjustment...). */
  'wallet': (w: { balance: number; totalEarned: number; totalSpent: number }) => void;
  'notify': (n: { id: string; type: string; payload: Record<string, unknown>; createdAt: string; readAt: string | null }) => void;
  'friends:changed': () => void;
  /** Someone asked to be your friend (a live Accept / Decline popup). */
  'friend:request': (r: { id: string; player: FriendCard }) => void;
  /** Your request was accepted: celebrate together. */
  'friend:accepted': (r: { id: string; player: FriendCard }) => void;
  /** Your request was declined. */
  'friend:rejected': (r: { id: string; player: FriendCard }) => void;
  /** A request to you was withdrawn: close its popup. */
  'friend:cancelled': (r: { id: string }) => void;
  /** A friend's voice packet, from their seat in your room. */
  'voice:frame': (seatId: string, data: ArrayBuffer | Uint8Array) => void;
}

/** A player's public card in the live friend popups. */
export interface FriendCard {
  username: string;
  publicId: string;
  avatarHue: number;
  avatarUrl: string | null;
  frame: string | null;
  country: string | null;
}

export const ROOM_CODE_LENGTH = 5;
export const MAX_NAME_LENGTH = 16;
