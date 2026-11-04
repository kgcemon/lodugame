import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { ChatMessage, GameUpdate, RoomView } from '../shared/protocol';
import { bootServer, emit, next, registerUser, socketFor, tick, type C, type TestServer } from './helpers';

/** Blocking and reporting players, against the real server over real sockets. */

type User = Awaited<ReturnType<typeof registerUser>>;

let t: TestServer;
let admin: User;

beforeAll(async () => {
  t = await bootServer();
  admin = await registerUser(t, 'Moderator', 'admin@example.com');
});
afterAll(() => t.stop());

const block = (me: User, other: User) => me.call('POST', '/blocks', { player: other.publicId });
const unblock = (me: User, other: User) => me.call('DELETE', `/blocks/${other.publicId}`);
const befriend = async (a: User, b: User) => {
  const r = await a.call<{ requestId: string }>('POST', '/friends/requests', { player: b.publicId });
  expect((await b.call('POST', `/friends/requests/${r.body.requestId}/accept`)).status).toBe(200);
};

/** Everything of one kind a socket receives from now on. */
function collect<T>(c: C, ev: 'chat:message' | 'emote' | 'chat:history'): T[] {
  const got: T[] = [];
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (c as any).on(ev, (x: T) => got.push(x));
  return got;
}

/** A started free match of `n` players. */
async function match(n = 2, opts: { teams?: boolean } = {}) {
  const users: User[] = [];
  const sockets: C[] = [];
  for (let i = 0; i < n; i++) {
    users.push(await registerUser(t, `Player ${i}`));
    sockets.push(await socketFor(t, users[i].cookie));
  }
  const made = await emit<{ room: RoomView }>(sockets[0], 'room:create', { modeId: 'friendly', maxPlayers: n, teams: opts.teams });
  if (!made.ok) throw new Error(made.error);
  for (let i = 1; i < n; i++) {
    await emit(sockets[i], 'room:join', { code: made.room.code });
    await emit(sockets[i], 'room:ready', { ready: true });
  }
  const live = next<RoomView>(sockets[0], 'room:state', (r) => r.status === 'playing');
  await emit(sockets[0], 'room:start');
  const room = await live;
  return { users, sockets, room, matchId: room.matchId };
}

const say = (c: C, text: string, team = false) => emit(c, 'chat:send', { text, team });

describe('chat and emotes', () => {
  it("a blocked player's chat reaches everyone but the person who blocked them, and they aren't told", async () => {
    const { users, sockets } = await match(3);
    const [blocker, blocked] = users;
    expect((await block(blocker, blocked)).status).toBe(200);
    const heard = sockets.map((c) => collect<ChatMessage>(c, 'chat:message'));
    expect((await say(sockets[1], 'hello everyone')).ok).toBe(true);
    await tick();
    expect(heard[0]).toEqual([]);
    expect(heard[1].map((m) => m.text)).toEqual(['hello everyone']);
    expect(heard[2].map((m) => m.text)).toEqual(['hello everyone']);
  });

  it('is one way: the person who blocked is still heard by the blocked player', async () => {
    const { users, sockets } = await match(2);
    await block(users[0], users[1]);
    const heard = collect<ChatMessage>(sockets[1], 'chat:message');
    await say(sockets[0], 'still here');
    await tick();
    expect(heard.map((m) => m.text)).toEqual(['still here']);
  });

  it('no user id goes out with a message', async () => {
    const { users, sockets } = await match(2);
    const heard = next<ChatMessage>(sockets[0], 'chat:message');
    await say(sockets[1], 'private id?');
    expect(JSON.stringify(await heard)).not.toContain(users[1].id);
  });

  it('team chat is filtered too', async () => {
    const { users, sockets, room } = await match(4, { teams: true });
    const team = (i: number) => room.seats[i].team;
    const mate = [1, 2, 3].find((i) => team(i) === team(0))!;
    const foe = [1, 2, 3].find((i) => team(i) !== team(0))!;
    await block(users[0], users[mate]);
    const mine = collect<ChatMessage>(sockets[0], 'chat:message');
    const theirs = collect<ChatMessage>(sockets[mate], 'chat:message');
    const enemy = collect<ChatMessage>(sockets[foe], 'chat:message');
    await say(sockets[mate], 'team secret', true);
    await tick();
    expect(mine).toEqual([]);
    expect(theirs.map((m) => m.text)).toEqual(['team secret']);
    expect(enemy).toEqual([]);
  });

  it('emotes are filtered per recipient', async () => {
    const { users, sockets } = await match(3);
    await block(users[0], users[1]);
    const heard = sockets.map((c) => collect<{ id: string }>(c, 'emote'));
    expect((await emit(sockets[1], 'emote:send', { id: 'fire' })).ok).toBe(true);
    await tick();
    expect(heard[0]).toEqual([]);
    expect(heard[1]).toHaveLength(1);
    expect(heard[2]).toHaveLength(1);
  });

  it('chat history leaves out blocked players, also after blocking mid-match and on coming back', async () => {
    const { users, sockets } = await match(2);
    await say(sockets[1], 'said before the block');
    await say(sockets[0], 'my own line');
    await tick();

    const refreshed = next<ChatMessage[]>(sockets[0], 'chat:history');
    await block(users[0], users[1]);
    expect((await refreshed).map((m) => m.text)).toEqual(['my own line']);

    sockets[0].close();
    await tick(100);
    const back = await socketFor(t, users[0].cookie);
    const history = next<ChatMessage[]>(back, 'chat:history');
    await emit(back, 'session:hello', {});
    expect((await history).map((m) => m.text)).toEqual(['my own line']);

    const restored = next<ChatMessage[]>(back, 'chat:history');
    await unblock(users[0], users[1]);
    expect((await restored).map((m) => m.text)).toEqual(['said before the block', 'my own line']);
  });

  it('after unblocking, their chat reaches me again', async () => {
    const { users, sockets } = await match(2);
    await block(users[0], users[1]);
    await unblock(users[0], users[1]);
    const heard = next<ChatMessage>(sockets[0], 'chat:message');
    await say(sockets[1], 'back again');
    expect((await heard).text).toBe('back again');
  });

  it('blocks survive a server restart', async () => {
    const own = await bootServer();
    const a = await registerUser(own, 'Player A');
    const b = await registerUser(own, 'Player B');
    await block(a, b);
    const { BlockList } = await import('../server/blocks');
    const fresh = new BlockList();
    await fresh.load(own.server.db);
    expect(fresh.has(a.id, b.id)).toBe(true);
    expect(fresh.has(b.id, a.id)).toBe(false);
    await own.stop();
  });
});

describe('the game does not change', () => {
  it('blocked players still share a room and take turns', async () => {
    const { users, sockets, room } = await match(2);
    await block(users[0], users[1]);
    await block(users[1], users[0]);
    expect(room.status).toBe('playing');
    const updates = sockets.map((c) => next<GameUpdate>(c, 'game:update', (u) => u.events.some((e) => e.type === 'rolled')));
    await Promise.all(sockets.map((c) => emit(c, 'game:roll', {})));
    const [mine, theirs] = await Promise.all(updates);
    expect(mine.state).toEqual(theirs.state);
  });

  it('a blocked player can still join by code', async () => {
    const host = await registerUser(t, 'Host');
    const guest = await registerUser(t, 'Guest');
    await block(host, guest);
    const hs = await socketFor(t, host.cookie);
    const gs = await socketFor(t, guest.cookie);
    const made = await emit<{ room: RoomView }>(hs, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    if (!made.ok) throw new Error(made.error);
    expect((await emit(gs, 'room:join', { code: made.room.code })).ok).toBe(true);
  });
});

describe('friend requests and invites', () => {
  it('a blocked player cannot send the blocker a friend request, nor the other way round', async () => {
    const a = await registerUser(t, 'Blocks');
    const b = await registerUser(t, 'Blocked');
    await block(a, b);
    const theirs = await b.call('POST', '/friends/requests', { player: a.publicId });
    expect(theirs.status).toBe(403);
    expect(theirs.body.code).toBe('BLOCKED');
    expect(theirs.body.error).not.toContain('block');
    expect((await a.call('POST', '/friends/requests', { player: b.publicId })).status).toBe(409);
    const count = await t.server.db.query<{ n: number }>('select count(*)::int as n from friend_requests where from_user = $1 or from_user = $2', [a.id, b.id]);
    expect(Number(count.rows[0].n)).toBe(0);
  });

  it('blocking ends a friendship and cancels a pending request, which still counts towards the limit', async () => {
    const a = await registerUser(t, 'Player A');
    const b = await registerUser(t, 'Player B');
    const c = await registerUser(t, 'Player C');
    await befriend(a, b);
    await block(a, b);
    expect((await a.call<{ friends: unknown[] }>('GET', '/friends')).body.friends).toEqual([]);
    expect((await b.call<{ friends: unknown[] }>('GET', '/friends')).body.friends).toEqual([]);

    const sent = await c.call<{ requestId: string; sendsLeft: number }>('POST', '/friends/requests', { player: a.publicId });
    expect(sent.body.sendsLeft).toBe(1);
    await block(a, c);
    const accept = await a.call('POST', `/friends/requests/${sent.body.requestId}/accept`);
    expect(accept.status).toBe(404);
    await unblock(a, c);
    const after = await c.call<{ player: { friendship: { sendsLeft: number; pending: string | null } } }>('GET', `/players/${a.publicId}`);
    expect(after.body.player.friendship).toMatchObject({ sendsLeft: 1, pending: null });
    // Unblocking doesn't restore a friendship.
    expect((await a.call<{ friends: unknown[] }>('GET', '/friends')).body.friends).toEqual([]);
  });

  it('a request accepted after a block is refused even if it somehow stayed pending', async () => {
    const a = await registerUser(t, 'Player A');
    const b = await registerUser(t, 'Player B');
    const sent = await b.call<{ requestId: string }>('POST', '/friends/requests', { player: a.publicId });
    await block(a, b);
    await t.server.db.query(`update friend_requests set status = 'pending' where id = $1`, [sent.body.requestId]);
    expect((await a.call('POST', `/friends/requests/${sent.body.requestId}/accept`)).status).toBe(404);
  });

  it('invites are refused in both directions, even if a friendship row survived', async () => {
    const a = await registerUser(t, 'Player A');
    const b = await registerUser(t, 'Player B');
    const as = await socketFor(t, a.cookie);
    const bs = await socketFor(t, b.cookie);
    await befriend(a, b);
    await block(a, b);
    const [x, y] = a.id < b.id ? [a.id, b.id] : [b.id, a.id];
    await t.server.db.query('insert into friendships (user_a, user_b) values ($1, $2)', [x, y]);
    await emit(as, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    await emit(bs, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    expect((await b.call('POST', '/friends/invite', { publicId: a.publicId })).status).toBe(403);
    expect((await a.call('POST', '/friends/invite', { publicId: b.publicId })).status).toBe(403);
    const notes = await a.call<{ items: { type: string }[] }>('GET', '/notifications');
    expect(notes.body.items.filter((n) => n.type === 'room_invite')).toEqual([]);
  });
});

describe('gifts', () => {
  const coins = async (u: User) => (await u.call<{ wallet: { balance: number } }>('GET', '/wallet')).body.wallet.balance;

  it('neither can send the other a gift, and nothing is charged', async () => {
    const { users, sockets, room } = await match(2);
    await t.server.db.query('update wallets set balance = balance + 5000 where user_id = $1 or user_id = $2', [users[0].id, users[1].id]);
    await block(users[0], users[1]);
    const before = await coins(users[1]);
    const theirs = await emit(sockets[1], 'playerGift:send', { productId: 'golden_rose', toSeatId: room.seats[0].id, key: randomUUID() });
    expect(theirs.ok).toBe(false);
    expect(await coins(users[1])).toBe(before);
    const mine = await emit(sockets[0], 'playerGift:send', { productId: 'golden_rose', toSeatId: room.seats[1].id, key: randomUUID() });
    expect(mine.ok).toBe(false);
    const rows = await t.server.db.query('select id from player_gifts where match_id = $1', [room.matchId]);
    expect(rows.rows).toEqual([]);
  });

  it('a gift paid for before the block is answered as sent on a retry, without a second charge', async () => {
    const { users, sockets, room } = await match(2);
    await t.server.db.query('update wallets set balance = balance + 5000 where user_id = $1', [users[1].id]);
    const key = randomUUID();
    const first = await emit<{ duplicate: boolean }>(sockets[1], 'playerGift:send', { productId: 'golden_rose', toSeatId: room.seats[0].id, key });
    expect(first.ok).toBe(true);
    const paid = await coins(users[1]);
    await block(users[0], users[1]);
    const retry = await emit<{ duplicate: boolean }>(sockets[1], 'playerGift:send', { productId: 'golden_rose', toSeatId: room.seats[0].id, key });
    expect(retry.ok && retry.duplicate).toBe(true);
    expect(await coins(users[1])).toBe(paid);
  });

  it("a blocked player's reaction to an earlier gift doesn't reach the person who blocked them", async () => {
    const { users, sockets, room } = await match(2);
    await t.server.db.query('update wallets set balance = balance + 5000 where user_id = $1', [users[0].id]);
    const sent = await emit<{ giftId: string }>(sockets[0], 'playerGift:send', { productId: 'golden_rose', toSeatId: room.seats[1].id, key: randomUUID() });
    if (!sent.ok) throw new Error(sent.error);
    await block(users[0], users[1]);
    let reacted = false;
    sockets[0].on('playerGift:reaction', () => (reacted = true));
    expect((await emit(sockets[1], 'playerGift:react', { giftId: sent.giftId, reaction: 'fire' })).ok).toBe(true);
    await tick();
    expect(reacted).toBe(false);
  });
});

describe('the block list', () => {
  it('lists who I blocked, and only for me', async () => {
    const a = await registerUser(t, 'Player A');
    const b = await registerUser(t, 'Player B');
    await block(a, b);
    await block(a, b);
    const mine = await a.call<{ items: { publicId: string; username: string }[] }>('GET', '/blocks');
    expect(mine.body.items.map((x) => x.publicId)).toEqual([b.publicId]);
    expect((await b.call<{ items: unknown[] }>('GET', '/blocks')).body.items).toEqual([]);
    expect((await a.call<{ player: { blocked: boolean } }>('GET', `/players/${b.publicId}`)).body.player.blocked).toBe(true);
    // Whether someone blocked me isn't shown.
    expect((await b.call<{ player: { blocked: boolean } }>('GET', `/players/${a.publicId}`)).body.player.blocked).toBe(false);
    expect((await unblock(a, b)).status).toBe(200);
    expect((await unblock(a, b)).status).toBe(200);
    expect((await a.call<{ items: unknown[] }>('GET', '/blocks')).body.items).toEqual([]);
  });

  it('refuses nonsense', async () => {
    const a = await registerUser(t, 'Player A');
    expect((await block(a, a)).status).toBe(400);
    expect((await a.call('POST', '/blocks', { player: 'NOPE0000' })).status).toBe(404);
    expect((await a.call('POST', '/blocks', {})).status).toBe(400);
    expect((await a.call('DELETE', '/blocks/NOPE0000')).status).toBe(404);
  });

  it('needs a signed-in player', async () => {
    const a = await registerUser(t, 'Player A');
    const { api } = await import('./helpers');
    const anon = api(t);
    expect((await anon.call('GET', '/blocks')).status).toBe(401);
    expect((await anon.call('POST', '/blocks', { player: a.publicId })).status).toBe(401);
    expect((await anon.call('POST', '/reports', { player: a.publicId, reason: 'spam' })).status).toBe(401);
  });

  it('deleting an account removes its blocks, both ways, and they stop applying', async () => {
    const a = await registerUser(t, 'Player A');
    const b = await registerUser(t, 'Player B');
    const c = await registerUser(t, 'Player C');
    await block(a, b);
    await block(c, a);
    expect((await a.call('POST', '/account/delete', { confirm: 'DELETE' })).status).toBe(200);
    const rows = await t.server.db.query('select 1 from player_blocks where blocker_id = $1 or blocked_id = $1', [a.id]);
    expect(rows.rows).toEqual([]);
    expect((await c.call<{ items: unknown[] }>('GET', '/blocks')).body.items).toEqual([]);
  });
});

describe('reports', () => {
  const report = (me: User, other: User, extra: object = {}) => me.call<{ id: string }>('POST', '/reports', { player: other.publicId, reason: 'harassment', ...extra });
  type Item = { id: string; status: string; reason: string; note: string | null; target: string; reporter: string | null; chat_excerpt: { text: string }[] | null; match_id: string | null; admin_note: string | null };
  const list = async (status: string) => (await admin.call<{ items: Item[] }>('GET', `/admin/reports?status=${status}`)).body.items;

  it('checks what it is given', async () => {
    const a = await registerUser(t, 'Player A');
    const b = await registerUser(t, 'Player B');
    expect((await a.call('POST', '/reports', { player: b.publicId })).status).toBe(400);
    expect((await a.call('POST', '/reports', { player: b.publicId, reason: 'nonsense' })).status).toBe(400);
    expect((await a.call('POST', '/reports', { player: b.publicId, reason: 'constructor' })).status).toBe(400);
    expect((await a.call('POST', '/reports', { player: 'NOPE0000', reason: 'spam' })).status).toBe(404);
    expect((await report(a, a)).status).toBe(400);
    expect((await report(a, b, { note: 'x'.repeat(501) })).status).toBe(400);
    expect((await report(a, b, { note: 42 })).status).toBe(400);
  });

  it('files one open report per player, with a note or without, and again once it is decided', async () => {
    const a = await registerUser(t, 'Player A');
    const b = await registerUser(t, 'Player B');
    const first = await report(a, b, { note: 'He keeps insulting me 😠' });
    expect(first.status).toBe(200);
    const twice = await report(a, b);
    expect(twice.status).toBe(409);
    expect(twice.body.code).toBe('ALREADY_REPORTED');
    const row = (await list('open')).find((x) => x.id === first.body.id)!;
    expect(row).toMatchObject({ reason: 'harassment', note: 'He keeps insulting me 😠', reporter: a.username, target: b.username, chat_excerpt: null, match_id: null });
    await admin.call('POST', `/admin/reports/${first.body.id}`, { status: 'dismissed', note: 'Nothing found' });
    expect((await report(a, b)).status).toBe(200);
  });

  it("keeps the server's copy of what the reported player said to the reporter, not team chat of the other side", async () => {
    const { users, sockets, room } = await match(4, { teams: true });
    const team = (i: number) => room.seats[i].team;
    const foe = [1, 2, 3].find((i) => team(i) !== team(0))!;
    await say(sockets[foe], 'you are terrible');
    await tick(1100);
    await say(sockets[foe], 'side plan', true);
    // Evidence survives blocking first.
    await block(users[0], users[foe]);
    const r = await report(users[0], users[foe], { note: 'Fake quote: they said something else' });
    const row = (await list('open')).find((x) => x.id === r.body.id)!;
    expect(row.chat_excerpt!.map((m) => m.text)).toEqual(['you are terrible']);
    expect(row.match_id).toBe(room.matchId);
  });

  it('the reported player is not told, and cannot read reports', async () => {
    const a = await registerUser(t, 'Player A');
    const b = await registerUser(t, 'Player B');
    await report(a, b);
    const notes = await b.call<{ items: unknown[]; unread: number }>('GET', '/notifications');
    expect(notes.body.items).toEqual([]);
  });

  it('only administrators can list and decide', async () => {
    const a = await registerUser(t, 'Player A');
    const b = await registerUser(t, 'Player B');
    const r = await report(a, b);
    expect((await a.call('GET', '/admin/reports')).status).toBe(403);
    expect((await b.call('POST', `/admin/reports/${r.body.id}`, { status: 'dismissed', note: 'No thanks' })).status).toBe(403);
    expect((await list('open')).find((x) => x.id === r.body.id)?.status).toBe('open');
  });

  it('each decision is recorded once, with a note, in the audit log', async () => {
    const a = await registerUser(t, 'Player A');
    const b = await registerUser(t, 'Player B');
    const r = await report(a, b);
    const id = r.body.id;
    expect((await admin.call('POST', `/admin/reports/${id}`, { status: 'resolved' })).status).toBe(400);
    expect((await admin.call('POST', `/admin/reports/${id}`, { status: 'open', note: 'Back to open' })).status).toBe(400);
    expect((await admin.call('POST', `/admin/reports/${id}`, { status: 'resolved', note: 'Warned the player' })).status).toBe(200);
    expect((await admin.call('POST', `/admin/reports/${id}`, { status: 'dismissed', note: 'Changed my mind' })).status).toBe(404);
    expect((await list('open')).some((x) => x.id === id)).toBe(false);
    expect((await list('resolved')).find((x) => x.id === id)).toMatchObject({ status: 'resolved', admin_note: 'Warned the player' });

    const audit = await admin.call<{ items: { action: string; target: string; actor: string; details: { reportId?: string; note?: string } }[] }>('GET', '/admin/audit');
    const entries = audit.body.items.filter((l) => l.details.reportId === id);
    expect(entries).toHaveLength(1);
    expect(entries[0]).toMatchObject({ action: 'report.resolved', target: b.username, actor: admin.username, details: { note: 'Warned the player' } });
    expect((await admin.call('POST', `/admin/reports/${randomUUID()}`, { status: 'resolved', note: 'Who is this' })).status).toBe(404);
  });

  it('the overview counts open reports', async () => {
    const a = await registerUser(t, 'Player A');
    const b = await registerUser(t, 'Player B');
    const before = (await admin.call<{ openReports: number }>('GET', '/admin/overview')).body.openReports;
    await report(a, b);
    expect((await admin.call<{ openReports: number }>('GET', '/admin/overview')).body.openReports).toBe(before + 1);
  });

  it('deleting an account keeps the report but removes what that person wrote or said', async () => {
    const { users, sockets } = await match(2);
    await say(sockets[1], 'rude words');
    await tick();
    const r = await report(users[0], users[1], { note: 'Reporter note' });
    await sockets[1].emitWithAck('room:leave', {});
    await users[1].call('POST', '/account/delete', { confirm: 'DELETE' });
    let row = (await list('open')).find((x) => x.id === r.body.id)!;
    expect(row.chat_excerpt).toBeNull();
    expect(row.note).toBe('Reporter note');
    await sockets[0].emitWithAck('room:leave', {});
    await users[0].call('POST', '/account/delete', { confirm: 'DELETE' });
    row = (await list('open')).find((x) => x.id === r.body.id)!;
    expect(row.note).toBeNull();
    expect(row.reporter).toMatch(/^deleted_/);
  });
});
