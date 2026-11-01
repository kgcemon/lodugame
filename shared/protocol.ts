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
  ready: boolean;
  connected: boolean;
  isHost: boolean;
}

export interface RoomView {
  code: string;
  isPublic: boolean;
  maxPlayers: number;
  status: RoomStatus;
  seats: SeatView[];
  /** Index of the receiving client's seat (-1 when not seated). */
  you: number;
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
  winnerName: string;
  winnerColor: Color;
  /** Coins credited to the winner (0 for free rooms or a bot winner). */
  payout: number;
  entryFee: number;
}

export interface GameUpdate {
  state: GameState;
  events: GameEvent[];
  /** Epoch ms when the current player's turn will be auto-played, or null. */
  deadline: number | null;
  serverTime: number;
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
  'room:create': (p: { modeId: string; maxPlayers: number }, ack: AckFn<{ room: RoomView }>) => void;
  'room:join': (p: { code: string }, ack: AckFn<{ room: RoomView }>) => void;
  'room:quick': (p: { modeId: string }, ack: AckFn<{ room: RoomView }>) => void;
  'room:ready': (p: { ready: boolean }, ack: AckFn) => void;
  'room:addBot': (p: Record<string, never>, ack: AckFn) => void;
  'room:kick': (p: { seat: number }, ack: AckFn) => void;
  'room:start': (p: Record<string, never>, ack: AckFn) => void;
  'room:leave': (p: Record<string, never>, ack: AckFn) => void;
  'game:roll': (p: Record<string, never>, ack: AckFn) => void;
  'game:move': (p: { token: number }, ack: AckFn) => void;
  'game:rematch': (p: Record<string, never>, ack: AckFn) => void;
  'chat:send': (p: { text: string; quick?: boolean }, ack: AckFn) => void;
  'emote:send': (p: { id: string }, ack: AckFn) => void;
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
}

export const ROOM_CODE_LENGTH = 5;
export const MAX_NAME_LENGTH = 16;
