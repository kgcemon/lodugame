import { chooseMove } from '../../shared/ai';
import { applyAction, createGame, distinctMoves, RuleError, validMoves } from '../../shared/engine';
import type { GameAction, GameState, RuleConfig, SeatSetup } from '../../shared/types';
import { sfx } from '../audio/audio';
import { isLocalTurn, useGame, type MatchKind } from '../store/game';
import { useSettings } from '../store/settings';
import { enqueue, onSettled, resetDirector, setView } from './director';
import { net } from './net';
import { clearReactions } from './emotes';
import { dice } from './runtime';

/**
 * Turn management for local matches (vs AI and pass-and-play) and the input
 * gate shared with online play. Every action is validated by the engine.
 */

let lastSetup: { kind: 'ai' | 'local'; seats: SeatSetup[]; rules: Partial<RuleConfig> } | null = null;
let aiTimer: ReturnType<typeof setTimeout> | null = null;

/** Fair d6 from the platform CSPRNG (rejection sampling avoids modulo bias). */
export function secureDie(): number {
  const buf = new Uint8Array(1);
  for (;;) {
    crypto.getRandomValues(buf);
    if (buf[0] < 252) return (buf[0] % 6) + 1;
  }
}

export function demoState(): GameState {
  const g = createGame([
    { color: 'red', name: 'Red', kind: 'ai' },
    { color: 'green', name: 'Green', kind: 'ai' },
    { color: 'yellow', name: 'Yellow', kind: 'ai' },
    { color: 'blue', name: 'Blue', kind: 'ai' },
  ]);
  g.players[0].tokens = [-1, 4, 21, -1];
  g.players[1].tokens = [9, -1, -1, 53];
  g.players[2].tokens = [-1, 30, -1, -1];
  g.players[3].tokens = [2, -1, 44, -1];
  return g;
}

export function showDemo(): void {
  resetDirector();
  clearAi();
  clearReactions();
  const g = demoState();
  useGame.getState().set({
    screen: 'menu',
    matchKind: null,
    game: null,
    paused: false,
    deadline: null,
    lastRoll: null,
    viewColor: 'blue',
    localColors: [],
  });
  setView(g);
  dice.api?.place('blue', 6);
}

export function startLocalMatch(kind: 'ai' | 'local', seats: SeatSetup[], rules: Partial<RuleConfig> = {}): void {
  resetDirector();
  clearAi();
  clearReactions();
  const g = createGame(seats, rules);
  const localColors = g.players.filter((p) => p.kind === 'human').map((p) => p.color);
  lastSetup = { kind, seats, rules };
  useGame.getState().set({
    screen: 'game',
    matchKind: kind,
    game: g,
    localColors,
    viewColor: localColors[0] ?? g.players[0].color,
    paused: false,
    lastRoll: null,
    deadline: null,
  });
  setView(g);
  dice.api?.place(g.players[0].color, null);
  afterSettle();
}

export function restartLocal(): void {
  if (lastSetup) startLocalMatch(lastSetup.kind, lastSetup.seats, lastSetup.rules);
}

function dispatch(action: GameAction): void {
  const s = useGame.getState();
  if (!s.game) return;
  try {
    const { state, events } = applyAction(s.game, action);
    s.set({ game: state });
    enqueue(state, events);
  } catch (err) {
    if (err instanceof RuleError) {
      sfx('error');
      s.toast(err.message);
    } else throw err;
  }
}

export function canRoll(): boolean {
  const s = useGame.getState();
  return !!s.view && !s.busy && !s.rollPending && !s.paused && s.view.phase === 'roll' && isLocalTurn(s);
}

export function requestRoll(): void {
  if (!canRoll()) return;
  const s = useGame.getState();
  s.set({ rollPending: true });
  if (s.matchKind === 'online') {
    void net.roll().then((ok) => {
      if (!ok) useGame.getState().set({ rollPending: false });
    });
  } else {
    dispatch({ type: 'roll', value: secureDie() });
  }
}

export function chooseToken(token: number): void {
  const s = useGame.getState();
  const v = s.view;
  if (!v || s.busy || s.rollPending || s.paused || v.phase !== 'move' || !isLocalTurn(s)) return;
  if (!validMoves(v).includes(token)) {
    sfx('error');
    return;
  }
  if (s.matchKind === 'online') {
    s.set({ rollPending: true });
    void net.move(token).then((ok) => {
      if (!ok) useGame.getState().set({ rollPending: false });
    });
  } else {
    dispatch({ type: 'move', token });
  }
}

function clearAi(): void {
  if (aiTimer) clearTimeout(aiTimer);
  aiTimer = null;
}

/** Called whenever the board has finished animating: schedule bots and auto-moves. */
export function afterSettle(): void {
  clearAi();
  const s = useGame.getState();
  const v = s.view;
  if (!v || v.phase === 'over' || s.paused || s.busy || !s.matchKind) return;
  const p = v.players[v.current];
  const seq = v.seq;
  const later = (ms: number, fn: () => void) => {
    aiTimer = setTimeout(() => {
      const n = useGame.getState();
      if (n.view?.seq !== seq || n.busy || n.paused) return;
      fn();
    }, ms);
  };

  if (s.matchKind !== 'online' && p.kind === 'ai') {
    // Pacing only — the decision itself is computed from the real state.
    if (v.phase === 'roll') later(600, () => dispatch({ type: 'roll', value: secureDie() }));
    else later(420, () => dispatch({ type: 'move', token: chooseMove(v, p.aiLevel) }));
    return;
  }
  if (isLocalTurn(s) && v.phase === 'move' && useSettings.getState().autoMove) {
    const d = distinctMoves(v);
    if (d.length === 1) later(350, () => chooseToken(d[0]));
  }
}

export function setPaused(paused: boolean): void {
  useGame.getState().set({ paused });
  if (paused) clearAi();
  else afterSettle();
}

export function leaveMatch(): void {
  const kind: MatchKind | null = useGame.getState().matchKind;
  if (kind === 'online') void net.leave();
  useGame.getState().set({ room: null, chatOpen: false, emoteOpen: false, settlement: null });
  showDemo();
  useGame.getState().set({ pendingNav: kind === 'online' ? '/' : '/play' });
}

onSettled(afterSettle);
