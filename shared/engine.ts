import { FINISH, HOME_START, absIndex, isSafeIndex } from './board';
import type {
  Color,
  GameAction,
  GameEvent,
  GameState,
  PlayerState,
  RuleConfig,
  SeatSetup,
} from './types';
import { COLORS } from './types';

/**
 * The single source of truth for Ludo rules. Everything — the local game, the
 * AI, and the online server — goes through applyAction(). The engine is pure
 * and deterministic: dice values are supplied by the caller (a CSPRNG on the
 * server), so a given state + action always yields the same result.
 */

export const DEFAULT_RULES: RuleConfig = {
  enterValues: [6],
  extraTurnOnSix: true,
  maxConsecutiveSixes: 3,
  extraTurnOnCapture: true,
  extraTurnOnHome: true,
  safeCells: true,
  playUntil: 'first',
};

export class RuleError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'RuleError';
  }
}

/** Seat colours by player count, keeping opponents across from each other. */
export function colorsForCount(count: number): Color[] {
  if (count === 2) return ['red', 'yellow'];
  if (count === 3) return ['red', 'green', 'yellow'];
  if (count === 4) return [...COLORS];
  throw new RuleError('Ludo needs 2 to 4 players');
}

export function createGame(seats: SeatSetup[], rules: Partial<RuleConfig> = {}): GameState {
  if (seats.length < 2 || seats.length > 4) throw new RuleError('Ludo needs 2 to 4 players');
  const colors = new Set(seats.map((s) => s.color));
  if (colors.size !== seats.length) throw new RuleError('Each player needs a distinct colour');
  // Seat order always follows the clockwise colour order.
  const ordered = [...seats].sort((a, b) => COLORS.indexOf(a.color) - COLORS.indexOf(b.color));
  const players: PlayerState[] = ordered.map((s) => ({
    color: s.color,
    name: s.name,
    kind: s.kind,
    aiLevel: s.aiLevel ?? 'medium',
    tokens: [-1, -1, -1, -1],
    rank: null,
  }));
  return {
    config: { ...DEFAULT_RULES, ...rules },
    players,
    current: 0,
    phase: 'roll',
    dice: null,
    sixStreak: 0,
    ranking: [],
    turnNo: 1,
    seq: 0,
  };
}

/** Progress a token would reach with this roll, or null if it cannot move. */
export function targetOf(progress: number, dice: number, config: RuleConfig): number | null {
  if (progress === FINISH) return null;
  if (progress < 0) return config.enterValues.includes(dice) ? 0 : null;
  const to = progress + dice;
  return to <= FINISH ? to : null;
}

export function movableTokens(state: GameState, dice = state.dice): number[] {
  if (dice == null) return [];
  const p = state.players[state.current];
  const out: number[] = [];
  p.tokens.forEach((prog, i) => {
    if (targetOf(prog, dice, state.config) !== null) out.push(i);
  });
  return out;
}

export function validMoves(state: GameState): number[] {
  return state.phase === 'move' ? movableTokens(state) : [];
}

/** Opponent tokens that would be captured by landing on `to` (track progress for `color`). */
export function captureVictims(
  state: GameState,
  playerIdx: number,
  to: number,
): { player: number; token: number }[] {
  const color = state.players[playerIdx].color;
  const at = absIndex(color, to);
  if (at === null) return [];
  if (state.config.safeCells && isSafeIndex(at)) return [];
  const victims: { player: number; token: number }[] = [];
  state.players.forEach((op, oi) => {
    if (oi === playerIdx) return;
    op.tokens.forEach((prog, ti) => {
      if (absIndex(op.color, prog) === at) victims.push({ player: oi, token: ti });
    });
  });
  return victims;
}

function clone(state: GameState): GameState {
  return {
    ...state,
    config: { ...state.config, enterValues: [...state.config.enterValues] },
    players: state.players.map((p) => ({ ...p, tokens: [...p.tokens] })),
    ranking: [...state.ranking],
  };
}

function activeCount(state: GameState): number {
  return state.players.filter((p) => p.rank === null).length;
}

function passTurn(state: GameState, events: GameEvent[]): void {
  state.sixStreak = 0;
  state.dice = null;
  state.phase = 'roll';
  const n = state.players.length;
  let next = state.current;
  for (let i = 0; i < n; i++) {
    next = (next + 1) % n;
    if (state.players[next].rank === null) break;
  }
  state.current = next;
  state.turnNo += 1;
  events.push({ type: 'turn', player: next });
}

function finishGame(state: GameState, events: GameEvent[]): void {
  // Rank anyone still playing by how far their tokens have travelled.
  const remaining = state.players
    .map((p, i) => ({ i, score: p.tokens.reduce((a, t) => a + Math.max(t, 0) + (t >= 0 ? 1 : 0), 0) }))
    .filter(({ i }) => state.players[i].rank === null)
    .sort((a, b) => b.score - a.score);
  for (const { i } of remaining) {
    state.ranking.push(i);
    state.players[i].rank = state.ranking.length;
  }
  state.phase = 'over';
  state.dice = null;
  events.push({ type: 'gameOver', ranking: [...state.ranking] });
}

export function applyAction(prev: GameState, action: GameAction): { state: GameState; events: GameEvent[] } {
  if (prev.phase === 'over') throw new RuleError('The match is over');
  const state = clone(prev);
  const events: GameEvent[] = [];
  const pi = state.current;

  if (action.type === 'roll') {
    if (state.phase !== 'roll') throw new RuleError('You must move a token first');
    const v = action.value;
    if (!Number.isInteger(v) || v < 1 || v > 6) throw new RuleError('Invalid dice value');
    events.push({ type: 'rolled', player: pi, value: v });
    state.dice = v;
    state.sixStreak = v === 6 ? state.sixStreak + 1 : 0;

    const max = state.config.maxConsecutiveSixes;
    if (v === 6 && max > 0 && state.sixStreak >= max) {
      events.push({ type: 'forfeitSixes', player: pi });
      passTurn(state, events);
    } else if (movableTokens(state, v).length === 0) {
      events.push({ type: 'noMoves', player: pi });
      if (v === 6 && state.config.extraTurnOnSix) {
        state.dice = null;
        state.phase = 'roll';
        events.push({ type: 'extraTurn', player: pi, reason: 'six' });
      } else {
        passTurn(state, events);
      }
    } else {
      state.phase = 'move';
    }
    state.seq += 1;
    return { state, events };
  }

  if (action.type === 'move') {
    if (state.phase !== 'move' || state.dice == null) throw new RuleError('Roll the dice first');
    const player = state.players[pi];
    const ti = action.token;
    if (!Number.isInteger(ti) || ti < 0 || ti >= player.tokens.length) throw new RuleError('No such token');
    const from = player.tokens[ti];
    const to = targetOf(from, state.dice, state.config);
    if (to === null) throw new RuleError('That token cannot move');

    const victims = to < HOME_START ? captureVictims(state, pi, to) : [];
    player.tokens[ti] = to;
    events.push({ type: 'moved', player: pi, token: ti, from, to });

    for (const v of victims) {
      const vf = state.players[v.player].tokens[v.token];
      state.players[v.player].tokens[v.token] = -1;
      events.push({ type: 'captured', player: v.player, token: v.token, by: pi, from: vf });
    }

    const reachedHome = to === FINISH;
    if (reachedHome) events.push({ type: 'tokenHome', player: pi, token: ti });

    if (player.tokens.every((t) => t === FINISH)) {
      state.ranking.push(pi);
      player.rank = state.ranking.length;
      events.push({ type: 'playerFinished', player: pi, rank: player.rank });
      if (state.config.playUntil === 'first' || activeCount(state) <= 1) {
        finishGame(state, events);
        state.seq += 1;
        return { state, events };
      }
      passTurn(state, events);
      state.seq += 1;
      return { state, events };
    }

    let reason: 'six' | 'capture' | 'home' | null = null;
    if (victims.length > 0 && state.config.extraTurnOnCapture) reason = 'capture';
    else if (reachedHome && state.config.extraTurnOnHome) reason = 'home';
    else if (state.dice === 6 && state.config.extraTurnOnSix) reason = 'six';

    if (reason) {
      state.dice = null;
      state.phase = 'roll';
      events.push({ type: 'extraTurn', player: pi, reason });
    } else {
      passTurn(state, events);
    }
    state.seq += 1;
    return { state, events };
  }

  throw new RuleError('Unknown action');
}

/** Moves that land on the same square are interchangeable (e.g. leaving the yard). */
export function distinctMoves(state: GameState): number[] {
  const p = state.players[state.current];
  const seen = new Set<number>();
  const out: number[] = [];
  for (const t of validMoves(state)) {
    const key = p.tokens[t];
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(t);
  }
  return out;
}
