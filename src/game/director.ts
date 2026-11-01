import { FINISH } from '../../shared/board';
import { winnersOf } from '../../shared/engine';
import type { GameEvent, GameState } from '../../shared/types';
import { botReaction } from '../../shared/social';
import { sfx } from '../audio/audio';
import { useGame } from '../store/game';
import { useSettings } from '../store/settings';
import { computeTargets, PLAYER_HEX, pointFor, tokenKey } from '../three/layout';
import { cancelAll, currentGeneration, ease, tween, wait } from './anim';
import { showEmote } from './emotes';
import { cameraFocus, dice, effects, syncTargets, tokenRuntime, tokens } from './runtime';

/**
 * Plays engine events as animations, in order. The authoritative state is
 * already final when a batch arrives; the director walks the visible board
 * ("view") through each event so tokens travel every intermediate square,
 * then commits the authoritative state. Animations never change game rules —
 * they only visualise transitions the engine already validated.
 */

interface Batch {
  state: GameState;
  events: GameEvent[];
}

const queue: Batch[] = [];
let running = false;
let settleHandler: () => void = () => {};

export function onSettled(fn: () => void): void {
  settleHandler = fn;
}

export function setView(v: GameState): void {
  syncTargets(v);
  useGame.getState().set({ view: v });
}

function patchView(fn: (v: GameState) => void): GameState {
  const next = structuredClone(useGame.getState().view!);
  fn(next);
  setView(next);
  return next;
}

export function enqueue(state: GameState, events: GameEvent[]): void {
  queue.push({ state, events });
  if (!running) void run();
}

/** Abort all playback (leaving a match, new match). */
export function resetDirector(): void {
  matchId += 1;
  cancelAll();
  queue.length = 0;
  running = false;
  for (const rt of tokens.values()) {
    rt.scripted = false;
    rt.spin = 0;
  }
  cameraFocus.point = null;
  cameraFocus.celebrate = false;
  useGame.getState().set({ busy: false, rollPending: false });
}

async function run(): Promise<void> {
  running = true;
  const gen = currentGeneration();
  useGame.getState().set({ busy: true });
  while (queue.length) {
    const batch = queue.shift()!;
    // Fall behind (background tab, slow device)? Skip straight to the result.
    const fast = queue.length >= 2 || document.hidden;
    if (!fast && useGame.getState().view) {
      for (const e of batch.events) {
        await play(e);
        if (gen !== currentGeneration()) return;
      }
      if (useGame.getState().matchKind !== 'online') {
        const r = botReaction(batch.events, batch.state.players, Math.random);
        if (r) showEmote(r.id, batch.state.players[r.player].color);
      }
    }
    finalize(batch.state);
  }
  running = false;
  useGame.getState().set({ busy: false, rollPending: false });
  settleHandler();
}

let matchId = 0;
let recordedMatch = -1;

/** Mark the start of a new match without resetting playback (online rematch). */
export function beginMatch(): void {
  matchId += 1;
}

/** Save a finished match to the local history exactly once, even if playback was skipped. */
function recordIfOver(state: GameState): void {
  const s = useGame.getState();
  if (state.phase !== 'over' || recordedMatch === matchId || !s.matchKind) return;
  recordedMatch = matchId;
  useSettings.getState().addRecord({
    at: Date.now(),
    mode: s.matchKind,
    won: winnersOf(state).some((pi) => s.localColors.includes(state.players[pi].color)),
    players: state.players.map((p) => ({ name: p.name, color: p.color, rank: p.rank, you: s.localColors.includes(p.color) })),
  });
}

function finalize(state: GameState): void {
  recordIfOver(state);
  for (const rt of tokens.values()) {
    rt.scripted = false;
    rt.spin = 0;
  }
  cameraFocus.point = null;
  cameraFocus.celebrate = state.phase === 'over';
  setView(state);
  dice.api?.place(state.players[state.current].color, null);
}

const ordinal = (n: number) => `${n}${['th', 'st', 'nd', 'rd'][n % 100 > 10 && n % 100 < 14 ? 0 : n % 10 < 4 ? n % 10 : 0]}`;

async function play(e: GameEvent): Promise<void> {
  const g = useGame.getState();
  const view = g.view!;
  switch (e.type) {
    case 'rolled': {
      const color = view.players[e.player].color;
      await dice.api?.roll(e.value, color);
      g.set({ lastRoll: { player: e.player, value: e.value } });
      patchView((v) => {
        v.dice = e.value;
      });
      break;
    }
    case 'noMoves': {
      const p = view.players[e.player];
      g.toast(`${p.name} has no valid move`, p.color);
      await wait(0.6);
      break;
    }
    case 'forfeitSixes': {
      const p = view.players[e.player];
      g.toast('Three sixes in a row — turn forfeited', p.color);
      sfx('error');
      await wait(0.8);
      break;
    }
    case 'moved':
      await moveToken(e);
      break;
    case 'captured':
      await captureToken(e);
      break;
    case 'tokenHome': {
      const p = view.players[e.player];
      const [x, y, z] = pointFor(p.color, FINISH, e.token);
      effects.api?.burst(x, y + 0.4, z, '#ffd56b', 40, 1.2);
      effects.api?.burst(x, y + 0.4, z, PLAYER_HEX[p.color], 24, 0.9);
      sfx('home');
      await wait(0.35);
      break;
    }
    case 'playerFinished': {
      const p = view.players[e.player];
      g.toast(`${p.name} finished ${ordinal(e.rank)}!`, p.color);
      await wait(0.4);
      break;
    }
    case 'extraTurn': {
      const p = view.players[e.player];
      const why = e.reason === 'six' ? 'Rolled a six' : e.reason === 'capture' ? 'Capture bonus' : 'Home bonus';
      g.toast(`${why} — roll again!`, p.color);
      sfx('extra');
      patchView((v) => {
        v.phase = 'roll';
        v.dice = null;
      });
      await wait(0.25);
      break;
    }
    case 'turn': {
      const next = patchView((v) => {
        v.current = e.player;
        v.phase = 'roll';
        v.dice = null;
      });
      const color = next.players[e.player].color;
      const s = useGame.getState();
      if (s.matchKind === 'local' && useSettings.getState().followTurns && s.localColors.includes(color)) {
        s.set({ viewColor: color });
      }
      sfx('turn');
      await dice.api?.moveTo(color);
      break;
    }
    case 'gameOver': {
      patchView((v) => {
        v.phase = 'over';
      });
      cameraFocus.celebrate = true;
      const s = useGame.getState();
      const winner = view.players[e.ranking[0]];
      const won = e.ranking.slice(0, view.config.teams ? 2 : 1).some((pi) => s.localColors.includes(view.players[pi].color));
      sfx(won || s.matchKind === 'local' ? 'victory' : 'defeat');
      const [wx, , wz] = pointFor(winner.color, FINISH, 0);
      for (let i = 0; i < 4; i++) {
        setTimeout(() => effects.api?.burst(wx * 0.5, 1.2, wz * 0.5, i % 2 ? '#ffd56b' : PLAYER_HEX[winner.color], 60, 1.6), i * 260);
      }
      break;
    }
  }
}

async function moveToken(e: Extract<GameEvent, { type: 'moved' }>): Promise<void> {
  const view = useGame.getState().view!;
  const pl = view.players[e.player];
  const key = tokenKey(e.player, e.token);
  const after = structuredClone(view);
  after.players[e.player].tokens[e.token] = e.to;
  const final = computeTargets(after).get(key)!;
  const rt = tokenRuntime(key);
  const g = rt.group;
  if (!g) {
    setView(after);
    return;
  }

  const points: { x: number; y: number; z: number; s: number }[] = [];
  if (e.from >= 0) {
    for (let p = e.from + 1; p < e.to; p++) {
      const [x, y, z] = pointFor(pl.color, p, e.token);
      points.push({ x, y, z, s: 1 });
    }
  }
  points.push({ x: final.x, y: final.y, z: final.z, s: final.scale });

  rt.scripted = true;
  const leavingYard = e.from < 0;
  const stepDur = leavingYard ? 0.42 : points.length > 8 ? 0.17 : 0.2;
  const hop = leavingYard ? 1.0 : 0.42;
  for (const pt of points) {
    const sx = g.position.x;
    const sy = g.position.y;
    const sz = g.position.z;
    const ss = g.scale.x;
    cameraFocus.point = [pt.x, pt.y, pt.z];
    await tween(stepDur, (t) => {
      const k = ease.inOutSine(t);
      g.position.set(sx + (pt.x - sx) * k, sy + (pt.y - sy) * t + Math.sin(Math.PI * t) * hop, sz + (pt.z - sz) * k);
      g.scale.setScalar(ss + (pt.s - ss) * k);
    });
    rt.squash = 1;
    sfx('move');
  }
  setView(after);
  rt.scripted = false;
  cameraFocus.point = null;
}

async function captureToken(e: Extract<GameEvent, { type: 'captured' }>): Promise<void> {
  const view = useGame.getState().view!;
  const key = tokenKey(e.player, e.token);
  const rt = tokenRuntime(key);
  const g = rt.group;
  const after = structuredClone(view);
  after.players[e.player].tokens[e.token] = -1;
  const home = computeTargets(after).get(key)!;
  const byColor = PLAYER_HEX[view.players[e.by].color];
  sfx('capture');
  if (!g) {
    setView(after);
    return;
  }
  effects.api?.burst(g.position.x, g.position.y + 0.3, g.position.z, byColor, 36, 1.3);
  effects.api?.ring(g.position.x, g.position.y + 0.02, g.position.z, byColor);
  rt.scripted = true;
  const sx = g.position.x;
  const sy = g.position.y;
  const sz = g.position.z;
  const ss = g.scale.x;
  await tween(0.8, (t) => {
    const k = ease.inOutCubic(t);
    g.position.set(sx + (home.x - sx) * k, sy + (home.y - sy) * k + Math.sin(Math.PI * t) * 2.6, sz + (home.z - sz) * k);
    g.scale.setScalar(ss + (home.scale - ss) * k);
    rt.spin = k * Math.PI * 4;
  });
  rt.spin = 0;
  rt.squash = 1;
  sfx('move');
  setView(after);
  rt.scripted = false;
}
