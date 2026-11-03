import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { io as connect } from 'socket.io-client';
import type { RoomView } from '../shared/protocol';
import { bootServer, emit, next, nextUpdate, registerUser, socketFor, tick, type TestServer } from './helpers';

let t: TestServer;
const rolls: number[] = [];

beforeAll(async () => {
  t = await bootServer({ roll: () => rolls.shift() ?? 3 });
});
afterAll(() => t.stop());

async function pair() {
  const ua = await registerUser(t, 'Alice');
  const ub = await registerUser(t, 'Bob');
  return { ua, ub, a: await socketFor(t, ua.cookie), b: await socketFor(t, ub.cookie) };
}

describe('online game server (authenticated)', () => {
  it('refuses sockets without a valid session', async () => {
    const c = connect(t.url, { transports: ['websocket'], forceNew: true, reconnection: false });
    const err = await new Promise<Error>((r) => c.on('connect_error', r));
    expect(err.message).toBe('unauthorized');
    c.close();
  });

  it('runs a server-authoritative two player match', async () => {
    const { ua, ub, a, b } = await pair();
    const created = await emit<{ room: RoomView }>(a, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    expect(created.ok).toBe(true);
    const code = created.ok ? created.room.code : '';
    expect(code).toMatch(/^[A-Z2-9]{5}$/);
    expect((await emit(b, 'room:join', { code: 'ZZZZZ' })).ok).toBe(false);
    const joined = await emit<{ room: RoomView }>(b, 'room:join', { code: code.toLowerCase() });
    expect(joined.ok && joined.room.seats.map((s) => s.name)).toEqual([ua.username, ub.username]);

    expect((await emit(a, 'room:start')).ok).toBe(false); // guest not ready
    expect((await emit(b, 'room:start')).ok).toBe(false); // not host
    await emit(b, 'room:ready', { ready: true });
    const started = nextUpdate(b);
    expect((await emit(a, 'room:start')).ok).toBe(true);
    const first = await started;
    expect(first.state.players.map((p) => p.color)).toEqual(['red', 'yellow']);

    expect(await emit(b, 'game:roll')).toEqual({ ok: false, error: 'It is not your turn' });
    rolls.push(6);
    const rolled = nextUpdate(b, (u) => u.events.some((e) => e.type === 'rolled'));
    expect((await emit(a, 'game:roll')).ok).toBe(true);
    expect((await rolled).state.phase).toBe('move');
    expect((await emit(a, 'game:roll')).ok).toBe(false);
    expect((await emit(a, 'game:move', { token: 'x' })).ok).toBe(false);
    const moved = nextUpdate(b, (u) => u.events.some((e) => e.type === 'moved'));
    expect((await emit(a, 'game:move', { token: 1 })).ok).toBe(true);
    expect((await moved).state.players[0].tokens).toEqual([-1, 0, -1, -1]);
  });

  /** A started 2-player friendly match (a is red and moves first). */
  async function started() {
    const p = await pair();
    const created = await emit<{ room: RoomView }>(p.a, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    const code = created.ok ? created.room.code : '';
    await emit(p.b, 'room:join', { code });
    await emit(p.b, 'room:ready', { ready: true });
    const go = nextUpdate(p.a);
    await emit(p.a, 'room:start');
    await go;
    return { ...p, code };
  }

  it('waits for a player whose connection dropped, with a countdown, and lets them back in', async () => {
    const { ua, a, b, code } = await started();
    const waiting = next<RoomView>(b, 'room:state', (r) => r.seats[0].reconnectMsLeft !== null);
    a.close();
    const view = await waiting;
    expect(view.dropAfterMs).toBe(400);
    expect(view.seats[0]).toMatchObject({ connected: false, left: false });
    expect(view.seats[0].reconnectMsLeft).toBeGreaterThan(0);
    // Nobody plays for them meanwhile: it's still their turn to roll.
    await tick(150);
    const a2 = await socketFor(t, ua.cookie);
    const snapshot = nextUpdate(a2);
    const hello = await emit<{ room: RoomView | null }>(a2, 'session:hello');
    expect(hello.ok && hello.room?.code).toBe(code);
    expect(hello.ok && hello.room?.seats[0].reconnectMsLeft).toBeNull();
    const s = await snapshot;
    expect(s.state.current).toBe(0);
    expect(s.state.phase).toBe('roll');
    expect(s.state.seq).toBe(0);
  });

  it('puts a player out who stays offline too long: the opponent wins', async () => {
    const { ua, a, b } = await started();
    const over = nextUpdate(b, (u) => u.state.phase === 'over');
    const settled = next<{ winnerColor: string }>(b, 'match:settled');
    a.close();
    const u = await over;
    expect(u.events.map((e) => e.type)).toEqual(['playerLeft', 'gameOver']);
    expect(u.state.players[0].left).toBe(true);
    expect(u.state.ranking).toEqual([1, 0]);
    expect((await settled).winnerColor).toBe(u.state.players[1].color);
    // Back online, they're told what happened and are no longer in the room.
    const a2 = await socketFor(t, ua.cookie);
    const told = next<{ message: string }>(a2, 'match:dropped');
    const hello = await emit<{ room: RoomView | null }>(a2, 'session:hello');
    expect(hello.ok && hello.room).toBeNull();
    expect((await told).message).toMatch(/opponent won/);
  });

  it('allows bots only in free rooms', async () => {
    const u = await registerUser(t, 'Solo');
    const s = await socketFor(t, u.cookie);
    await emit(s, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    expect((await emit(s, 'room:addBot')).ok).toBe(true);
    expect((await emit(s, 'room:addBot')).ok).toBe(false); // full
    const started = nextUpdate(s);
    await emit(s, 'room:start');
    await started;
    rolls.push(1, 6, 5);
    const botRolled = nextUpdate(s, (x) => x.events.some((e) => e.type === 'rolled' && e.player === 1));
    await emit(s, 'game:roll');
    expect((await botRolled).events[0]).toMatchObject({ type: 'rolled', player: 1 });

    const p = await registerUser(t, 'Payer');
    const ps = await socketFor(t, p.cookie);
    await emit(ps, 'room:create', { modeId: 'classic', maxPlayers: 4 });
    expect(await emit(ps, 'room:addBot')).toEqual({ ok: false, error: 'Bots can only join free Friendly rooms' });
  });

  it('matches two quick-play players of the same mode and starts automatically', async () => {
    const { a, b } = await pair();
    const qa = await emit<{ room: RoomView }>(a, 'room:quick', { modeId: 'quick' });
    const started = nextUpdate(a);
    const qb = await emit<{ room: RoomView }>(b, 'room:quick', { modeId: 'quick' });
    expect(qa.ok && qb.ok && qa.room.code === qb.room.code).toBe(true);
    expect(qa.ok && qa.room.entryFee).toBe(10);
    expect((await started).state.players).toHaveLength(2);
    await tick();
  });

  it('fills quick play with bots when nobody else joins before the wait runs out', async () => {
    const st = await bootServer({ timings: { matchWaitMs: 300 } });
    try {
      const u = await registerUser(st, 'Lonely');
      const s = await socketFor(st, u.cookie);
      const started = nextUpdate(s);
      const q = await emit<{ room: RoomView }>(s, 'room:quick', { modeId: 'classic' });
      expect(q.ok).toBe(true);
      if (!q.ok) return;
      // The client is told how long the search lasts.
      expect(q.room.waitMsLeft).toBeGreaterThan(0);
      expect(q.room.waitMsLeft).toBeLessThanOrEqual(300);
      const u1 = await started;
      // Classic is a 4-player mode: one human plus three bots.
      expect(u1.state.players).toHaveLength(4);
      expect(u1.state.players.filter((p) => p.kind === 'ai')).toHaveLength(3);
      // The entry fee was charged once, for the human only.
      const me = await u.call<{ wallet: { balance: number } }>('GET', '/wallet');
      expect(me.body.wallet.balance).toBe(1000 - 50);
    } finally {
      await st.stop();
    }
  });

  it('rejects unknown modes and private-only modes in matchmaking', async () => {
    const u = await registerUser(t, 'Picky');
    const s = await socketFor(t, u.cookie);
    expect(await emit(s, 'room:quick', { modeId: 'mega' })).toEqual({ ok: false, error: 'Unknown match mode' });
    expect((await emit(s, 'room:quick', { modeId: 'friendly' })).ok).toBe(false);
  });
});
