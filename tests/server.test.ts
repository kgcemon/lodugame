import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { io as connect } from 'socket.io-client';
import type { RoomView } from '../shared/protocol';
import { bootServer, emit, nextUpdate, registerUser, socketFor, tick, type TestServer } from './helpers';

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

  it('auto-plays for a disconnected player and lets them reconnect', async () => {
    const { ua, a, b } = await pair();
    const created = await emit<{ room: RoomView }>(a, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    const code = created.ok ? created.room.code : '';
    await emit(b, 'room:join', { code });
    await emit(b, 'room:ready', { ready: true });
    const started = nextUpdate(a);
    await emit(a, 'room:start');
    await started;

    rolls.push(4);
    const auto = nextUpdate(b, (u) => u.events.some((e) => e.type === 'rolled' && e.player === 0));
    a.close();
    expect((await auto).events.map((e) => e.type)).toContain('noMoves');

    const a2 = await socketFor(t, ua.cookie);
    const snapshot = nextUpdate(a2);
    const hello = await emit<{ room: RoomView | null }>(a2, 'session:hello');
    expect(hello.ok && hello.room?.code).toBe(code);
    expect(hello.ok && hello.room?.you).toBe(0);
    expect((await snapshot).state.current).toBe(1);
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

  it('rejects unknown modes and private-only modes in matchmaking', async () => {
    const u = await registerUser(t, 'Picky');
    const s = await socketFor(t, u.cookie);
    expect(await emit(s, 'room:quick', { modeId: 'mega' })).toEqual({ ok: false, error: 'Unknown match mode' });
    expect((await emit(s, 'room:quick', { modeId: 'friendly' })).ok).toBe(false);
  });
});
