import { FINISH, HOME_START, TRACK_LEN, absIndex, isSafeIndex } from './board';
import { captureVictims, targetOf, validMoves } from './engine';
import type { AiLevel, GameState } from './types';

/**
 * Ludo AI. It only ever chooses among engine-validated moves, so it plays by
 * exactly the same rules as a human. Difficulty changes how much it looks at:
 *   easy   — mostly random, grabs obvious captures/finishes
 *   medium — greedy heuristic (capture, safety, progress)
 *   hard   — heuristic plus threat analysis of both the origin and target squares
 */

/** Probability-weighted number of opponent tokens able to hit absolute square `at` next turn. */
export function threatAt(state: GameState, playerIdx: number, at: number): number {
  if (state.config.safeCells && isSafeIndex(at)) return 0;
  let threat = 0;
  state.players.forEach((op, oi) => {
    if (oi === playerIdx || op.rank !== null) return;
    for (const prog of op.tokens) {
      const pos = absIndex(op.color, prog);
      if (pos === null) continue;
      const d = (at - pos + TRACK_LEN) % TRACK_LEN;
      // They must reach us before turning into their home column.
      if (d >= 1 && d <= 6 && prog + d < HOME_START) threat += 1 / 6;
      // Distances 7..12 need a six first — a smaller but real risk.
      else if (d >= 7 && d <= 12 && prog + d < HOME_START) threat += 1 / 36;
    }
  });
  return threat;
}

export function scoreMove(state: GameState, token: number, level: AiLevel): number {
  const pi = state.current;
  const player = state.players[pi];
  const from = player.tokens[token];
  const to = targetOf(from, state.dice!, state.config)!;
  let score = 0;

  if (to === FINISH) score += 120;
  if (from < 0) score += 55;
  if (from < HOME_START && to >= HOME_START) score += 45;

  const victims = to < HOME_START ? captureVictims(state, pi, to) : [];
  for (const v of victims) {
    const vp = state.players[v.player].tokens[v.token];
    score += 90 + vp * 1.2;
  }

  const toAbs = absIndex(player.color, to);
  if (toAbs !== null && state.config.safeCells && isSafeIndex(toAbs)) score += 22;
  if (player.tokens.some((t, i) => i !== token && t === to && to > 0 && to < FINISH)) score += 6;

  // Plain progress, nudging toward tokens that are already far along.
  score += (to - Math.max(from, 0)) * 1.0 + Math.max(from, 0) * 0.15;

  if (level === 'hard' || level === 'medium') {
    const value = 30 + Math.max(to, 0) * 0.9;
    if (toAbs !== null && victims.length === 0) score -= threatAt(state, pi, toAbs) * value * (level === 'hard' ? 1 : 0.6);
    else if (toAbs !== null) score -= threatAt(state, pi, toAbs) * value * 0.4;
    if (level === 'hard') {
      const fromAbs = absIndex(player.color, from);
      if (fromAbs !== null) score += threatAt(state, pi, fromAbs) * (30 + from * 0.9);
      // Keep a blocker near our start while others still need to leave the yard.
      if (from >= 0 && from < 6 && player.tokens.filter((t) => t < 0).length > 1) score -= 4;
    }
  }
  return score;
}

export function chooseMove(state: GameState, level: AiLevel, rng: () => number = Math.random): number {
  const moves = validMoves(state);
  if (moves.length === 0) throw new Error('chooseMove called with no valid moves');
  if (moves.length === 1) return moves[0];

  if (level === 'easy' && rng() < 0.55) return moves[Math.floor(rng() * moves.length)];

  let best = moves[0];
  let bestScore = -Infinity;
  for (const m of moves) {
    // Small jitter breaks ties without changing clear decisions.
    const s = scoreMove(state, m, level) + rng() * (level === 'hard' ? 0.5 : 3);
    if (s > bestScore) {
      bestScore = s;
      best = m;
    }
  }
  return best;
}
