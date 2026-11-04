import type { EmoteId } from './social';
import type { RewardEvent } from './gifts';
import type { MissedGift, PlayerGiftEvent } from './playergifts';
import type { Color, GameEvent, GameState, PlayerKind } from './types';

/** Socket.IO contract shared by the client and the authoritative server. */

export type RoomStatus = 'lobby' | 'playing' | 'over';

/** How long a player who loses their connection mid-match has to come back. */
export const RECONNECT_GRACE_MS = 20_000;

/** How long the Match Found introduction holds the first turn: with coins going into the pool, and without (free rooms). */
export const MATCH_INTRO_MS = 6_000;
export const MATCH_INTRO_FREE_MS = 3_000;

export type SeatStatus = 'connected' | 'disconnected' | 'forfeited' | 'eliminated';

/** Why a player is out of a match: offline past the deadline, left it themselves, or removed by an administrator. */
export type ForfeitReason = 'timeout' | 'left' | 'removed';

/** What a player who is no longer in a match sees (built from the match record, so it survives a restart). */
export interface MatchResult {
  matchId: string;
  outcome: 'lost' | 'cancelled';
  /** 'server_restart' when the match itself was interrupted. */
  reason: ForfeitReason | 'server_restart';
  message: string;
  /** Everyone else who sat down. */
  opponents: string[];
  /** Null while the others still play, and when nobody won. */
  winnerName: string | null;
  status: 'in_progress' | 'completed' | 'abandoned';
  coinsPaid: number;
  coinsReceived: number;
  net: number;
  /** The same match type to queue for again; null when it can't be played again right now. */
  playAgain: { modeId: string; name: string; entryFee: number } | null;
}

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
  /** Mid-match: ms this player has left to reconnect before they're out (null while connected). */
  reconnectMsLeft: number | null;
  /** Out of the match: left it, or was offline too long. */
  left: boolean;
  /** Where this player stands in the match, decided by the server. */
  status: SeatStatus;
  isHost: boolean;
  /** 2 vs 2 team (0 = red + yellow, 1 = green + blue); null outside team rooms. */
  team: 0 | 1 | null;
  /** The dice skin this player shows (shared/skins.ts). */
  diceSkin: string;
}

/** One real participant in the Match Found introduction (bots say so: kind 'ai'). */
export interface MatchIntroPlayer {
  seatId: string;
  name: string;
  color: Color;
  kind: PlayerKind;
  publicId: string | null;
  avatarHue: number;
  avatarUrl: string | null;
  frame: string | null;
  country: string | null;
  /** Place on the wins leaderboard; null for bots and players without a win yet. */
  rank: number | null;
  /** Coins this player's seat actually paid into the pool (0 for bots and free rooms). */
  contribution: number;
}

/**
 * The confirmed match, as the server recorded it: the entry fees are in the ledger and the pool is
 * fixed before anyone sees it. Everyone in the match gets the same one, with the players in seat order.
 * The coins in the animation only show this; nothing a client does changes it.
 */
export interface MatchIntro {
  matchId: string;
  modeName: string;
  players: MatchIntroPlayer[];
  entryFee: number;
  /** What everyone paid in. */
  pool: number;
  /** What each winner is credited (multiplier x entry fee). */
  winnerReward: number;
  /** How many win: two partners in 2 vs 2. */
  winners: number;
  durationMs: number;
  /** Time left when this was sent: clients play from durationMs - msLeft, so they stay together and a reconnect picks up where it should. */
  msLeft: number;
}

export interface RoomView {
  code: string;
  matchId: string;
  isPublic: boolean;
  maxPlayers: number;
  /** 2 vs 2: seats 1 + 3 play against seats 2 + 4. */
  teams: boolean;
  status: RoomStatus;
  seats: SeatView[];
  /** Index of the receiving client's seat (-1 when not seated). */
  you: number;
  /** How long a player who loses their connection mid-match has to come back. */
  dropAfterMs: number;
  /** Quick play: ms until bots fill the empty seats and the match starts (null when not counting). */
  waitMsLeft: number | null;
  mode: { id: string; name: string };
  /** Official fee every player in this room paid (set by the server from game settings). */
  entryFee: number;
  payoutMultiplier: number;
  /** The board everyone plays on (shared/boards.ts): the host's paid board, else the paid board of whoever sat down first. */
  board: string;
  /** Whose board it is (their name), or null for the free classic board. */
  boardBy: string | null;
  /** The Match Found introduction while it plays (status 'playing', the first turn is held); null otherwise. */
  intro: MatchIntro | null;
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
  /** The match's prize pool: what everyone paid in. */
  pool: number;
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
  /**
   * Resume this player's session on a new socket. `results: true`: the client shows a MatchResult
   * from the reply and confirms it with match:resultSeen (older apps get match:dropped instead).
   */
  'session:hello': (p: { results?: boolean }, ack: AckFn<{ room: RoomView | null; result: MatchResult | null }>) => void;
  'match:resultSeen': (p: { matchId: string }, ack: AckFn) => void;
  'room:create': (p: { modeId: string; maxPlayers: number; teams?: boolean }, ack: AckFn<{ room: RoomView }>) => void;
  'room:join': (p: { code: string }, ack: AckFn<{ room: RoomView }>) => void;
  'room:quick': (p: { modeId: string }, ack: AckFn<{ room: RoomView }>) => void;
  'room:ready': (p: { ready: boolean }, ack: AckFn) => void;
  'room:addBot': (p: Record<string, never>, ack: AckFn) => void;
  'room:kick': (p: { seat: number }, ack: AckFn) => void;
  'room:start': (p: Record<string, never>, ack: AckFn) => void;
  /** Before the start: leave with a refund. During a match: forfeit at once (asking again is harmless). */
  'room:leave': (p: { results?: boolean }, ack: AckFn<{ result: MatchResult | null }>) => void;
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
  /** An empty round trip: how the connection feels, for the app's indicator. */
  'net:ping': (p: Record<string, never>, ack: AckFn) => void;
  /** Send a gift to a player in my match. `key`: a new one per tap on Send, the same one on a retry. */
  'playerGift:send': (p: { productId: string; toSeatId: string; key: string }, ack: AckFn<{ giftId: string; duplicate: boolean; balance: number; currency: string; price: number }>) => void;
  /** The gift has reached me. */
  'playerGift:seen': (p: { ids: string[] }, ack: AckFn) => void;
  /** Thank the sender of a gift I received, with an emote. */
  'playerGift:react': (p: { giftId: string; reaction: string }, ack: AckFn) => void;
}

export interface ServerToClientEvents {
  'room:state': (room: RoomView) => void;
  'room:closed': (reason: string) => void;
  'chat:message': (m: ChatMessage) => void;
  'chat:history': (m: ChatMessage[]) => void;
  'emote': (e: EmoteMessage) => void;
  'game:update': (u: GameUpdate) => void;
  'match:settled': (s: MatchSettled) => void;
  /** You were put out of a match for being offline too long (older apps, when they come back). */
  'match:dropped': (d: { message: string }) => void;
  /** This account opened the match on a newer connection: this one no longer plays it. */
  'session:replaced': () => void;
  /** Wallet numbers changed (entry fee, payout, refund, admin adjustment...). */
  'wallet': (w: { balance: number; totalEarned: number; totalSpent: number }) => void;
  /** Coins or diamonds the ledger just credited, for the app to animate (e.g. a match win). */
  'reward': (r: RewardEvent) => void;
  /** A new gift waits in your inbox. */
  'gift:new': (g: { giftId: string; title: string }) => void;
  /** A gift of yours changed: claimed on another phone, or cancelled by an administrator. */
  'gift:update': (g: { giftId: string; status: string }) => void;
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
  /** A friend came online; `best`: the friend you play with most. */
  'friend:online': (r: { player: FriendCard; played: number; best: boolean }) => void;
  /** A friend's voice packet, from their seat in your room. */
  'voice:frame': (seatId: string, data: ArrayBuffer | Uint8Array) => void;
  /** A gift on its way, for everyone in the match to watch; sent once per gift. */
  'playerGift:event': (e: PlayerGiftEvent) => void;
  /** Gifts that arrived while I was away (when I come back). */
  'playerGift:missed': (m: { gifts: MissedGift[] }) => void;
  /** Someone thanked a gift. */
  'playerGift:reaction': (r: { giftId: string; giftName: string; reaction: string; from: string }) => void;
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
