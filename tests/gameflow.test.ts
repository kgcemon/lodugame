import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { RoomView } from '../shared/protocol';
import type { GameUpdate } from '../shared/protocol';
import { bootServer, emit, nextUpdate, registerUser, socketFor, tick, type C, type TestServer } from './helpers';

/**
 * Turn flow over real sockets: the server plays the only possible move by itself, never a
 * move with real choices, refuses stale or duplicate actions, and every player sees the
 * same authoritative state.
 */

let t: TestServer;
const rolls: number[] = [];

beforeAll(async () => {
  t = await bootServer({ roll: () => rolls.shift() ?? 3, timings: { autoMoveMs: 120, autoMoveRerollMs: 120 } });
});
afterAll(() => t.stop());

/** A started two player friendly match: a is red (first to act), b is yellow. */
async function match(): Promise<{ a: C; b: C; first: GameUpdate }> {
  const ua = await registerUser(t, 'Ann');
  const ub = await registerUser(t, 'Ben');
  const a = await socketFor(t, ua.cookie);
  const b = await socketFor(t, ub.cookie);
  const created = await emit<{ room: RoomView }>(a, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
  const code = created.ok ? created.room.code : '';
  await emit(b, 'room:join', { code });
  await emit(b, 'room:ready', { ready: true });
  const started = nextUpdate(b);
  await emit(a, 'room:start');
  return { a, b, first: await started };
}

const has = (u: GameUpdate, type: string) => u.events.some((e) => e.type === type);

describe('turn flow', () => {
  it('plays the only possible move by itself, exactly once, for everyone', async () => {
    const { a, b } = await match();
    // A six with every token in the yard: leaving the yard is the only real choice.
    rolls.push(6);
    const movedA = nextUpdate(a, (u) => has(u, 'moved'));
    const movedB = nextUpdate(b, (u) => has(u, 'moved'));
    expect((await emit(a, 'game:roll')).ok).toBe(true);
    const [ua, ub] = await Promise.all([movedA, movedB]);
    expect(ua.state.players[0].tokens.filter((x) => x === 0)).toHaveLength(1);
    // Both players see the same state version.
    expect(ub.state).toEqual(ua.state);
    // Rolled a six: still red's turn, back to rolling, and no second move happened.
    expect(ua.state.phase).toBe('roll');
    expect(ua.state.current).toBe(0);
    await tick(300);
    expect(ua.state.players[0].tokens.filter((x) => x >= 0)).toHaveLength(1);
  });

  it('waits for the player when there is a real choice', async () => {
    const { a } = await match();
    rolls.push(6);
    const out = nextUpdate(a, (u) => has(u, 'moved'));
    await emit(a, 'game:roll');
    await out;
    // Another six: enter a new token or move the one outside — two choices.
    rolls.push(6);
    const rolled = nextUpdate(a, (u) => has(u, 'rolled'));
    await emit(a, 'game:roll');
    const r = await rolled;
    expect(r.state.phase).toBe('move');
    let movedAgain = false;
    a.on('game:update', (u) => {
      if (has(u, 'moved')) movedAgain = true;
    });
    await tick(400);
    expect(movedAgain).toBe(false);
    expect((await emit(a, 'game:move', { token: 1, seq: r.state.seq })).ok).toBe(true);
  });

  it('skips the turn when the roll cannot move anything', async () => {
    const { a, b } = await match();
    rolls.push(4);
    const passed = nextUpdate(b, (u) => has(u, 'rolled'));
    expect((await emit(a, 'game:roll')).ok).toBe(true);
    const u = await passed;
    expect(u.events.map((e) => e.type)).toEqual(['rolled', 'noMoves', 'turn']);
    expect(u.state.current).toBe(1);
    expect(u.state.phase).toBe('roll');
  });

  it('refuses an action made on an old state version', async () => {
    const { a, first } = await match();
    rolls.push(4);
    const passed = nextUpdate(a, (u) => has(u, 'rolled'));
    await emit(a, 'game:roll', { seq: first.state.seq });
    await passed;
    rolls.push(6, 6);
    // Yellow's turn now; an old red tap is rejected before anything else is checked.
    expect((await emit(a, 'game:roll', { seq: first.state.seq })).ok).toBe(false);
  });

  it('accepts exactly one of several rapid roll taps', async () => {
    const { a } = await match();
    rolls.push(5);
    const results = await Promise.all([emit(a, 'game:roll'), emit(a, 'game:roll'), emit(a, 'game:roll')]);
    expect(results.filter((r) => r.ok)).toHaveLength(1);
  });

  it('a stale move after the server already auto-moved is refused, not applied twice', async () => {
    const { a } = await match();
    rolls.push(6);
    const rolled = nextUpdate(a, (u) => has(u, 'rolled'));
    const moved = nextUpdate(a, (u) => has(u, 'moved'));
    await emit(a, 'game:roll');
    const r = await rolled;
    const m = await moved;
    const late = await emit(a, 'game:move', { token: 2, seq: r.state.seq });
    expect(late).toEqual({ ok: false, error: 'That turn has already moved on' });
    expect(m.state.players[0].tokens.filter((x) => x >= 0)).toHaveLength(1);
  });
});
