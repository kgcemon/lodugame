import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { MatchSettled, RoomView } from '../shared/protocol';
import type { GameState } from '../shared/types';
import { MatchLedger } from '../server/ledger';
import { Presence } from '../server/presence';
import { api, bootServer, emit, next, nextUpdate, registerUser, socketFor, tick, type TestServer } from './helpers';

let t: TestServer;
const rolls: number[] = [];

beforeAll(async () => {
  t = await bootServer({ roll: () => rolls.shift() ?? 3 });
});
afterAll(() => t.stop());

type Me = { user: { id: string; username: string; role: string } | null; wallet: { balance: number; totalEarned: number; totalSpent: number } };
const balance = async (a: ReturnType<typeof api>) => (await a.call<Me>('GET', '/auth/me')).body.wallet.balance;
const ledgerRows = async (userId: string) =>
  (await t.server.db.query<{ type: string; amount: number; balance_before: number; balance_after: number; idempotency_key: string }>(
    'select type, amount, balance_before, balance_after, idempotency_key from wallet_transactions where user_id = $1 order by created_at',
    [userId],
  )).rows;

/** Put a running room's game one roll away from red winning (test-only shortcut into server state). */
function nearlyWon(code: string): void {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const room = (t.server.rooms as any).rooms.get(code) as { game: GameState };
  room.game.players[0].tokens = [56, 56, 56, 55];
  room.game.current = 0;
  room.game.phase = 'roll';
}

describe('accounts', () => {
  it('validates registration input', async () => {
    const a = api(t);
    const bad = async (body: object) => (await a.call('POST', '/auth/register', body)).body.error;
    const ok = { fullName: 'Valid Name', email: 'v@example.com', password: 'goodPass123', confirmPassword: 'goodPass123' };
    expect(await bad({ ...ok, fullName: 'A' })).toBe('Full name must be at least 2 characters');
    expect(await bad({ ...ok, email: 'not-an-email' })).toBe('Enter a valid email address');
    expect(await bad({ ...ok, password: 'short1', confirmPassword: 'short1' })).toBe('Password must be at least 8 characters');
    expect(await bad({ ...ok, password: 'onlyletters', confirmPassword: 'onlyletters' })).toBe('Password must contain letters and numbers');
    expect(await bad({ ...ok, password: 'password123', confirmPassword: 'password123' })).toBe('That password is too common');
    expect(await bad({ ...ok, confirmPassword: 'different123' })).toBe('Passwords do not match');
  });

  it('registers, grants the welcome bonus exactly once, and rejects duplicate emails', async () => {
    const u = await registerUser(t, 'Nadia Rahman', 'Nadia@Example.com');
    const me = await u.call<Me>('GET', '/auth/me');
    expect(me.body.user?.username).toMatch(/^NadiaRahman\d{4}$/);
    expect(me.body.wallet.balance).toBe(1000);
    expect(await ledgerRows(u.id)).toEqual([{ type: 'welcome_bonus', amount: 1000, balance_before: 0, balance_after: 1000, idempotency_key: 'welcome_bonus' }]);

    // Same email, different case: rejected, and no second bonus anywhere.
    const again = await api(t).call('POST', '/auth/register', { fullName: 'Copy', email: 'nadia@example.COM', password: 'goodPass123', confirmPassword: 'goodPass123' });
    expect(again.status).toBe(409);
    expect(again.body.error).toBe('An account with this email already exists');
    expect(await ledgerRows(u.id)).toHaveLength(1);
    // The password is stored only as a scrypt hash.
    const row = (await t.server.db.query<{ password_hash: string }>('select password_hash from users where id = $1', [u.id])).rows[0];
    expect(row.password_hash.startsWith('scrypt$')).toBe(true);
    expect(row.password_hash).not.toContain('goodPass123');
  });

  it('logs in and out; a logged-out cookie no longer works', async () => {
    const u = await registerUser(t, 'Login Test');
    const fresh = api(t);
    expect((await fresh.call('POST', '/auth/login', { email: u.email, password: 'wrongPass999' })).status).toBe(401);
    expect((await fresh.call('POST', '/auth/login', { email: u.email, password: 'goodPass123' })).status).toBe(200);
    expect((await fresh.call<Me>('GET', '/auth/me')).body.user?.username).toBe(u.username);
    const stolen = fresh.cookie;
    await fresh.call('POST', '/auth/logout');
    expect((await api(t, stolen).call<Me>('GET', '/auth/me')).body.user).toBeNull();
    // Balance persists across sessions (it lives in the database, not the browser).
    expect(await balance(u)).toBe(1000);
  });

  it('verifies email and resets passwords with single-use emailed tokens', async () => {
    const u = await registerUser(t, 'Mail Test');
    const verifyMail = t.server.mailer.outbox.find((m) => m.to === u.email && m.subject.includes('Verify'))!;
    const token = /token=([A-Za-z0-9_-]+)/.exec(verifyMail.text)![1];
    expect((await api(t).call('POST', '/auth/verify-email', { token })).status).toBe(200);
    expect((await api(t).call('POST', '/auth/verify-email', { token })).status).toBe(400); // single use

    const forgot = await api(t).call('POST', '/auth/forgot-password', { email: u.email });
    expect(forgot.body.message).toContain('If an account exists');
    const unknown = await api(t).call('POST', '/auth/forgot-password', { email: 'nobody@example.com' });
    expect(unknown.body.message).toBe(forgot.body.message); // no account enumeration
    const resetMail = t.server.mailer.outbox.filter((m) => m.to === u.email && m.subject.includes('Reset')).at(-1)!;
    const rt = /token=([A-Za-z0-9_-]+)/.exec(resetMail.text)![1];
    expect((await api(t).call('POST', '/auth/reset-password', { token: rt, password: 'newPass456', confirmPassword: 'newPass456' })).status).toBe(200);
    // Old sessions were revoked and the old password no longer works.
    expect((await u.call<Me>('GET', '/auth/me')).body.user).toBeNull();
    expect((await api(t).call('POST', '/auth/login', { email: u.email, password: 'goodPass123' })).status).toBe(401);
    expect((await api(t).call('POST', '/auth/login', { email: u.email, password: 'newPass456' })).status).toBe(200);
  });

  it('blocks cross-site requests and unauthenticated access', async () => {
    const u = await registerUser(t, 'Csrf Test');
    const noHeader = await fetch(`${t.url}/api/wallet/daily-bonus`, { method: 'POST', headers: { cookie: u.cookie, 'content-type': 'application/json' }, body: '{}' });
    expect(noHeader.status).toBe(403);
    const evil = await fetch(`${t.url}/api/wallet/daily-bonus`, {
      method: 'POST',
      headers: { cookie: u.cookie, 'content-type': 'application/json', 'x-requested-with': 'ludo3d', origin: 'https://evil.example' },
      body: '{}',
    });
    expect(evil.status).toBe(403);
    expect((await api(t).call('GET', '/wallet')).status).toBe(401);
    expect((await api(t).call('GET', '/friends')).status).toBe(401);
  });
});

describe('wallet', () => {
  it('cannot be changed by the player: no endpoint, no client fields, admin routes refuse players', async () => {
    const u = await registerUser(t, 'Tamper Test');
    await u.call('PATCH', '/profile', { avatarHue: 20, balance: 999999 });
    expect(await balance(u)).toBe(1000);
    expect((await u.call('POST', `/admin/users/${u.id}/adjust`, { amount: 5000, reason: 'free money' })).status).toBe(403);
    expect((await u.call('PUT', '/admin/settings', { welcomeBonus: 1 })).status).toBe(403);
    expect(await balance(u)).toBe(1000);
  });

  // PostgreSQL only: MySQL has no row level security (access there is limited by database user grants).
  it.skipIf(!!process.env.TEST_MYSQL_URL)('denies direct database access for non-owner roles (row level security)', async () => {
    const db = t.server.db;
    await db.exec(`do $$ begin create role rls_probe nologin; exception when duplicate_object then null; end $$;
                   grant select, update on wallets to rls_probe;`);
    const out = await db.tx(async (q) => {
      await q.exec('set local role rls_probe');
      const seen = (await q.query('select * from wallets')).rows.length;
      const changed = (await q.query('update wallets set balance = 999999')).rowCount;
      return { seen, changed };
    });
    expect(out).toEqual({ seen: 0, changed: 0 });
  });

  it('daily bonus can be claimed once per day', async () => {
    const u = await registerUser(t, 'Daily Test');
    expect((await u.call('POST', '/wallet/daily-bonus')).status).toBe(200);
    const second = await u.call('POST', '/wallet/daily-bonus');
    expect(second.status).toBe(409);
    expect(await balance(u)).toBe(1100);
  });
});

describe('paid matches', () => {
  it('refuses entry with insufficient coins (with the exact message)', async () => {
    const admin = await registerUser(t, 'Admin One', 'admin@example.com');
    const poor = await registerUser(t, 'Poor Player');
    const adj = await admin.call('POST', `/admin/users/${poor.id}/adjust`, { amount: -995, reason: 'test: drain wallet', requestId: 'drain-0001' });
    expect(adj.status).toBe(200);
    // Same request id again (double click): no second deduction.
    await admin.call('POST', `/admin/users/${poor.id}/adjust`, { amount: -995, reason: 'test: drain wallet', requestId: 'drain-0001' });
    expect(await balance(poor)).toBe(5);
    const s = await socketFor(t, poor.cookie);
    const r = await emit(s, 'room:quick', { modeId: 'quick' });
    expect(r).toEqual({ ok: false, error: 'Insufficient Coins! Play more matches or collect your free bonus.', code: 'INSUFFICIENT_COINS' });
    expect(await balance(poor)).toBe(5);
    const audit = (await t.server.db.query<{ action: string }>(`select action from audit_logs where target_user_id = $1`, [poor.id])).rows;
    expect(audit.map((a) => a.action)).toEqual(['wallet.adjust']);
  });

  it('charges the official fee once, pays the winner 2x, and settles exactly once', async () => {
    const ua = await registerUser(t, 'Winner Will');
    const ub = await registerUser(t, 'Loser Lou');
    const a = await socketFor(t, ua.cookie);
    const b = await socketFor(t, ub.cookie);
    // A client-supplied fee is ignored: only the mode id matters.
    const qa = await emit<{ room: RoomView }>(a, 'room:quick', { modeId: 'quick', entryFee: 0 });
    expect((await emit(a, 'room:quick', { modeId: 'quick' })).ok).toBe(true); // retry: still one seat, one charge
    const started = nextUpdate(a);
    await emit(b, 'room:quick', { modeId: 'quick' });
    await started;
    expect(await balance(ua)).toBe(990);
    expect(await balance(ub)).toBe(990);
    expect((await ledgerRows(ua.id)).filter((r) => r.type === 'match_entry')).toHaveLength(1);

    const code = qa.ok ? qa.room.code : '';
    nearlyWon(code);
    rolls.push(1);
    const settled = next<MatchSettled>(b, 'match:settled');
    await emit(a, 'game:roll');
    await emit(a, 'game:move', { token: 3 });
    const s = await settled;
    expect(s).toMatchObject({ payout: 20, entryFee: 10, winnerName: ua.username });
    await tick(100);
    expect(await balance(ua)).toBe(1010); // -10 entry +20 payout = +10 net
    expect(await balance(ub)).toBe(990);

    // Settling again (repeat completion / reconnection) changes nothing.
    const matchId = (await t.server.db.query<{ id: string }>('select id from matches where room_code = $1 order by created_at desc limit 1', [code])).rows[0].id;
    const ledger = new MatchLedger({ db: t.server.db, settings: t.server.settings, presence: new Presence(), requireEmailVerification: false });
    expect(await ledger.settle(matchId, [ua.id, ub.id], 'red')).toBeNull();
    const a2 = await socketFor(t, ua.cookie);
    await emit(a2, 'session:hello');
    await tick(100);
    expect(await balance(ua)).toBe(1010);
    expect((await ledgerRows(ua.id)).filter((r) => r.type === 'match_reward')).toEqual([
      { type: 'match_reward', amount: 20, balance_before: 990, balance_after: 1010, idempotency_key: `reward:${matchId}` },
    ]);
    const st = (await t.server.db.query<{ total_pot: number; payout: number; house_amount: number }>('select total_pot, payout, house_amount from match_settlements where match_id = $1', [matchId])).rows[0];
    expect(st).toEqual({ total_pot: 20, payout: 20, house_amount: 0 });

    // Match history and stats come from these records.
    const hist = await ua.call<{ items: { result: string; coinsPaid: number; coinsReceived: number; net: number; modeName: string }[] }>('GET', '/matches/history');
    expect(hist.body.items[0]).toMatchObject({ result: 'won', coinsPaid: 10, coinsReceived: 20, net: 10, modeName: 'Quick Match' });
    const lost = await ub.call<{ items: { result: string; net: number }[] }>('GET', '/matches/history');
    expect(lost.body.items[0]).toMatchObject({ result: 'lost', net: -10 });
    const stats = await ua.call<{ stats: { matches: number; wins: number; winRate: number } }>('GET', '/me/stats');
    expect(stats.body.stats).toMatchObject({ matches: 1, wins: 1, winRate: 100 });
  });

  it('refunds players who leave before the start and when a room is cancelled', async () => {
    const host = await registerUser(t, 'Host Hana');
    const guest = await registerUser(t, 'Guest Gil');
    const hs = await socketFor(t, host.cookie);
    const gs = await socketFor(t, guest.cookie);
    const c = await emit<{ room: RoomView }>(hs, 'room:create', { modeId: 'classic', maxPlayers: 4 });
    const code = c.ok ? c.room.code : '';
    expect(await balance(host)).toBe(950);

    // Preview shows the official fee before paying.
    const preview = await guest.call<{ room: { entryFee: number; players: number; joinable: boolean } }>('GET', `/rooms/${code}`);
    expect(preview.body.room).toMatchObject({ entryFee: 50, players: 1, joinable: true });
    await emit(gs, 'room:join', { code });
    expect(await balance(guest)).toBe(950);
    await emit(gs, 'room:leave');
    await tick(100);
    expect(await balance(guest)).toBe(1000);
    // Rejoining charges again (a new attempt), leaving refunds again.
    await emit(gs, 'room:join', { code });
    expect(await balance(guest)).toBe(950);

    // Host leaves; guest becomes host; guest leaves -> room cancelled, everyone refunded.
    await emit(hs, 'room:leave');
    await emit(gs, 'room:leave');
    await tick(150);
    expect(await balance(host)).toBe(1000);
    expect(await balance(guest)).toBe(1000);
    const types = (await ledgerRows(guest.id)).map((r) => `${r.type}:${r.amount}`);
    expect(types).toEqual(['welcome_bonus:1000', 'match_entry:-50', 'match_refund:50', 'match_entry:-50', 'match_refund:50']);
  });

  it('does not refund a player who leaves after the start; a server restart refunds those still playing', async () => {
    const ua = await registerUser(t, 'Quitter Quinn');
    const ub = await registerUser(t, 'Stayer Sam');
    const a = await socketFor(t, ua.cookie);
    const b = await socketFor(t, ub.cookie);
    const created = await emit<{ room: RoomView }>(a, 'room:create', { modeId: 'pro', maxPlayers: 2 });
    await emit(b, 'room:join', { code: created.ok ? created.room.code : '' });
    await emit(b, 'room:ready', { ready: true });
    const started = nextUpdate(a);
    await emit(a, 'room:start');
    await started;
    await emit(a, 'room:leave'); // forfeits mid-match: a bot takes the seat, no refund
    await tick(100);
    expect(await balance(ua)).toBe(900);

    // Simulate a crash: in-memory rooms vanish, then boot recovery runs.
    const ledger = new MatchLedger({ db: t.server.db, settings: t.server.settings, presence: new Presence(), requireEmailVerification: false });
    const rec = await ledger.recover();
    expect(rec.abandoned).toBeGreaterThanOrEqual(1);
    expect(await balance(ua)).toBe(900); // already forfeited: no refund
    expect(await balance(ub)).toBe(1000); // still playing: refunded
  });
});

describe('friends and invitations', () => {
  it('sends, rejects, accepts and removes friend requests without duplicates', async () => {
    const a = await registerUser(t, 'Friend Ana');
    const b = await registerUser(t, 'Friend Ben');
    expect((await a.call('POST', '/friends/requests', { player: a.username })).body.error).toBe('You cannot add yourself');
    expect((await a.call('POST', '/friends/requests', { player: b.username })).status).toBe(200);
    expect((await a.call('POST', '/friends/requests', { player: b.publicId })).status).toBe(409);

    const search = await b.call<{ players: { username: string; pending: string; publicId: string }[] }>('GET', `/players/search?q=${encodeURIComponent(a.username.slice(0, 6))}`);
    const found = search.body.players.find((p) => p.username === a.username)!;
    expect(found.pending).toBe('incoming');
    expect(JSON.stringify(search.body)).not.toContain('@'); // no emails in public search

    const reqs = await b.call<{ incoming: { id: string }[] }>('GET', '/friends/requests');
    expect(reqs.body.incoming).toHaveLength(1);
    const rej = await b.call('POST', `/friends/requests/${reqs.body.incoming[0].id}/reject`);
    expect(rej.status).toBe(200);
    // A can try again; this time B accepts.
    await a.call('POST', '/friends/requests', { player: b.username });
    const reqs2 = await b.call<{ incoming: { id: string }[] }>('GET', '/friends/requests');
    expect((await a.call('POST', `/friends/requests/${reqs2.body.incoming[0].id}/accept`)).status).toBe(404); // only the recipient may accept
    expect((await b.call('POST', `/friends/requests/${reqs2.body.incoming[0].id}/accept`)).status).toBe(200);
    const list = await a.call<{ friends: { username: string; online: boolean }[] }>('GET', '/friends');
    expect(list.body.friends.map((f) => f.username)).toEqual([b.username]);
    expect((await a.call('POST', '/friends/requests', { player: b.username })).status).toBe(409); // already friends

    const notes = await a.call<{ items: { type: string }[] }>('GET', '/notifications');
    expect(notes.body.items.map((n) => n.type)).toContain('friend_accepted');
    expect((await a.call('DELETE', `/friends/${b.publicId}`)).status).toBe(200);
    expect((await a.call<{ friends: unknown[] }>('GET', '/friends')).body.friends).toHaveLength(0);
  });

  it('invites a friend to a room and shows the friend online and in that room', async () => {
    const a = await registerUser(t, 'Inviter Ivy');
    const b = await registerUser(t, 'Invitee Ian');
    const c = await registerUser(t, 'Stranger Sid');
    await a.call('POST', '/friends/requests', { player: b.username });
    const r = await b.call<{ incoming: { id: string }[] }>('GET', '/friends/requests');
    await b.call('POST', `/friends/requests/${r.body.incoming[0].id}/accept`);

    const as = await socketFor(t, a.cookie);
    const bs = await socketFor(t, b.cookie);
    const created = await emit<{ room: RoomView }>(as, 'room:create', { modeId: 'friendly', maxPlayers: 4 });
    const code = created.ok ? created.room.code : '';
    const live = next<{ type: string; payload: { code: string } }>(bs, 'notify');
    expect((await a.call('POST', '/friends/invite', { publicId: b.publicId })).status).toBe(200);
    expect((await live).payload.code).toBe(code);
    expect((await a.call('POST', '/friends/invite', { publicId: c.publicId })).status).toBe(403); // not a friend

    const view = await b.call<{ friends: { username: string; online: boolean; room: { code: string; joinable: boolean } | null }[] }>('GET', '/friends');
    expect(view.body.friends[0]).toMatchObject({ username: a.username, online: true, room: { code, joinable: true } });
  });
});

describe('administration and leaderboard', () => {
  it('lets admins change entry fees, suspend accounts, and see audit logs', async () => {
    const admin = api(t);
    await admin.call('POST', '/auth/login', { email: 'admin@example.com', password: 'goodPass123' });
    const s = await admin.call<{ settings: { modes: { id: string; entryFee: number }[] } }>('GET', '/admin/settings');
    const modes = s.body.settings.modes.map((m) => (m.id === 'classic' ? { ...m, entryFee: 75 } : m));
    expect((await admin.call('PUT', '/admin/settings', { modes, payoutMultiplier: 9 })).status).toBe(400);
    expect((await admin.call('PUT', '/admin/settings', { modes })).status).toBe(200);
    const cfg = await api(t).call<{ modes: { id: string; entryFee: number }[] }>('GET', '/config');
    expect(cfg.body.modes.find((m) => m.id === 'classic')?.entryFee).toBe(75);

    const bad = await registerUser(t, 'Bad Actor');
    const bs = await socketFor(t, bad.cookie);
    const kicked = new Promise((r) => bs.on('disconnect', r));
    expect((await admin.call('POST', `/admin/users/${bad.id}/suspend`, { reason: 'abuse in chat' })).status).toBe(200);
    await kicked;
    expect((await bad.call<Me>('GET', '/auth/me')).body.user).toBeNull();
    const login = await api(t).call('POST', '/auth/login', { email: bad.email, password: 'goodPass123' });
    expect(login.status).toBe(403);
    const log = await admin.call<{ items: { action: string }[] }>('GET', '/admin/audit');
    expect(log.body.items.map((x) => x.action)).toEqual(expect.arrayContaining(['settings.update', 'user.suspend', 'wallet.adjust']));
  });

  it('ranks players by wins without exposing emails', async () => {
    const u = await registerUser(t, 'Board Viewer');
    const lb = await u.call<{ items: { username: string; wins: number; position: number }[] }>('GET', '/leaderboard?sort=wins');
    expect(lb.body.items[0].wins).toBeGreaterThanOrEqual(1);
    expect(lb.body.items[0].position).toBe(1);
    expect(JSON.stringify(lb.body)).not.toContain('@example.com');
    expect((await u.call('GET', '/leaderboard?sort=winrate')).status).toBe(200);
  });
});
