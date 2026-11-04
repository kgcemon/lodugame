import { randomBytes, randomInt } from 'node:crypto';
import type { Server, Socket } from 'socket.io';
import { chooseMove } from '../shared/ai';
import { applyAction, colorsForCount, concludeGame, createGame, distinctMoves, dropPlayer, RuleError, teamOf } from '../shared/engine';
import type {
  ClientToServerEvents,
  ForfeitReason,
  GameUpdate,
  MatchResult,
  RoomPreview,
  RoomStatus,
  RoomView,
  SeatStatus,
  ServerToClientEvents,
} from '../shared/protocol';
import { MAX_NAME_LENGTH, RECONNECT_GRACE_MS, ROOM_CODE_LENGTH, type ChatMessage } from '../shared/protocol';
import {
  botReaction,
  CHAT_HISTORY,
  CHAT_LIMIT,
  EMOTE_LIMIT,
  isEmoteId,
  normalizeChat,
  RateLimiter,
} from '../shared/social';
import type { AiLevel, Color, GameAction, GameEvent, GameState, PlayerKind } from '../shared/types';
import { COLORS } from '../shared/types';
import { DEFAULT_DICE_SKIN } from '../shared/skins';
import { DEFAULT_BOARD } from '../shared/boards';
import type { AuthUser } from './http';
import { MAX_REROLLS, REROLL_COSTS } from './gems';
import { NO_ELIGIBLE_PLAYERS, type MatchLedger } from './ledger';
import type { Presence } from './presence';
import { HttpError, WindowLimiter } from './security';
import type { MatchMode, Settings } from './settings';

export type IO = Server<ClientToServerEvents, ServerToClientEvents>;
export type ClientSocket = Socket<ClientToServerEvents, ServerToClientEvents>;

export interface Timings {
  /** How long a connected human has to act. */
  turnMs: number;
  /**
   * Mid-match, how long a player who lost their connection has to come back. The match
   * waits for them on their turn; after this they are out and the others play on (in a
   * 2-player match the opponent wins).
   */
  dropMs: number;
  /**
   * A connection must hold this long to earn a new dropMs: one that comes back and drops again
   * sooner carries on with the time it had left, so a flapping connection can't stall a match.
   */
  stableMs: number;
  /** Least time on the turn clock when a player comes back mid-turn (to see the board again). */
  resumeFloorMs: number;
  /** Pause before a bot acts, on top of the client animation time. */
  botThinkMs: number;
  /** Lobby seats of disconnected players are released after this. */
  lobbyGraceMs: number;
  /** Rooms with nobody connected are deleted after this. */
  emptyRoomMs: number;
  /** Multiplier for the estimated client animation time (0 in tests). */
  animScale: number;
  /** Pause before the only possible move is played for a human, after the roll has landed. */
  autoMoveMs: number;
  /** The same, when they could still pay to re-roll that roll (time to decide). */
  autoMoveRerollMs: number;
  /** Overrides the configured matchmaking wait (tests). */
  matchWaitMs?: number;
}

export const DEFAULT_TIMINGS: Timings = {
  turnMs: 8_000,
  dropMs: RECONNECT_GRACE_MS,
  stableMs: 10_000,
  resumeFloorMs: 2_000,
  botThinkMs: 650,
  lobbyGraceMs: 30_000,
  emptyRoomMs: 120_000,
  animScale: 1,
  autoMoveMs: 600,
  autoMoveRerollMs: 2_000,
};

/** One JSON line per game action when GAME_LOG=1 (room, match, turn, dice, moves; no secrets). */
const GAME_LOG = process.env.GAME_LOG === '1';

interface Seat {
  id: string;
  /** Authenticated user id (null for bots) — never broadcast. */
  playerId: string | null;
  publicId: string | null;
  avatarHue: number;
  name: string;
  kind: PlayerKind;
  aiLevel: AiLevel;
  ready: boolean;
  /** Seat reserved while the entry fee is being charged. */
  pending: boolean;
  socketId: string | null;
  connected: boolean;
  color: Color;
  /** Lobby: frees the seat; mid-match: puts the player out (see Timings.dropMs). */
  graceTimer?: NodeJS.Timeout;
  /** Mid-match: the server's deadline for this player to reconnect (null while connected). */
  graceDeadline: number | null;
  /** What was left of the last countdown when they came back, and when (see Timings.stableMs). */
  graceLeft: { ms: number; at: number } | null;
  /** Out of this match; `forfeitReason` says why. Their user id stays, for settlement. */
  left: boolean;
  forfeitReason: ForfeitReason | null;
  /** Gem re-rolls used this match, and the last known gem balance (null = not loaded). */
  rerolls: number;
  gems: number | null;
  /** Dice skin shown for this seat. */
  diceSkin: string;
  /** This player's board: the room plays on it while they host (or, with a free host, sat down first). */
  board: string;
  /** When this seat was taken (ms): who sat down first picks the board if the host has none. */
  seatedAt: number;
  avatarUrl: string | null;
  frame: string | null;
  country: string | null;
}

interface Room {
  code: string;
  hostPid: string | null;
  /** The board this match is played on (see roomBoard) when it started, locked until it ends, and whose it is. */
  matchBoard?: string;
  matchBoardBy?: string | null;
  isPublic: boolean;
  quick: boolean;
  mode: MatchMode;
  matchId: string;
  entryFee: number;
  payoutMultiplier: number;
  maxPlayers: number;
  /** 2 vs 2 (always 4 seats). */
  teams: boolean;
  status: RoomStatus;
  seats: Seat[];
  game: GameState | null;
  deadline: number | null;
  timer: NodeJS.Timeout | null;
  emptyTimer: NodeJS.Timeout | null;
  waitTimer: NodeJS.Timeout | null;
  /** When the quick-play wait runs out and bots fill the empty seats (epoch ms). */
  waitUntil: number | null;
  /** What was left of the current player's turn when their connection dropped. */
  pausedTurn: { seq: number; leftMs: number } | null;
  /**
   * The last player left in the race is offline: they win if they come back in time; if they
   * don't, nobody does.
   */
  heldWin: boolean;
  /** Match writes run one at a time, in the order they were decided here. */
  writes: Promise<unknown>;
  /** The last roll, which its (human) roller may still re-roll with gems. */
  rerollable: { color: Color; before: GameState; seqAfter: number; until: number | null } | null;
  /** A re-roll is being charged: hold every other action. */
  rerollBusy: boolean;
  /**
   * Players act on what they see: no roll or move is accepted until the clients have shown
   * the last action (most of its animation), so the next player can't roll while the
   * previous roll is still tumbling on everyone's screen.
   */
  readyAt: number;
  starting: boolean;
  lastEvents: GameEvent[];
  chat: ChatMessage[];
  chatSeq: number;
  /** Per-player limiters, keyed by the user id (not by socket). */
  limits: Map<string, { chat: RateLimiter; emote: RateLimiter }>;
  /** Delayed bot reactions; cleared with the room. */
  botTimers: Set<NodeJS.Timeout>;
}

const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const MAX_ROOMS = 1000;
/** Rooms one player may open or join per minute: each one is a database record. */
const ROOMS_PER_MIN = 30;
const BOT_NAMES = ['Nova', 'Atlas', 'Pixel', 'Orion', 'Echo', 'Vega', 'Blaze', 'Kiwi'];

export function sanitizeName(raw: unknown): string {
  if (typeof raw !== 'string') return 'Player';
  const clean = raw.replace(/[\u0000-\u001f\u007f<>]/g, '').trim().slice(0, MAX_NAME_LENGTH);
  return clean || 'Player';
}

/** Rough client animation time for a batch of events, so bots and timers wait for it. */
export function animationMs(events: GameEvent[]): number {
  let ms = 0;
  for (const e of events) {
    switch (e.type) {
      case 'rolled': ms += 1050; break;
      case 'moved': ms += 230 * Math.max(1, e.to - Math.max(e.from, 0)) + 250; break;
      case 'captured': ms += 750; break;
      case 'tokenHome': ms += 450; break;
      // Short: a roll that can't move anything should pass the turn quickly.
      case 'noMoves': case 'forfeitSixes': ms += 450; break;
      case 'extraTurn': ms += 350; break;
      case 'turn': ms += 300; break;
      default: break;
    }
  }
  return ms;
}

export class RoomManager {
  private rooms = new Map<string, Room>();
  private pidRoom = new Map<string, string>();
  /** Forfeits still being written: a result is read from the database only once they are done. */
  private forfeiting = new Map<string, Promise<unknown>>();
  private botCounter = 0;
  private roomLimit = new WindowLimiter(ROOMS_PER_MIN, 60_000);

  constructor(
    private io: IO,
    private ledger: MatchLedger,
    private settings: Settings,
    private presence: Presence,
    private t: Timings = DEFAULT_TIMINGS,
    private roll: () => number = () => randomInt(1, 7),
  ) {}

  get roomCount(): number {
    return this.rooms.size;
  }

  // ---------------------------------------------------------------- views

  private view(room: Room, pid: string | null): RoomView {
    return {
      code: room.code,
      matchId: room.matchId,
      isPublic: room.isPublic,
      maxPlayers: room.maxPlayers,
      teams: room.teams,
      status: room.status,
      mode: { id: room.mode.id, name: room.mode.name },
      entryFee: room.entryFee,
      payoutMultiplier: room.payoutMultiplier,
      ...this.boardOf(room),
      you: pid ? room.seats.findIndex((s) => s.playerId === pid) : -1,
      dropAfterMs: this.t.dropMs,
      waitMsLeft: room.waitUntil ? Math.max(0, room.waitUntil - Date.now()) : null,
      seats: room.seats.map((s, i) => ({
        id: s.id,
        name: s.name,
        color: s.color,
        kind: s.kind,
        publicId: s.publicId,
        avatarHue: s.avatarHue,
        ready: s.kind === 'ai' || ((s.ready || s.playerId === room.hostPid) && !s.pending),
        connected: s.kind === 'ai' || (s.connected && !s.pending),
        reconnectMsLeft: s.graceDeadline !== null ? Math.max(0, s.graceDeadline - Date.now()) : null,
        left: s.left,
        status: this.seatStatus(s),
        isHost: s.playerId !== null && s.playerId === room.hostPid,
        team: room.teams ? this.teamOfSeat(room, s, i) : null,
        diceSkin: s.diceSkin,
        avatarUrl: s.avatarUrl,
        frame: s.frame,
        country: s.country,
      })),
    };
  }

  private seatStatus(s: Seat): SeatStatus {
    if (s.left) return s.forfeitReason === 'timeout' ? 'eliminated' : 'forfeited';
    return s.graceDeadline !== null ? 'disconnected' : 'connected';
  }

  /** The board shown to the room: the locked one during a match, otherwise today's pick. */
  private boardOf(room: Room): { board: string; boardBy: string | null } {
    if (room.status !== 'lobby' && room.matchBoard) return { board: room.matchBoard, boardBy: room.matchBoardBy ?? null };
    const now = this.roomBoard(room);
    return { board: now.board, boardBy: now.by };
  }

  /**
   * The board the room plays on: the host's paid board; when the host has only the free one,
   * the paid board of whoever sat down first. A seat's board is only ever one its player owns
   * (the server looked it up from their profile); opponents never need to own it.
   */
  private roomBoard(room: Room): { board: string; by: string | null } {
    const host = room.seats.find((s) => s.playerId !== null && s.playerId === room.hostPid);
    const paid = room.seats
      .filter((s) => s.playerId !== null && !s.pending && s.board !== DEFAULT_BOARD)
      .sort((a, b) => a.seatedAt - b.seatedAt);
    const from = host && host.board !== DEFAULT_BOARD ? host : paid[0];
    return from ? { board: from.board, by: from.name } : { board: DEFAULT_BOARD, by: null };
  }

  preview(code: unknown): RoomPreview {
    if (typeof code !== 'string') throw new HttpError(400, 'Enter a room code');
    const room = this.rooms.get(code.trim().toUpperCase());
    if (!room) throw new HttpError(404, 'Room not found');
    const host = room.seats.find((s) => s.playerId === room.hostPid);
    return {
      code: room.code,
      mode: { id: room.mode.id, name: room.mode.name, description: room.mode.description },
      entryFee: room.entryFee,
      payoutMultiplier: room.payoutMultiplier,
      players: room.seats.length,
      maxPlayers: room.maxPlayers,
      status: room.status,
      isPublic: room.isPublic,
      hostName: host?.name ?? null,
      joinable: room.status === 'lobby' && room.seats.length < room.maxPlayers,
    };
  }

  /**
   * A seat's 2 vs 2 team. In the lobby it follows the seat order (1 + 3 against 2 + 4),
   * which is how colours are dealt at the start; in a match it follows the colour.
   */
  private teamOfSeat(room: Room, seat: Seat, index = room.seats.indexOf(seat)): 0 | 1 {
    return room.status === 'lobby' ? ((index % 2) as 0 | 1) : teamOf(seat.color);
  }

  /** Chat this player may read: everything for the room plus their own team's messages. */
  private chatFor(room: Room, seat: Seat): ChatMessage[] {
    if (!room.teams) return room.chat;
    const team = this.teamOfSeat(room, seat);
    return room.chat.filter((m) => m.team === null || m.team === team);
  }

  private broadcastRoom(room: Room): void {
    for (const s of room.seats) {
      if (s.socketId && s.playerId) this.io.to(s.socketId).emit('room:state', this.view(room, s.playerId));
    }
  }

  private update(room: Room, events: GameEvent[]): GameUpdate {
    return { state: room.game!, events, deadline: room.deadline, serverTime: Date.now(), reroll: this.rerollOffer(room) };
  }

  /** What the last roll's player could pay to roll again (sent to everyone; clients show it to its owner). */
  private rerollOffer(room: Room): GameUpdate['reroll'] {
    const r = room.rerollable;
    if (!r || !room.game || room.game.seq !== r.seqAfter || room.status !== 'playing') return null;
    if (r.until !== null && Date.now() >= r.until) return null;
    const seat = room.seats.find((s) => s.color === r.color);
    if (!seat || !seat.playerId) return null;
    return {
      color: r.color,
      nextCost: seat.rerolls < MAX_REROLLS ? REROLL_COSTS[seat.rerolls] : null,
      msLeft: r.until === null ? null : Math.max(0, r.until - Date.now()),
    };
  }

  // ---------------------------------------------------------------- session

  private user(socket: ClientSocket): AuthUser {
    const u = socket.data.user as AuthUser | undefined;
    if (!u) throw new RuleError('Please sign in');
    return u;
  }

  /**
   * Bring this socket back into the player's match. Identity is the signed-in session of the
   * socket (checked at connect), never anything the client says, and nothing from the client
   * reaches the match: they get the server's state.
   */
  hello(socket: ClientSocket): RoomView | null {
    const u = this.user(socket);
    const room = this.roomOf(u.id);
    if (!room) return null;
    const seat = room.seats.find((s) => s.playerId === u.id)!;
    // The deadline is the decision, whichever of this and its timer runs first.
    if (seat.graceDeadline !== null && Date.now() >= seat.graceDeadline) {
      this.forfeitSeat(room, seat, 'timeout');
      return null;
    }
    // A newer connection replaces an older one for the same player.
    if (seat.socketId && seat.socketId !== socket.id) {
      const old = this.io.sockets.sockets.get(seat.socketId);
      old?.leave(room.code);
      old?.emit('session:replaced');
    }
    seat.socketId = socket.id;
    seat.connected = true;
    if (seat.graceTimer) clearTimeout(seat.graceTimer);
    const wasOffline = seat.graceDeadline !== null;
    if (wasOffline) {
      const now = Date.now();
      seat.graceLeft = { ms: seat.graceDeadline! - now, at: now };
      seat.graceDeadline = null;
      const matchId = room.matchId;
      void this.write(room, () => this.ledger.markReconnected(matchId, u.id, now));
    }
    if (room.emptyTimer) {
      clearTimeout(room.emptyTimer);
      room.emptyTimer = null;
    }
    socket.join(room.code);
    socket.emit('chat:history', this.chatFor(room, seat));
    if (room.game) {
      if (room.heldWin) this.concludeHeld(room);
      else if (wasOffline && this.isCurrent(room, seat)) this.resumeTurn(room);
      socket.emit('game:update', this.update(room, []));
    }
    this.broadcastRoom(room);
    return this.view(room, u.id);
  }

  /** session:hello: the player's match, or else the result of the one they are no longer in. */
  async resume(socket: ClientSocket, wantsResult: boolean): Promise<{ room: RoomView | null; result: MatchResult | null }> {
    const room = this.hello(socket);
    if (room) return { room, result: null };
    const u = this.user(socket);
    await this.forfeiting.get(u.id);
    const result = await this.ledger.pendingResult(u.id);
    if (result && !wantsResult) {
      socket.emit('match:dropped', { message: result.message });
      await this.ledger.resultSeen(result.matchId, u.id);
    }
    return { room: null, result };
  }

  async resultSeen(socket: ClientSocket, matchId: unknown): Promise<void> {
    if (typeof matchId !== 'string' || !/^[0-9a-f-]{36}$/i.test(matchId)) throw new RuleError('Unknown match');
    await this.ledger.resultSeen(matchId, this.user(socket).id);
  }

  disconnect(socket: ClientSocket): void {
    const u = socket.data.user as AuthUser | undefined;
    if (!u) return;
    const room = this.roomOf(u.id);
    if (!room) return;
    const seat = room.seats.find((s) => s.playerId === u.id);
    if (!seat || seat.socketId !== socket.id) return;
    seat.connected = false;
    seat.socketId = null;
    if (room.status === 'lobby') {
      seat.graceTimer = setTimeout(() => {
        if (room.status === 'lobby' && !seat.connected && seat.playerId) void this.removeFromRoom(room, seat.playerId);
      }, this.t.lobbyGraceMs);
    } else if (room.status === 'playing' && room.game && room.game.phase !== 'over') {
      // The match waits for them on their turn; if they don't come back in time, they're out.
      const current = this.isCurrent(room, seat);
      if (current && room.deadline !== null) room.pausedTurn = { seq: room.game.seq, leftMs: Math.max(0, room.deadline - Date.now()) };
      this.beginGrace(room, seat);
      if (current) this.reschedule(room, 0);
      this.io.to(room.code).emit('game:update', this.update(room, []));
    }
    this.checkEmpty(room);
    this.broadcastRoom(room);
  }

  /** Start the server's reconnection countdown for a player whose connection dropped. */
  private beginGrace(room: Room, seat: Seat): void {
    const now = Date.now();
    const last = seat.graceLeft;
    const ms = last && now - last.at < this.t.stableMs ? last.ms : this.t.dropMs;
    const deadline = now + ms;
    seat.graceDeadline = deadline;
    this.armGrace(room, seat);
    const matchId = room.matchId;
    const pid = seat.playerId!;
    void this.write(room, () => this.ledger.markDisconnected(matchId, pid, now, deadline));
  }

  private armGrace(room: Room, seat: Seat): void {
    if (seat.graceTimer) clearTimeout(seat.graceTimer);
    seat.graceTimer = setTimeout(() => {
      if (seat.graceDeadline === null) return;
      // Timers can fire a hair early: the deadline itself is what counts.
      if (Date.now() < seat.graceDeadline) this.armGrace(room, seat);
      else this.forfeitSeat(room, seat, 'timeout');
    }, Math.max(0, seat.graceDeadline! - Date.now()));
  }

  /** Run a match write after those already decided, so a forfeit reaches the database before the settlement it led to. */
  private write(room: Room, job: () => Promise<unknown>): Promise<unknown> {
    room.writes = room.writes.then(job).catch((e) => console.error('[rooms] match write failed', e));
    return room.writes;
  }

  /**
   * A player is out of the match, for good: they left it, or the reconnection deadline passed.
   * The decision is made here, in one step, and nothing can reverse it; asking again changes
   * nothing. No refund. With two or more still in the race they play on, tokens untouched.
   * With one, they win, unless they are offline themselves: they then have to come back to
   * win (concludeHeld), and if they don't, nobody can. If only bots remain the match ends and
   * no coins are paid.
   */
  private forfeitSeat(room: Room, seat: Seat, reason: ForfeitReason): void {
    const g = room.game;
    if (!this.rooms.has(room.code) || room.status !== 'playing' || !g || g.phase === 'over' || seat.left || !seat.playerId) return;
    const pi = g.players.findIndex((p) => p.color === seat.color);
    const rest = g.players
      .filter((p, i) => i !== pi && p.rank === null && !p.left)
      .map((p) => room.seats.find((s) => s.color === p.color)!);
    const humans = rest.filter((s) => s.playerId);
    const ending = g.config.teams ? 'auto' : !humans.length ? 'end' : rest.length === 1 && !humans[0].connected ? 'hold' : 'auto';
    const { state, events } = dropPlayer(g, pi, ending);

    const pid = seat.playerId;
    const matchId = room.matchId;
    if (seat.graceTimer) clearTimeout(seat.graceTimer);
    seat.graceDeadline = null;
    seat.graceLeft = null;
    seat.left = true;
    seat.forfeitReason = reason;
    seat.connected = false;
    if (seat.socketId) this.io.sockets.sockets.get(seat.socketId)?.leave(room.code);
    seat.socketId = null;
    this.track(pid, null);
    if (room.hostPid === pid) room.hostPid = room.seats.find((s) => s.playerId && s.connected && !s.left)?.playerId ?? room.hostPid;

    const turnMoved = state.current !== g.current;
    room.game = state;
    room.lastEvents = events;
    room.rerollable = null;
    room.heldWin = ending === 'hold';
    if (turnMoved) room.pausedTurn = null;
    const now = Date.now();
    const connection = reason === 'timeout' && g.players.length > 2 ? 'eliminated' : 'forfeited';
    void this.write(room, () => this.ledger.forfeit(matchId, pid, reason, now, connection));
    if (state.phase === 'over') {
      this.endMatch(room);
      if (rest.length) this.settle(room, state, 'opponent_forfeit');
      else void this.write(room, () => this.ledger.abandon(matchId, NO_ELIGIBLE_PLAYERS, false));
    } else if (turnMoved) {
      this.reschedule(room, animationMs(events) * this.t.animScale);
    }
    this.io.to(room.code).emit('game:update', this.update(room, events));
    this.broadcastRoom(room);
    const done = room.writes.finally(() => {
      if (this.forfeiting.get(pid) === done) this.forfeiting.delete(pid);
    });
    this.forfeiting.set(pid, done);
    if (state.phase === 'over' && !room.seats.some((s) => s.playerId && !s.left)) this.deleteRoom(room, 'all players left');
    else this.checkEmpty(room);
  }

  /** The last player left in the race came back in time: they win. */
  private concludeHeld(room: Room): void {
    const { state, events } = concludeGame(room.game!);
    room.game = state;
    room.lastEvents = events;
    this.endMatch(room);
    this.settle(room, state, 'opponent_forfeit');
    this.io.to(room.code).emit('game:update', this.update(room, events));
  }

  /** The match is over: no more clocks run, for anyone. Whoever was still counting down can simply come back to see the result. */
  private endMatch(room: Room): void {
    room.status = 'over';
    room.heldWin = false;
    room.pausedTurn = null;
    if (room.timer) clearTimeout(room.timer);
    room.timer = null;
    room.deadline = null;
    for (const s of room.seats) {
      if (s.graceDeadline !== null && s.graceTimer) clearTimeout(s.graceTimer);
      s.graceDeadline = null;
      s.graceLeft = null;
    }
  }

  private isCurrent(room: Room, seat: Seat): boolean {
    const g = room.game;
    return !!g && g.phase !== 'over' && g.players[g.current].color === seat.color;
  }

  // ---------------------------------------------------------------- lobby

  private roomOf(pid: string): Room | undefined {
    const code = this.pidRoom.get(pid);
    return code ? this.rooms.get(code) : undefined;
  }

  private newCode(): string {
    for (;;) {
      const bytes = randomBytes(ROOM_CODE_LENGTH);
      let code = '';
      for (const b of bytes) code += CODE_ALPHABET[b % CODE_ALPHABET.length];
      if (!this.rooms.has(code)) return code;
    }
  }

  private freeColor(room: Room): Color {
    return COLORS.find((c) => !room.seats.some((s) => s.color === c))!;
  }

  private seatFor(socket: ClientSocket, room: Room): Seat {
    const u = this.user(socket);
    return {
      id: randomBytes(6).toString('hex'),
      playerId: u.id,
      publicId: u.publicId,
      avatarHue: u.avatarHue,
      name: u.username,
      kind: 'human',
      aiLevel: 'medium',
      ready: false,
      pending: true,
      socketId: socket.id,
      connected: true,
      color: this.freeColor(room),
      graceDeadline: null,
      graceLeft: null,
      left: false,
      forfeitReason: null,
      rerolls: 0,
      gems: null,
      diceSkin: DEFAULT_DICE_SKIN,
      board: DEFAULT_BOARD,
      seatedAt: Date.now(),
      avatarUrl: u.avatarUrl,
      frame: null,
      country: u.country,
    };
  }

  /** Look up the dice skin, frame and board a newly seated player shows, then tell the room. */
  private loadCosmetics(room: Room, seat: Seat): void {
    if (!seat.playerId) return;
    this.ledger.cosmetics(seat.playerId).then(
      (c) => {
        if (seat.diceSkin === c.diceSkin && seat.frame === c.frame && seat.board === c.board) return;
        seat.diceSkin = c.diceSkin;
        seat.frame = c.frame;
        seat.board = c.board;
        if (this.rooms.has(room.code)) this.broadcastRoom(room);
      },
      () => {},
    );
  }

  /** A player put on another dice skin, frame or board: show it at once if they are seated somewhere. */
  setCosmetics(userId: string, c: { diceSkin?: string | null; frame?: string | null; board?: string | null }): void {
    const room = this.roomOf(userId);
    const seat = room?.seats.find((s) => s.playerId === userId);
    if (!room || !seat) return;
    const diceSkin = c.diceSkin ?? seat.diceSkin;
    const frame = c.frame !== undefined ? c.frame : seat.frame;
    const board = c.board ?? seat.board;
    if (seat.diceSkin === diceSkin && seat.frame === frame && seat.board === board) return;
    seat.diceSkin = diceSkin;
    seat.frame = frame;
    seat.board = board;
    this.broadcastRoom(room);
  }

  private track(pid: string, room: Room | null): void {
    if (room) this.pidRoom.set(pid, room.code);
    else this.pidRoom.delete(pid);
    this.presence.setRoom(pid, room?.code ?? null);
  }

  /** Create a room backed by a new match row, seat the creator and charge their entry fee. */
  private async openRoom(socket: ClientSocket, mode: MatchMode, maxPlayers: number, isPublic: boolean, quick: boolean, teams = false): Promise<Room> {
    const u = this.user(socket);
    if (this.rooms.size >= MAX_ROOMS) throw new RuleError('Server is full, try again shortly');
    this.assertNotPlaying(u.id);
    this.takeRoomSlot(u.id);
    await this.ledger.assertCanPlay(u.id, mode.entryFee);
    await this.leave(socket);
    const code = this.newCode();
    const info = await this.ledger.createMatch({ roomCode: code, mode, maxPlayers, isPrivate: !isPublic, createdBy: u.id });
    const room: Room = {
      code,
      hostPid: u.id,
      isPublic,
      quick,
      mode,
      matchId: info.matchId,
      entryFee: info.entryFee,
      payoutMultiplier: info.payoutMultiplier,
      maxPlayers,
      teams,
      status: 'lobby',
      seats: [],
      game: null,
      deadline: null,
      timer: null,
      emptyTimer: null,
      waitTimer: null,
      waitUntil: null,
      pausedTurn: null,
      heldWin: false,
      writes: Promise.resolve(),
      rerollable: null,
      rerollBusy: false,
      readyAt: 0,
      starting: false,
      lastEvents: [],
      chat: [],
      chatSeq: 0,
      limits: new Map(),
      botTimers: new Set(),
    };
    const seat = this.seatFor(socket, room);
    room.seats.push(seat);
    this.rooms.set(code, room);
    this.loadCosmetics(room, seat);
    this.track(u.id, room);
    try {
      await this.ledger.join(room.matchId, u.id);
    } catch (e) {
      this.deleteRoom(room, 'creation failed');
      throw e;
    }
    seat.pending = false;
    socket.join(code);
    // Quick play: the bot-fill countdown starts with the first player, before anyone sees the room.
    if (quick) this.checkQuickStart(room);
    this.broadcastRoom(room);
    return room;
  }

  async create(socket: ClientSocket, modeId: unknown, maxPlayers: unknown, teams: unknown = false): Promise<RoomView> {
    const u = this.user(socket);
    const mode = this.settings.mode(modeId);
    const n = Number(maxPlayers);
    if (!Number.isInteger(n) || n < 2 || n > 4) throw new RuleError('Rooms hold 2 to 4 players');
    if (teams === true && n !== 4) throw new RuleError('2 vs 2 rooms hold exactly 4 players');
    if (teams === true && mode.maxPlayers < 4) throw new RuleError(`${mode.name} is a 2-player mode`);
    const room = await this.openRoom(socket, mode, n, false, false, teams === true);
    return this.view(room, u.id);
  }

  async join(socket: ClientSocket, rawCode: unknown): Promise<RoomView> {
    const u = this.user(socket);
    if (typeof rawCode !== 'string') throw new RuleError('Enter a room code');
    if (!this.rooms.has(rawCode.trim().toUpperCase())) this.takeRoomSlot(u.id); // guessing codes
    const code = rawCode.trim().toUpperCase();
    const room = this.rooms.get(code);
    if (!room) throw new RuleError('Room not found');
    if (room.seats.some((s) => s.playerId === u.id && s.left)) throw new RuleError('You are out of that match');
    if (room.seats.some((s) => s.playerId === u.id)) return this.hello(socket)!;
    if (room.status !== 'lobby' || room.starting) throw new RuleError('That match has already started');
    this.assertNotPlaying(u.id);
    this.takeRoomSlot(u.id);
    if (room.seats.length >= room.maxPlayers) throw new RuleError('Room is full');
    await this.ledger.assertCanPlay(u.id, room.entryFee);
    await this.leave(socket);
    // Re-check after the await: the room may have filled or started meanwhile.
    if (!this.rooms.has(code) || room.status !== 'lobby' || room.starting) throw new RuleError('That match has already started');
    if (room.seats.length >= room.maxPlayers) throw new RuleError('Room is full');
    const seat = this.seatFor(socket, room);
    room.seats.push(seat);
    this.track(u.id, room);
    this.loadCosmetics(room, seat);
    this.broadcastRoom(room);
    try {
      await this.ledger.join(room.matchId, u.id);
    } catch (e) {
      room.seats.splice(room.seats.indexOf(seat), 1);
      this.track(u.id, null);
      this.broadcastRoom(room);
      throw e;
    }
    if (!this.rooms.has(code) || !room.seats.includes(seat)) {
      // The room went away while we were charging: give the fee back.
      await this.ledger.leaveBeforeStart(room.matchId, u.id);
      throw new RuleError('The room was closed');
    }
    seat.pending = false;
    socket.join(room.code);
    socket.emit('chat:history', this.chatFor(room, seat));
    this.broadcastRoom(room);
    if (room.quick) this.checkQuickStart(room);
    return this.view(room, u.id);
  }

  private takeRoomSlot(pid: string): void {
    if (!this.roomLimit.take(pid)) throw new RuleError('Too many rooms, please wait a moment');
  }

  /** Starting something else never walks out of a running match: leaving one is an explicit, confirmed forfeit. */
  private assertNotPlaying(pid: string): void {
    if (this.roomOf(pid)?.status === 'playing') throw new RuleError('Finish or leave your current match first');
  }

  /** Public matchmaking for a mode: join a waiting room of that mode or open one. */
  async quick(socket: ClientSocket, modeId: unknown): Promise<RoomView> {
    const u = this.user(socket);
    const mode = this.settings.mode(modeId);
    if (mode.privateOnly) throw new RuleError(`${mode.name} is only available as a private room`);
    const current = this.roomOf(u.id);
    if (current?.quick && current.status === 'lobby' && current.mode.id === mode.id) return this.view(current, u.id);
    for (const room of this.rooms.values()) {
      if (
        room.quick &&
        room.mode.id === mode.id &&
        room.status === 'lobby' &&
        !room.starting &&
        room.seats.length < room.maxPlayers &&
        room.seats.some((s) => s.connected && !s.pending)
      ) {
        return this.join(socket, room.code);
      }
    }
    const room = await this.openRoom(socket, mode, mode.maxPlayers, true, true);
    return this.view(room, u.id);
  }

  /**
   * Quick rooms start when full. Otherwise the matchmaking wait starts with the first
   * player; when it runs out, empty seats are filled with bots and the match starts.
   */
  private checkQuickStart(room: Room): void {
    const seated = room.seats.filter((s) => !s.pending).length;
    if (seated >= room.maxPlayers) {
      void this.startGame(room).catch((e) => console.error('[rooms] quick start failed', e));
      return;
    }
    if (seated >= 1 && !room.waitTimer) {
      const ms = this.t.matchWaitMs ?? this.settings.get().matchmakingWaitSeconds * 1000;
      room.waitUntil = Date.now() + ms;
      room.waitTimer = setTimeout(() => {
        room.waitTimer = null;
        room.waitUntil = null;
        if (!this.rooms.has(room.code) || room.status !== 'lobby' || room.starting) return;
        if (!room.seats.some((s) => s.playerId && !s.pending)) return;
        if (room.seats.some((s) => s.pending)) {
          // Someone is paying for a seat right now: give them a moment, then try again.
          this.checkQuickStart(room);
          return;
        }
        // Nobody else came: fill the empty seats with bots so the player isn't kept waiting.
        while (room.seats.length < room.maxPlayers) room.seats.push(this.makeBot(room));
        this.broadcastRoom(room);
        void this.startGame(room).catch((e) => console.error('[rooms] quick start failed', e));
      }, ms);
    }
  }

  private makeBot(room: Room): Seat {
    return {
      id: randomBytes(6).toString('hex'),
      playerId: null,
      publicId: null,
      avatarHue: 0,
      name: `${BOT_NAMES[this.botCounter++ % BOT_NAMES.length]} (bot)`,
      kind: 'ai',
      aiLevel: 'medium',
      ready: true,
      pending: false,
      socketId: null,
      connected: true,
      color: this.freeColor(room),
      graceDeadline: null,
      graceLeft: null,
      left: false,
      forfeitReason: null,
      rerolls: 0,
      gems: null,
      diceSkin: DEFAULT_DICE_SKIN,
      board: DEFAULT_BOARD,
      seatedAt: Date.now(),
      avatarUrl: null,
      frame: null,
      country: null,
    };
  }

  setReady(socket: ClientSocket, ready: boolean): void {
    const { room, seat } = this.mySeat(socket);
    if (room.status !== 'lobby') throw new RuleError('Match already started');
    seat.ready = !!ready;
    this.broadcastRoom(room);
  }

  addBot(socket: ClientSocket): void {
    const { room } = this.hostRoom(socket);
    if (room.status !== 'lobby') throw new RuleError('Match already started');
    if (room.entryFee > 0) throw new RuleError('Bots can only join free Friendly rooms');
    if (room.seats.length >= room.maxPlayers) throw new RuleError('Room is full');
    room.seats.push(this.makeBot(room));
    this.broadcastRoom(room);
  }

  async kick(socket: ClientSocket, seatIdx: number): Promise<void> {
    const { room } = this.hostRoom(socket);
    if (room.status !== 'lobby') throw new RuleError('Match already started');
    const seat = room.seats[seatIdx];
    if (!Number.isInteger(seatIdx) || !seat) throw new RuleError('No such seat');
    if (seat.pending) throw new RuleError('That player is still joining');
    if (seat.playerId === room.hostPid) throw new RuleError('You cannot remove yourself');
    if (seat.playerId) {
      if (seat.socketId) {
        this.io.to(seat.socketId).emit('room:closed', 'You were removed from the room (entry fee refunded)');
        this.io.sockets.sockets.get(seat.socketId)?.leave(room.code);
      }
      await this.removeFromRoom(room, seat.playerId);
    } else {
      room.seats.splice(seatIdx, 1);
      this.broadcastRoom(room);
    }
  }

  async start(socket: ClientSocket): Promise<void> {
    const { room } = this.hostRoom(socket);
    if (room.status !== 'lobby' || room.starting) throw new RuleError('Match already started');
    if (room.seats.some((s) => s.pending)) throw new RuleError('A player is still joining');
    if (room.seats.length < 2) {
      throw new RuleError(room.entryFee > 0 ? 'Need at least 2 players — invite a friend' : 'Need at least 2 players — invite a friend or add a bot');
    }
    if (room.teams && room.seats.length !== 4) {
      throw new RuleError(room.entryFee > 0 ? '2 vs 2 needs 4 players — invite friends' : '2 vs 2 needs 4 players — invite friends or add bots');
    }
    const notReady = room.seats.filter((s) => s.kind === 'human' && s.playerId !== room.hostPid && !s.ready);
    if (notReady.length) throw new RuleError(`Waiting for ${notReady.map((s) => s.name).join(', ')} to be ready`);
    await this.startGame(room);
  }

  /** A rematch is a new match: everyone pays the entry fee again; anyone who can't is removed. */
  async rematch(socket: ClientSocket): Promise<void> {
    const { room } = this.hostRoom(socket);
    if (room.status !== 'over') throw new RuleError('The match is still running');
    this.takeRoomSlot(room.hostPid!);
    const info = await this.ledger.createMatch({
      roomCode: room.code,
      mode: room.mode,
      maxPlayers: room.maxPlayers,
      isPrivate: !room.isPublic,
      createdBy: room.hostPid!,
    });
    room.matchId = info.matchId;
    room.entryFee = info.entryFee;
    room.payoutMultiplier = info.payoutMultiplier;
    room.status = 'lobby';
    room.heldWin = false;
    room.pausedTurn = null;
    room.matchBoard = undefined;
    room.matchBoardBy = undefined;
    room.game = null;
    room.deadline = null;
    if (room.timer) clearTimeout(room.timer);
    room.timer = null;
    // Seats a bot took over are cleared from paid rooms; free rooms keep them.
    room.seats = room.seats.filter((s) => !s.left && (s.playerId || room.entryFee === 0));
    for (const s of [...room.seats]) {
      s.ready = false;
      if (!s.playerId) continue;
      try {
        await this.ledger.join(room.matchId, s.playerId);
      } catch (e) {
        room.seats.splice(room.seats.indexOf(s), 1);
        this.track(s.playerId, null);
        if (s.socketId) {
          this.io.to(s.socketId).emit('room:closed', `Rematch: ${(e as Error).message}`);
          this.io.sockets.sockets.get(s.socketId)?.leave(room.code);
        }
      }
    }
    if (room.hostPid && !room.seats.some((s) => s.playerId === room.hostPid)) room.hostPid = room.seats.find((s) => s.playerId)?.playerId ?? null;
    if (!room.seats.some((s) => s.playerId)) {
      this.deleteRoom(room, 'rematch failed');
      return;
    }
    this.broadcastRoom(room);
  }

  private async startGame(room: Room): Promise<void> {
    if (room.starting || room.status !== 'lobby') return;
    room.starting = true;
    if (room.waitTimer) clearTimeout(room.waitTimer);
    room.waitTimer = null;
    room.waitUntil = null;
    try {
      const colors = colorsForCount(room.seats.length);
      room.seats.forEach((s, i) => (s.color = colors[i]));
      await this.ledger.start(
        room.matchId,
        room.seats.filter((s) => s.playerId).map((s) => ({ userId: s.playerId!, color: s.color })),
        room.seats.map((s) => ({ color: s.color, name: s.name, kind: s.kind })),
      );
    } finally {
      room.starting = false;
    }
    if (!this.rooms.has(room.code)) return;
    room.game = createGame(room.seats.map((s) => ({ color: s.color, name: s.name, kind: s.kind, aiLevel: s.aiLevel })), { teams: room.teams });
    room.status = 'playing';
    // Lock the board: a host changing boards (or leaving) mid-match changes nothing, and
    // anyone reconnecting gets the same one.
    const locked = this.roomBoard(room);
    room.matchBoard = locked.board;
    room.matchBoardBy = locked.by;
    room.lastEvents = [];
    room.rerollable = null;
    for (const s of room.seats) s.rerolls = 0;
    // Someone who dropped in the lobby just before the start has their countdown start with the match.
    for (const s of room.seats) if (s.playerId && !s.connected) this.beginGrace(room, s);
    this.reschedule(room, 0);
    this.broadcastRoom(room);
    this.io.to(room.code).emit('game:update', this.update(room, []));
    // Gem balances decide whether a roll that passed the turn is held for a re-roll.
    for (const s of room.seats) {
      if (s.playerId) {
        const seat = s;
        this.ledger.gems(seat.playerId!).then((g) => (seat.gems = g), () => {});
      }
    }
  }

  /**
   * Leave the room: before the start with a refund, during a match as an immediate forfeit (the
   * reply carries the result). Asking again once out gets the same result and changes nothing.
   */
  async leave(socket: ClientSocket, wantsResult = false): Promise<{ result: MatchResult | null }> {
    const u = socket.data.user as AuthUser | undefined;
    if (!u) return { result: null };
    const room = this.roomOf(u.id);
    const matchId = room?.status === 'playing' ? room.matchId : null;
    if (room) {
      socket.leave(room.code);
      await this.removeFromRoom(room, u.id);
    }
    await this.forfeiting.get(u.id);
    const result = matchId ? await this.ledger.matchResult(matchId, u.id) : wantsResult ? await this.ledger.pendingResult(u.id) : null;
    // Older apps can't show it: it counts as seen.
    if (result && !wantsResult) await this.ledger.resultSeen(result.matchId, u.id);
    return { result };
  }

  private async removeFromRoom(room: Room, pid: string, reason: ForfeitReason = 'left'): Promise<void> {
    const idx = room.seats.findIndex((s) => s.playerId === pid);
    if (idx < 0) {
      this.track(pid, null);
      return;
    }
    const seat = room.seats[idx];
    if (room.status === 'playing') {
      this.forfeitSeat(room, seat, reason);
      return;
    }
    this.track(pid, null);
    if (seat.graceTimer) clearTimeout(seat.graceTimer);

    if (room.status === 'lobby') {
      room.seats.splice(idx, 1);
      await this.ledger.leaveBeforeStart(room.matchId, pid).catch((e) => console.error('[rooms] refund failed', e));
    } else {
      // The match is over: their seat stays on the final board.
      seat.playerId = null;
      seat.socketId = null;
      seat.kind = 'ai';
      seat.connected = true;
      seat.name = `${seat.name} (bot)`;
      const p = room.game?.players.find((pl) => pl.color === seat.color);
      if (p) {
        p.kind = 'ai';
        p.name = seat.name;
      }
    }

    if (room.hostPid === pid) room.hostPid = room.seats.find((s) => s.playerId && !s.pending && !s.left)?.playerId ?? null;
    if (!room.seats.some((s) => s.playerId && !s.left)) {
      this.deleteRoom(room, room.status === 'lobby' ? 'everyone left' : 'all players left');
      return;
    }
    this.broadcastRoom(room);
    if (room.game) this.io.to(room.code).emit('game:update', this.update(room, []));
  }

  private checkEmpty(room: Room): void {
    // During a match every absent player is on their own countdown.
    if (room.status === 'playing' || room.seats.some((s) => s.playerId && s.connected)) return;
    if (room.emptyTimer) return;
    room.emptyTimer = setTimeout(() => this.deleteRoom(room, 'everyone disconnected'), this.t.emptyRoomMs);
  }

  // ---------------------------------------------------------------- chat & emotes

  private limiter(room: Room, pid: string) {
    let l = room.limits.get(pid);
    if (!l) {
      l = {
        chat: new RateLimiter(CHAT_LIMIT.burst, CHAT_LIMIT.windowMs, CHAT_LIMIT.minGapMs),
        emote: new RateLimiter(EMOTE_LIMIT.burst, EMOTE_LIMIT.windowMs, EMOTE_LIMIT.cooldownMs),
      };
      room.limits.set(pid, l);
    }
    return l;
  }

  /**
   * Room chat. The sender is whoever owns this socket's seat — clients cannot name a sender.
   * In 2 vs 2 a team message reaches only the sender's own team.
   */
  chat(socket: ClientSocket, raw: unknown, quick: unknown, team: unknown = false): void {
    const { room, seat } = this.mySeat(socket);
    const text = normalizeChat(raw);
    if (!text) throw new RuleError('Message is empty or too long');
    if (team === true && !room.teams) throw new RuleError('Team chat is for 2 vs 2 rooms');
    if (!this.limiter(room, seat.playerId!).chat.take()) throw new RuleError('You are sending messages too fast');
    const myTeam = team === true ? this.teamOfSeat(room, seat) : null;
    const msg: ChatMessage = {
      id: ++room.chatSeq,
      seatId: seat.id,
      color: seat.color,
      name: seat.name,
      text,
      at: Date.now(),
      quick: quick === true,
      team: myTeam,
    };
    room.chat.push(msg);
    if (room.chat.length > CHAT_HISTORY) room.chat.splice(0, room.chat.length - CHAT_HISTORY);
    if (myTeam === null) {
      this.io.to(room.code).emit('chat:message', msg);
      return;
    }
    for (const s of room.seats) {
      if (s.socketId && s.playerId && this.teamOfSeat(room, s) === myTeam) this.io.to(s.socketId).emit('chat:message', msg);
    }
  }

  emote(socket: ClientSocket, id: unknown): void {
    const { room, seat } = this.mySeat(socket);
    if (!isEmoteId(id)) throw new RuleError('Unknown emote');
    if (room.status === 'lobby') throw new RuleError('Emotes are available during a match');
    if (!this.limiter(room, seat.playerId!).emote.take()) throw new RuleError('Emote cooling down');
    this.io.to(room.code).emit('emote', { id, seatId: seat.id, color: seat.color, name: seat.name, at: Date.now() });
  }

  /** Bots occasionally react to captures and finishes, after the move has been animated. */
  private scheduleBotReaction(room: Room, events: GameEvent[]): void {
    const g = room.game;
    if (!g) return;
    const r = botReaction(events, g.players, Math.random);
    if (!r) return;
    const seat = room.seats.find((s) => s.color === g.players[r.player].color);
    if (!seat || seat.kind !== 'ai') return;
    const t = setTimeout(() => {
      room.botTimers.delete(t);
      if (!this.rooms.has(room.code)) return;
      this.io.to(room.code).emit('emote', { id: r.id, seatId: seat.id, color: seat.color, name: seat.name, at: Date.now() });
    }, animationMs(events) * this.t.animScale * 0.85 + 150);
    room.botTimers.add(t);
  }

  /** Drop a room; its match is cancelled (refunds) before start or abandoned (no refunds) after. */
  private deleteRoom(room: Room, reason: string): void {
    for (const t of room.botTimers) clearTimeout(t);
    room.botTimers.clear();
    if (room.timer) clearTimeout(room.timer);
    if (room.emptyTimer) clearTimeout(room.emptyTimer);
    if (room.waitTimer) clearTimeout(room.waitTimer);
    for (const s of room.seats) {
      if (s.graceTimer) clearTimeout(s.graceTimer);
      // Someone already out of this match may be seated elsewhere by now.
      if (s.playerId && this.pidRoom.get(s.playerId) === room.code) this.track(s.playerId, null);
    }
    this.rooms.delete(room.code);
    const job =
      room.status === 'lobby'
        ? this.ledger.cancel(room.matchId, reason)
        : room.status === 'playing'
          ? this.ledger.abandon(room.matchId, reason, false)
          : Promise.resolve();
    job.catch((e) => console.error('[rooms] closing match failed', e));
  }

  // ---------------------------------------------------------------- gameplay

  /** `seq` (optional) is the state version the client acted on: a stale tap is refused. */
  rollDice(socket: ClientSocket, seq?: unknown): void {
    const { room } = this.myTurn(socket);
    this.checkSeq(room, seq);
    this.apply(room, { type: 'roll', value: this.roll() });
  }

  move(socket: ClientSocket, token: unknown, seq?: unknown): void {
    const { room } = this.myTurn(socket);
    this.checkSeq(room, seq);
    if (typeof token !== 'number') throw new RuleError('Invalid token');
    this.apply(room, { type: 'move', token });
  }

  private checkSeq(room: Room, seq: unknown): void {
    if (typeof seq === 'number' && room.game && seq !== room.game.seq) throw new RuleError('That turn has already moved on');
  }

  /**
   * Pay gems to roll the last roll again. Allowed until the roller moves, or — when the
   * roll already passed the turn — during a short hold before the next player can act.
   * The game goes back to the moment before that roll and a fresh server roll is applied.
   */
  async reroll(socket: ClientSocket): Promise<{ cost: number; gems: number }> {
    const { room, seat } = this.mySeat(socket);
    const r = room.rerollable;
    if (!room.game || room.status !== 'playing') throw new RuleError('No match in progress');
    if (!r || r.color !== seat.color || room.game.seq !== r.seqAfter || (r.until !== null && Date.now() >= r.until)) {
      throw new RuleError('There is no roll to re-roll right now');
    }
    if (room.rerollBusy) throw new RuleError('Re-rolling…');
    if (seat.rerolls >= MAX_REROLLS) throw new RuleError(`You can re-roll at most ${MAX_REROLLS} times per match`);
    room.rerollBusy = true;
    // Freeze the game while the gems are charged.
    if (room.timer) clearTimeout(room.timer);
    room.timer = null;
    let charge: { attempt: number; cost: number; gems: number };
    try {
      charge = await this.ledger.chargeReroll(room.matchId, seat.playerId!);
    } catch (e) {
      room.rerollBusy = false;
      if (this.rooms.has(room.code) && room.status === 'playing') this.reschedule(room, 0);
      throw e;
    }
    room.rerollBusy = false;
    seat.rerolls = charge.attempt;
    seat.gems = charge.gems;
    if (!this.rooms.has(room.code) || room.status !== 'playing' || room.game.seq !== r.seqAfter) {
      // The game moved on while we were charging: give the gems back.
      await this.ledger.refundReroll(room.matchId, seat.playerId!, charge.attempt, charge.cost).catch((e) => console.error('[rooms] reroll refund failed', e));
      seat.rerolls = charge.attempt - 1;
      throw new RuleError('Too late — the game moved on (diamonds refunded)');
    }
    const seq = room.game.seq;
    this.apply(room, { type: 'roll', value: this.roll() }, r.before, seq + 1);
    return { cost: charge.cost, gems: charge.gems };
  }

  /** Hold before the next player may act after a roll its owner could still re-roll. */
  private static readonly REROLL_HOLD_MS = 1500;

  /**
   * Apply an action. `base` replaces the current state as the starting point (re-rolls
   * go back to before the last roll) and `seq` keeps the sequence counter moving forward.
   */
  private apply(room: Room, action: GameAction, base?: GameState, seq?: number, auto = false): void {
    const prev = base ?? room.game!;
    const actor = prev.players[prev.current];
    const { state, events } = applyAction(prev, action);
    if (seq !== undefined) state.seq = seq;
    if (GAME_LOG) {
      console.log(JSON.stringify({
        t: 'game', at: new Date().toISOString(), room: room.code, match: room.matchId, seq: state.seq, turn: prev.turnNo,
        player: actor.color, kind: actor.kind, action: action.type, auto, ...(action.type === 'move' ? { token: action.token } : {}),
        events: events.map((e) => e.type === 'rolled' ? `rolled:${e.value}` : e.type === 'moved' ? `moved:${e.token}:${e.from}>${e.to}` : e.type),
        next: state.phase === 'over' ? 'over' : `${state.players[state.current].color}:${state.phase}`,
      }));
    }
    room.game = state;
    room.lastEvents = events;
    if (state.phase === 'over') this.endMatch(room);

    // A human's roll can be re-rolled with gems. If the roll already handed the turn on,
    // hold the next player briefly so the roller has time to decide.
    room.rerollable = null;
    let hold = 0;
    const seat = room.seats.find((s) => s.color === actor.color);
    if (action.type === 'roll' && seat?.playerId && actor.kind === 'human' && state.phase !== 'over') {
      const stillMine = state.current === prev.current && state.phase === 'move';
      const canAfford = seat.rerolls < MAX_REROLLS && (seat.gems ?? 0) >= REROLL_COSTS[seat.rerolls];
      if (stillMine || canAfford) {
        if (!stillMine) hold = RoomManager.REROLL_HOLD_MS;
        const animMs = animationMs(events) * this.t.animScale;
        room.rerollable = { color: actor.color, before: prev, seqAfter: state.seq, until: stillMine ? null : Date.now() + animMs + hold };
      }
    }
    room.readyAt = Date.now() + Math.round(animationMs(events) * this.t.animScale * 0.6);
    this.reschedule(room, animationMs(events) * this.t.animScale + hold);
    this.io.to(room.code).emit('game:update', this.update(room, events));
    this.scheduleBotReaction(room, events);
    if (room.status === 'over') {
      this.broadcastRoom(room);
      this.settle(room, state);
    }
  }

  /** The winner comes from the server's own game state, never from a client. */
  private settle(room: Room, state: GameState, endReason = 'finished'): void {
    const ranking = state.ranking.map((pi) => {
      const seat = room.seats.find((s) => s.color === state.players[pi].color);
      return seat && seat.kind === 'human' && seat.playerId ? seat.playerId : null;
    });
    const winnerColor = state.players[state.ranking[0]].color;
    // 2 vs 2: both partners win (they are ranked 1st and 2nd).
    const winners = state.config.teams ? 2 : 1;
    const winnerColors = state.ranking.slice(0, winners).map((pi) => state.players[pi].color);
    const matchId = room.matchId;
    const entryFee = room.entryFee;
    const winnerName = winnerColors.map((c) => room.seats.find((s) => s.color === c)?.name ?? 'Unknown').join(' & ');
    void this.write(room, async () => {
      const res = await this.ledger.settle(matchId, ranking, winnerColor, winners, endReason);
      if (res) this.io.to(room.code).emit('match:settled', { winnerName, winnerColor, winnerColors, payout: res.payout, entryFee });
    });
  }

  /** Back mid-turn: the turn carries on with the clock it had when the connection dropped. */
  private resumeTurn(room: Room): void {
    const paused = room.pausedTurn;
    room.pausedTurn = null;
    const left = paused && paused.seq === room.game!.seq ? Math.max(paused.leftMs, this.t.resumeFloorMs) : undefined;
    this.reschedule(room, 0, left);
  }

  /** Arm the timer that auto-plays for bots and expired turns (`turnLeftMs` overrides a fresh turn's clock). */
  private reschedule(room: Room, animMs: number, turnLeftMs?: number): void {
    if (room.timer) clearTimeout(room.timer);
    room.timer = null;
    room.deadline = null;
    const g = room.game;
    if (!g || g.phase === 'over') return;
    const p = g.players[g.current];
    const seat = room.seats.find((s) => s.color === p.color);
    let wait: number;
    if (p.kind === 'ai' || !seat) {
      wait = animMs + this.t.botThinkMs;
    } else if (seat.graceDeadline !== null) {
      // Their connection dropped: nothing is played for them, the match waits (forfeitSeat ends the wait).
      room.deadline = seat.graceDeadline;
      return;
    } else if (g.phase === 'move' && distinctMoves(g).length === 1) {
      // Only one possible move: the server plays it, once, as soon as the roll has landed
      // (a little later if they could still pay to re-roll that roll).
      const r = room.rerollable;
      const canReroll = r?.color === p.color && seat.rerolls < MAX_REROLLS && (seat.gems ?? 0) >= REROLL_COSTS[seat.rerolls];
      wait = animMs + (canReroll ? this.t.autoMoveRerollMs : this.t.autoMoveMs);
      room.deadline = Date.now() + wait;
    } else {
      wait = turnLeftMs ?? animMs + this.t.turnMs;
      room.deadline = Date.now() + wait;
    }
    room.timer = setTimeout(() => this.autoPlay(room), wait);
  }

  private autoPlay(room: Room): void {
    room.timer = null;
    const g = room.game;
    if (!g || g.phase === 'over' || !this.rooms.has(room.code)) return;
    const p = g.players[g.current];
    try {
      if (g.phase === 'roll') {
        this.apply(room, { type: 'roll', value: this.roll() }, undefined, undefined, true);
      } else {
        const only = distinctMoves(g);
        const token = only.length === 1 ? only[0] : chooseMove(g, p.kind === 'ai' ? p.aiLevel : 'medium');
        this.apply(room, { type: 'move', token }, undefined, undefined, true);
      }
    } catch (err) {
      console.error('[rooms] autoplay failed', err);
    }
  }

  // ---------------------------------------------------------------- guards

  /**
   * Who could hear this socket's voice: the other people seated (and connected) in its room.
   * Null when the socket isn't seated. Friendship is checked by the voice relay.
   */
  voicePeers(socket: ClientSocket): { playerId: string; seatId: string; peers: { playerId: string; socketId: string }[] } | null {
    const u = socket.data.user as AuthUser | undefined;
    if (!u) return null;
    const room = this.roomOf(u.id);
    const seat = room?.seats.find((s) => s.playerId === u.id);
    if (!room || !seat || seat.pending || seat.socketId !== socket.id) return null;
    const peers = room.seats
      .filter((s) => s !== seat && s.kind === 'human' && s.playerId && s.socketId && s.connected && !s.pending)
      .map((s) => ({ playerId: s.playerId!, socketId: s.socketId! }));
    return { playerId: u.id, seatId: seat.id, peers };
  }

  private mySeat(socket: ClientSocket): { room: Room; seat: Seat } {
    const u = this.user(socket);
    const room = this.roomOf(u.id);
    const seat = room?.seats.find((s) => s.playerId === u.id);
    if (!room || !seat || seat.pending) throw new RuleError('You are not in a room');
    if (seat.socketId !== socket.id) throw new RuleError('This room is open in another tab');
    return { room, seat };
  }

  private hostRoom(socket: ClientSocket): { room: Room; seat: Seat } {
    const r = this.mySeat(socket);
    if (r.room.hostPid !== r.seat.playerId) throw new RuleError('Only the host can do that');
    return r;
  }

  private myTurn(socket: ClientSocket): { room: Room; seat: Seat } {
    const r = this.mySeat(socket);
    const g = r.room.game;
    if (!g || r.room.status !== 'playing') throw new RuleError('No match in progress');
    if (g.players[g.current].color !== r.seat.color) throw new RuleError('It is not your turn');
    if (r.room.rerollBusy) throw new RuleError('Wait a moment…');
    if (Date.now() < r.room.readyAt) throw new RuleError('Wait a moment…');
    // The previous player may still re-roll their last roll for a moment.
    const rr = r.room.rerollable;
    if (rr && rr.color !== r.seat.color && rr.until !== null && Date.now() < rr.until && g.seq === rr.seqAfter) {
      throw new RuleError('Wait a moment…');
    }
    return r;
  }

  /** Remove a suspended user from any room they're in. */
  async evict(userId: string): Promise<void> {
    const room = this.roomOf(userId);
    if (room) await this.removeFromRoom(room, userId, 'removed');
  }

  shutdown(): void {
    // Rooms stay "open" in the database; recovery on next boot refunds them.
    for (const room of this.rooms.values()) {
      for (const t of room.botTimers) clearTimeout(t);
      if (room.timer) clearTimeout(room.timer);
      if (room.emptyTimer) clearTimeout(room.emptyTimer);
      if (room.waitTimer) clearTimeout(room.waitTimer);
      for (const s of room.seats) if (s.graceTimer) clearTimeout(s.graceTimer);
    }
    this.rooms.clear();
    this.pidRoom.clear();
  }
}
