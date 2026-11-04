import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { RoomView } from '../shared/protocol';
import { mayRefresh } from '../server/stats';
import { bootServer, emit, next, registerUser, socketFor, type TestServer } from './helpers';

/** Monitoring, stats and account deletion, against the real server. */

let t: TestServer;
type User = Awaited<ReturnType<typeof registerUser>>;

beforeAll(async () => {
  t = await bootServer();
});
afterAll(() => t.stop());

describe('health', () => {
  it('reports the process and the database to administrators only', async () => {
    const admin = await registerUser(t, 'Ops', 'admin@example.com');
    const u = await registerUser(t, 'Nosy');
    expect((await u.call('GET', '/admin/health')).status).toBe(403);
    const h = await admin.call<{ process: { rssMb: number; lagMs: { p99: number } }; sockets: number; rooms: number; dbPingMs: number }>('GET', '/admin/health');
    expect(h.body.process.rssMb).toBeGreaterThan(0);
    expect(h.body.dbPingMs).toBeGreaterThanOrEqual(0);
    const pub = await fetch(`${t.url}/healthz/db`);
    expect(pub.status).toBe(200);
    expect(await pub.json()).toMatchObject({ ok: true });
  });

  it('answers a connection probe', async () => {
    const u = await registerUser(t, 'Probe');
    const c = await socketFor(t, u.cookie);
    expect((await emit(c, 'net:ping')).ok).toBe(true);
  });
});

describe('rankings', () => {
  it('a slow read waits before it is repeated, a quick one never does', () => {
    expect(mayRefresh(5, 0)).toBe(true);
    expect(mayRefresh(3000, 60_000)).toBe(false);
    expect(mayRefresh(3000, 91_000)).toBe(true);
  });

  it('a win shows on the profile and the leaderboard straight away', async () => {
    const [a, b] = [await registerUser(t, 'Winner'), await registerUser(t, 'Loser')];
    const [ca, cb] = [await socketFor(t, a.cookie), await socketFor(t, b.cookie)];
    const made = await emit<{ room: RoomView }>(ca, 'room:create', { modeId: 'quick', maxPlayers: 2 });
    if (!made.ok) throw new Error(made.error);
    await emit(cb, 'room:join', { code: made.room.code });
    await emit(cb, 'room:ready', { ready: true });
    const started = next<RoomView>(ca, 'room:state', (r) => r.status === 'playing');
    await emit(ca, 'room:start');
    await started;
    const settled = next(ca, 'match:settled');
    await emit(cb, 'room:leave', { results: true });
    await settled;
    type P = { player: { stats: { wins: number; rank: number; matches: number } } };
    const p = await a.call<P>('GET', `/players/${a.publicId}`);
    expect(p.body.player.stats).toMatchObject({ wins: 1, matches: 1, rank: 1 });
    const board = await a.call<{ items?: { username: string; wins: number }[]; players?: { username: string; wins: number }[] }>('GET', '/leaderboard');
    const rows = board.body.items ?? board.body.players ?? [];
    expect(rows[0]).toMatchObject({ username: a.username, wins: 1 });
  });
});

describe('deleting an account', () => {
  const del = (u: User) => u.call('POST', '/account/delete', { confirm: 'DELETE' });

  it('asks for confirmation and waits until a running match is over', async () => {
    const a = await registerUser(t, 'Busy');
    const b = await registerUser(t, 'Partner');
    expect((await a.call('POST', '/account/delete', {})).status).toBe(400);
    const [ca, cb] = [await socketFor(t, a.cookie), await socketFor(t, b.cookie)];
    const made = await emit<{ room: RoomView }>(ca, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    if (!made.ok) throw new Error(made.error);
    await emit(cb, 'room:join', { code: made.room.code });
    await emit(cb, 'room:ready', { ready: true });
    const started = next<RoomView>(ca, 'room:state', (r) => r.status === 'playing');
    await emit(ca, 'room:start');
    await started;
    expect((await del(a)).status).toBe(409);
  });

  it('removes the person, ends every session, frees the email, and keeps the ledger', async () => {
    const a = await registerUser(t, 'Leaving');
    const friend = await registerUser(t, 'Friend');
    const [x, y] = a.id < friend.id ? [a.id, friend.id] : [friend.id, a.id];
    await t.server.db.query('insert into friendships (user_a, user_b) values ($1, $2)', [x, y]);
    const other = await a.call('POST', '/auth/login', { email: a.email, password: 'goodPass123' });
    expect(other.status).toBe(200);
    const ledgerBefore = (await t.server.db.query('select id from wallet_transactions where user_id = $1', [a.id])).rows.length;

    const old = a.cookie;
    expect((await del(a)).status).toBe(200);

    expect((await friend.call('GET', `/players/${a.publicId}`)).status).toBe(404);
    const row = (await t.server.db.query<{ email: string; full_name: string; username: string }>(
      'select u.email, p.full_name, p.username from users u join profiles p on p.user_id = u.id where u.id = $1', [a.id],
    )).rows[0];
    expect(row.email).not.toContain(a.email);
    expect(row.full_name).toBe('Deleted player');
    expect(row.username).not.toContain('Leaving');
    expect((await t.server.db.query('select 1 from friendships where user_a = $1 or user_b = $1', [a.id])).rows).toHaveLength(0);
    expect((await t.server.db.query('select id from wallet_transactions where user_id = $1', [a.id])).rows.length).toBe(ledgerBefore);

    // Nothing signs in any more: not the old session, not the password.
    a.cookie = old;
    const me = await a.call<{ user: unknown }>('GET', '/auth/me');
    expect(me.body.user ?? null).toBeNull();
    const again = await a.call('POST', '/auth/login', { email: a.email, password: 'goodPass123' });
    expect(again.status).toBe(401);
    // The same email can start over.
    expect((await registerUser(t, 'Back again', a.email)).id).not.toBe(a.id);
  });

  it('has a public page explaining how', async () => {
    const r = await fetch(`${t.url}/delete-account`);
    expect(r.status).toBe(200);
    expect(await r.text()).toContain('Delete my account');
  });
});
