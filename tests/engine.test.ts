import { describe, expect, it } from 'vitest';
import { FINISH, HOME_COLUMN, SAFE_INDICES, START_OFFSET, TRACK, absIndex, cellOf } from '../shared/board';
import { applyAction, createGame, distinctMoves, dropPlayer, RuleError, validMoves } from '../shared/engine';
import { chooseMove } from '../shared/ai';
import type { GameState, RuleConfig } from '../shared/types';
import { COLORS } from '../shared/types';

function game(count = 4, rules: Partial<RuleConfig> = {}): GameState {
  const colors = count === 2 ? ['red', 'yellow'] : count === 3 ? ['red', 'green', 'yellow'] : COLORS;
  return createGame(
    colors.map((c) => ({ color: c as (typeof COLORS)[number], name: c, kind: 'human' as const })),
    rules,
  );
}

function withTokens(s: GameState, player: number, tokens: number[]): GameState {
  s.players[player].tokens = tokens;
  return s;
}

function roll(s: GameState, v: number) {
  return applyAction(s, { type: 'roll', value: v });
}

describe('board geometry', () => {
  it('has a 52-cell loop where consecutive cells touch', () => {
    expect(TRACK).toHaveLength(52);
    expect(new Set(TRACK.map((c) => c.join(','))).size).toBe(52);
    for (let i = 0; i < 52; i++) {
      const [a, b] = [TRACK[i], TRACK[(i + 1) % 52]];
      expect(Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]))).toBe(1);
    }
  });

  it('places each start cell next to its yard and the home column after the entry cell', () => {
    expect(TRACK[START_OFFSET.red]).toEqual([1, 6]);
    expect(TRACK[START_OFFSET.green]).toEqual([8, 1]);
    expect(TRACK[START_OFFSET.yellow]).toEqual([13, 8]);
    expect(TRACK[START_OFFSET.blue]).toEqual([6, 13]);
    for (const c of COLORS) {
      const entry = cellOf(c, 50, 0);
      const first = HOME_COLUMN[c][0];
      expect(Math.abs(entry[0] - first[0]) + Math.abs(entry[1] - first[1])).toBe(1);
      expect(SAFE_INDICES).toContain(absIndex(c, 0));
    }
  });
});

describe('rules engine', () => {
  it('requires a six to leave the yard', () => {
    let s = game();
    const r = roll(s, 4);
    expect(r.events.map((e) => e.type)).toEqual(['rolled', 'noMoves', 'turn']);
    expect(r.state.current).toBe(1);
    s = roll(game(), 6).state;
    expect(s.phase).toBe('move');
    expect(validMoves(s)).toEqual([0, 1, 2, 3]);
    expect(distinctMoves(s)).toEqual([0]);
    const m = applyAction(s, { type: 'move', token: 2 });
    expect(m.state.players[0].tokens).toEqual([-1, -1, 0, -1]);
    expect(m.events.map((e) => e.type)).toEqual(['moved', 'extraTurn']);
    expect(m.state.current).toBe(0);
  });

  it('gives another roll on six even with no legal move', () => {
    const s = withTokens(game(), 0, [53, FINISH, FINISH, FINISH]);
    const r = roll(s, 6);
    expect(r.events.map((e) => e.type)).toEqual(['rolled', 'noMoves', 'extraTurn']);
    expect(r.state.current).toBe(0);
    expect(r.state.phase).toBe('roll');
  });

  it('forfeits the turn on the third consecutive six', () => {
    let s = withTokens(game(), 0, [10, -1, -1, -1]);
    s = applyAction(roll(s, 6).state, { type: 'move', token: 0 }).state;
    s = applyAction(roll(s, 6).state, { type: 'move', token: 0 }).state;
    expect(s.sixStreak).toBe(2);
    const r = roll(s, 6);
    expect(r.events.map((e) => e.type)).toEqual(['rolled', 'forfeitSixes', 'turn']);
    expect(r.state.current).toBe(1);
    expect(r.state.sixStreak).toBe(0);
  });

  it('passes the turn after a normal move', () => {
    const s = withTokens(game(), 0, [5, -1, -1, -1]);
    const r = applyAction(roll(s, 3).state, { type: 'move', token: 0 });
    expect(r.state.players[0].tokens[0]).toBe(8);
    expect(r.state.current).toBe(1);
    expect(r.events.at(-1)).toEqual({ type: 'turn', player: 1 });
  });

  it('captures an opponent on a normal square and grants an extra roll', () => {
    const s = game();
    // green progress 2 => absolute 15; red needs progress 15.
    withTokens(s, 0, [12, -1, -1, -1]);
    withTokens(s, 1, [2, -1, -1, -1]);
    expect(absIndex('green', 2)).toBe(absIndex('red', 15));
    const r = applyAction(roll(s, 3).state, { type: 'move', token: 0 });
    expect(r.state.players[1].tokens[0]).toBe(-1);
    expect(r.events.map((e) => e.type)).toEqual(['moved', 'captured', 'extraTurn']);
    expect(r.state.current).toBe(0);
  });

  it('cannot capture a pair (jora) with a single token: they share the square', () => {
    const s = game();
    withTokens(s, 0, [12, -1, -1, -1]);
    withTokens(s, 1, [2, 2, -1, -1]);
    const r = applyAction(roll(s, 3).state, { type: 'move', token: 0 });
    expect(r.state.players[1].tokens).toEqual([2, 2, -1, -1]);
    expect(r.state.players[0].tokens[0]).toBe(15);
    expect(r.events.some((e) => e.type === 'captured')).toBe(false);
    expect(r.state.current).toBe(1);
  });

  it('captures a lone token even when another player has a pair on the square', () => {
    const s = game();
    withTokens(s, 0, [12, -1, -1, -1]);
    withTokens(s, 1, [2, 2, -1, -1]);
    // yellow progress 41 => absolute 15 as well.
    expect(absIndex('yellow', 41)).toBe(absIndex('red', 15));
    withTokens(s, 2, [41, -1, -1, -1]);
    const r = applyAction(roll(s, 3).state, { type: 'move', token: 0 });
    expect(r.state.players[1].tokens).toEqual([2, 2, -1, -1]);
    expect(r.state.players[2].tokens[0]).toBe(-1);
  });

  it('does not capture on safe squares', () => {
    const s = game();
    // red progress 8 is a star square (absolute 8); blue at absolute 8 = progress 21.
    withTokens(s, 0, [5, -1, -1, -1]);
    withTokens(s, 3, [21, -1, -1, -1]);
    expect(absIndex('blue', 21)).toBe(8);
    const r = applyAction(roll(s, 3).state, { type: 'move', token: 0 });
    expect(r.state.players[3].tokens[0]).toBe(21);
    expect(r.events.some((e) => e.type === 'captured')).toBe(false);
  });

  it('captures on star squares when safe cells are disabled', () => {
    const s = game(4, { safeCells: false });
    withTokens(s, 0, [5, -1, -1, -1]);
    withTokens(s, 3, [21, -1, -1, -1]);
    const r = applyAction(roll(s, 3).state, { type: 'move', token: 0 });
    expect(r.state.players[3].tokens[0]).toBe(-1);
  });

  it('never captures inside a home column', () => {
    const s = game();
    withTokens(s, 0, [50, -1, -1, -1]);
    const r = applyAction(roll(s, 2).state, { type: 'move', token: 0 });
    expect(r.state.players[0].tokens[0]).toBe(52);
    expect(r.events.some((e) => e.type === 'captured')).toBe(false);
  });

  it('requires an exact roll to finish', () => {
    const s = withTokens(game(), 0, [54, -1, -1, -1]);
    const over = roll(s, 3);
    expect(over.events.map((e) => e.type)).toEqual(['rolled', 'noMoves', 'turn']);
    const exact = applyAction(roll(s, 2).state, { type: 'move', token: 0 });
    expect(exact.state.players[0].tokens[0]).toBe(FINISH);
    expect(exact.events.map((e) => e.type)).toEqual(['moved', 'tokenHome', 'extraTurn']);
  });

  it('declares the winner when the last token arrives', () => {
    const s = withTokens(game(2), 0, [FINISH, FINISH, FINISH, 55]);
    const r = applyAction(roll(s, 1).state, { type: 'move', token: 3 });
    expect(r.state.phase).toBe('over');
    expect(r.state.ranking[0]).toBe(0);
    expect(r.state.players[0].rank).toBe(1);
    expect(r.events.map((e) => e.type)).toEqual(['moved', 'tokenHome', 'playerFinished', 'gameOver']);
    expect(() => roll(r.state, 3)).toThrow(RuleError);
  });

  it('keeps playing for places in "all" mode and skips finished players', () => {
    const s = withTokens(game(3, { playUntil: 'all' }), 0, [FINISH, FINISH, FINISH, 55]);
    let r = applyAction(roll(s, 1).state, { type: 'move', token: 3 });
    expect(r.state.phase).toBe('roll');
    expect(r.state.current).toBe(1);
    // Player 1 rolls a 2 with nothing out — turn goes to 2, then back to 1 (skipping 0).
    r = roll(r.state, 2);
    expect(r.state.current).toBe(2);
    r = roll(r.state, 2);
    expect(r.state.current).toBe(1);
  });

  it('rejects out-of-turn and invalid actions', () => {
    const s = game();
    expect(() => applyAction(s, { type: 'move', token: 0 })).toThrow(RuleError);
    expect(() => roll(s, 7)).toThrow(RuleError);
    expect(() => roll(s, 2.5)).toThrow(RuleError);
    const m = roll(withTokens(game(), 0, [3, -1, -1, -1]), 4).state;
    expect(() => roll(m, 4)).toThrow(RuleError);
    expect(() => applyAction(m, { type: 'move', token: 1 })).toThrow(RuleError);
    expect(() => applyAction(m, { type: 'move', token: 9 })).toThrow(RuleError);
  });

  it('does not mutate the previous state', () => {
    const s = withTokens(game(), 0, [3, -1, -1, -1]);
    const snapshot = JSON.stringify(s);
    applyAction(roll(s, 4).state, { type: 'move', token: 0 });
    expect(JSON.stringify(s)).toBe(snapshot);
  });

  it('supports alternative entry values', () => {
    const s = game(4, { enterValues: [1, 6] });
    expect(roll(s, 1).state.phase).toBe('move');
  });

  it('seats two players on opposite corners', () => {
    expect(game(2).players.map((p) => p.color)).toEqual(['red', 'yellow']);
  });
});

describe('AI and full games', () => {
  function rng(seed: number) {
    return () => {
      seed = (seed * 1664525 + 1013904223) % 4294967296;
      return seed / 4294967296;
    };
  }

  it('prefers capturing and finishing on medium/hard', () => {
    const s = game();
    withTokens(s, 0, [12, 30, -1, -1]);
    withTokens(s, 1, [2, -1, -1, -1]);
    const st = roll(s, 3).state;
    expect(chooseMove(st, 'hard', rng(1))).toBe(0);
    expect(chooseMove(st, 'medium', rng(1))).toBe(0);
  });

  it('plays hundreds of complete games without an illegal state', () => {
    const r = rng(42);
    for (let g = 0; g < 200; g++) {
      const n = 2 + (g % 3);
      let s = game(n, { playUntil: g % 2 ? 'all' : 'first' });
      const levels = ['easy', 'medium', 'hard'] as const;
      let steps = 0;
      while (s.phase !== 'over') {
        if (s.phase === 'roll') s = roll(s, 1 + Math.floor(r() * 6)).state;
        else s = applyAction(s, { type: 'move', token: chooseMove(s, levels[s.current % 3], r) }).state;
        for (const p of s.players) for (const t of p.tokens) if (t < -1 || t > FINISH) throw new Error(`bad token ${t}`);
        if (++steps > 20000) throw new Error('game did not terminate');
      }
      expect(s.ranking.length).toBe(n);
      expect(s.players[s.ranking[0]].tokens.every((t) => t === FINISH)).toBe(true);
    }
  });
});

describe('2 vs 2 teams', () => {
  it('needs exactly four players', () => {
    expect(() => game(2, { teams: true })).toThrow(RuleError);
  });

  it('never captures a partner, but still captures rivals', () => {
    // Red lands on absolute square 31, where yellow (partner) sits at progress 5.
    let s = withTokens(withTokens(game(4, { teams: true }), 0, [28, -1, -1, -1]), 2, [5, -1, -1, -1]);
    let r = applyAction(roll(s, 3).state, { type: 'move', token: 0 });
    expect(r.events.some((e) => e.type === 'captured')).toBe(false);
    expect(r.state.players[2].tokens[0]).toBe(5);
    // Green (rival) on the same square is sent home.
    s = withTokens(withTokens(game(4, { teams: true }), 0, [28, -1, -1, -1]), 1, [18, -1, -1, -1]);
    r = applyAction(roll(s, 3).state, { type: 'move', token: 0 });
    expect(r.state.players[1].tokens[0]).toBe(-1);
  });

  it('plays on when one partner finishes and ranks the winning team first', () => {
    let s = withTokens(game(4, { teams: true }), 0, [FINISH, FINISH, FINISH, FINISH - 1]);
    s = applyAction(roll(s, 1).state, { type: 'move', token: 3 }).state;
    expect(s.phase).toBe('roll');
    expect(s.players[0].rank).toBe(1);
    // Green (other team) finishes next, then yellow brings red's team home.
    s.current = 1;
    s.players[1].tokens = [FINISH, FINISH, FINISH, FINISH - 2];
    s = applyAction(roll(s, 2).state, { type: 'move', token: 3 }).state;
    expect(s.phase).toBe('roll');
    s.current = 2;
    s.players[2].tokens = [FINISH, FINISH, FINISH, FINISH - 4];
    const end = applyAction(roll(s, 4).state, { type: 'move', token: 3 });
    expect(end.state.phase).toBe('over');
    expect(end.state.ranking.slice(0, 2).sort()).toEqual([0, 2]);
    expect(end.state.players[1].rank).toBe(3);
  });
});

describe('a player dropping out', () => {
  it('ends a 2-player match: the one left wins', () => {
    const { state, events } = dropPlayer(game(2), 0);
    expect(events.map((e) => e.type)).toEqual(['playerLeft', 'gameOver']);
    expect(state.phase).toBe('over');
    expect(state.ranking).toEqual([1, 0]);
  });

  it('in a 3-player match the others play on, skipping them, and they rank last', () => {
    let s = withTokens(game(3), 0, [5, -1, -1, FINISH]);
    s = dropPlayer(s, 0).state;
    expect(s.phase).toBe('roll');
    expect(s.current).toBe(1);
    expect(s.players[0].tokens).toEqual([-1, -1, -1, FINISH]);
    // Player 1 rolls a 1 with nothing out: the turn skips player 0.
    s = roll(s, 1).state;
    expect(s.current).toBe(2);
    s = roll(s, 1).state;
    expect(s.current).toBe(1);
    // Player 2 drops too: player 1 wins, player 0 (who left first) is still last.
    const end = dropPlayer(s, 2).state;
    expect(end.phase).toBe('over');
    expect(end.ranking[0]).toBe(1);
    expect(end.ranking).toHaveLength(3);
  });

  it('in 2 vs 2 the other team wins', () => {
    const { state } = dropPlayer(game(4, { teams: true }), 0);
    expect(state.phase).toBe('over');
    const winners = state.ranking.slice(0, 2).map((i) => state.players[i].color).sort();
    expect(winners).toEqual(['blue', 'green']);
  });
});
