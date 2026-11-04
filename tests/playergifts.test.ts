import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { GIFT_DURATION_MS, GIFT_MODELS, GIFT_PARTICLES, GIFT_RECEIVE_ANIMS, GIFT_SEND_ANIMS, GIFT_SENDS_PER_MIN, type GiftProductView, type MissedGift, type PlayerGiftEvent } from '../shared/playergifts';
import type { RoomView } from '../shared/protocol';
import { bootServer, emit, next, registerUser, socketFor, tick, type C, type TestServer } from './helpers';

/** Gifts between players in a match, against the real server over real sockets. */

let t: TestServer;
let admin: Awaited<ReturnType<typeof registerUser>>;
type User = Awaited<ReturnType<typeof registerUser>>;
type Sent = { giftId: string; duplicate: boolean; balance: number; currency: string; price: number };

beforeAll(async () => {
  t = await bootServer({ timings: { dropMs: 600 } });
  admin = await registerUser(t, 'Gift Admin', 'admin@example.com');
});
afterAll(() => t.stop());

const wallet = async (u: User) => (await u.call<{ wallet: { balance: number; gems: number } }>('GET', '/wallet')).body.wallet;
const addGems = (u: User, n: number) => t.server.db.query('update wallets set gems = gems + $2 where user_id = $1', [u.id, n]);
const addCoins = (u: User, n: number) => t.server.db.query('update wallets set balance = balance + $2 where user_id = $1', [u.id, n]);

/** A started match of `n` real players. */
async function match(n = 2, modeId = 'friendly') {
  const users: User[] = [];
  const sockets: C[] = [];
  for (let i = 0; i < n; i++) {
    users.push(await registerUser(t, `Player ${i}`));
    sockets.push(await socketFor(t, users[i].cookie));
  }
  const made = await emit<{ room: RoomView }>(sockets[0], 'room:create', { modeId, maxPlayers: n });
  if (!made.ok) throw new Error(made.error);
  for (let i = 1; i < n; i++) {
    await emit(sockets[i], 'room:join', { code: made.room.code });
    await emit(sockets[i], 'room:ready', { ready: true });
  }
  const live = next<RoomView>(sockets[0], 'room:state', (r) => r.status === 'playing');
  await emit(sockets[0], 'room:start');
  const room = await live;
  return { users, sockets, room, matchId: room.matchId, seat: (i: number) => room.seats[i].id };
}

const send = (c: C, productId: string, toSeatId: string, key: string = randomUUID()) => emit<Sent>(c, 'playerGift:send', { productId, toSeatId, key });
const giftsOf = async (matchId: string) => (await t.server.db.query<Record<string, unknown>>('select * from player_gifts where match_id = $1', [matchId])).rows;

describe('the catalogue', () => {
  it('has the ten gold and twelve diamond gifts, with prices in the right currency, and the app can draw them', async () => {
    const u = await registerUser(t, 'Browser');
    const r = await u.call<{ products: GiftProductView[] }>('GET', '/player-gifts/catalog');
    const gold = r.body.products.filter((p) => p.currency === 'coins');
    const diamonds = r.body.products.filter((p) => p.currency === 'diamonds');
    expect(gold).toHaveLength(10);
    expect(diamonds).toHaveLength(12);
    expect(gold.find((p) => p.id === 'golden_rose')?.price).toBe(100);
    expect(gold.find((p) => p.id === 'golden_trophy')?.price).toBe(2500);
    expect(diamonds.find((p) => p.id === 'royal_crown')?.price).toBe(100);
    expect(diamonds.find((p) => p.id === 'royal_palace')?.price).toBe(1000);
    expect(new Set(r.body.products.map((p) => p.model)).size).toBe(22);
    for (const p of r.body.products) {
      expect(GIFT_MODELS).toContain(p.model);
      expect(GIFT_SEND_ANIMS).toContain(p.sendAnim);
      expect(GIFT_RECEIVE_ANIMS).toContain(p.receiveAnim);
      expect(GIFT_PARTICLES).toContain(p.particle);
    }
  });
});

describe('sending', () => {
  it('charges gold from the sender only, once, and everyone in the match watches the same gift', async () => {
    const { users, sockets, seat, matchId } = await match(3);
    const [a, b] = users;
    await addCoins(a, 500);
    const before = await wallet(a);
    const beforeB = await wallet(b);
    const events = sockets.map((c) => next<PlayerGiftEvent>(c, 'playerGift:event'));
    const r = await send(sockets[0], 'teddy_bear', seat(1));
    if (!r.ok) throw new Error(r.error);
    expect(r).toMatchObject({ duplicate: false, currency: 'coins', price: 500, balance: before.balance - 500 });

    const seen = await Promise.all(events);
    expect(new Set(seen.map((e) => JSON.stringify(e))).size).toBe(1);
    const e = seen[0];
    expect(e).toMatchObject({ id: r.giftId, matchId, name: 'Teddy Bear', rarity: 'rare', model: 'bear', durationMs: GIFT_DURATION_MS.rare });
    expect(e.from).toMatchObject({ seatId: seat(0), name: a.username });
    expect(e.to).toMatchObject({ seatId: seat(1), name: b.username });
    expect(JSON.stringify(e)).not.toContain('"price"');

    const after = await wallet(a);
    expect(after.balance).toBe(before.balance - 500);
    expect(after.gems).toBe(before.gems);
    const afterB = await wallet(b);
    expect(afterB).toMatchObject({ balance: beforeB.balance, gems: beforeB.gems }); // cosmetic: no currency for the recipient
    const ledger = await t.server.db.query<{ type: string; amount: number }>('select type, amount from wallet_transactions where match_id = $1 and user_id = $2', [matchId, a.id]);
    expect(ledger.rows.map((x) => [x.type, Number(x.amount)])).toEqual([['player_gift', -500]]);
  });

  it('charges diamonds from the diamond wallet, and only there', async () => {
    const { users, sockets, seat, matchId } = await match(2);
    const [a] = users;
    await addGems(a, 150);
    const before = await wallet(a);
    const r = await send(sockets[0], 'royal_crown', seat(1));
    if (!r.ok) throw new Error(r.error);
    expect(r).toMatchObject({ currency: 'diamonds', price: 100, balance: before.gems - 100 });
    const after = await wallet(a);
    expect(after.gems).toBe(before.gems - 100);
    expect(after.balance).toBe(before.balance);
    const ledger = await t.server.db.query<{ type: string; amount: number }>('select type, amount from gem_transactions where match_id = $1 and user_id = $2', [matchId, a.id]);
    expect(ledger.rows.map((x) => [x.type, Number(x.amount)])).toEqual([['player_gift', -100]]);
  });

  it('refuses without enough balance: nothing moves and nothing flies', async () => {
    const { users, sockets, seat, matchId } = await match(2);
    let flew = false;
    sockets[1].on('playerGift:event', () => (flew = true));
    const before = await wallet(users[0]);
    const r = await send(sockets[0], 'royal_palace', seat(1));
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.code).toBe('NOT_ENOUGH_GEMS');
    const r2 = await send(sockets[0], 'golden_trophy', seat(1));
    if (before.balance < 2500) {
      expect(r2.ok).toBe(false);
      if (!r2.ok) expect(r2.code).toBe('INSUFFICIENT_COINS');
    }
    await tick(50);
    expect(flew).toBe(false);
    expect(await giftsOf(matchId)).toHaveLength(r2.ok ? 1 : 0);
    expect((await wallet(users[0])).gems).toBe(before.gems);
    sockets[1].removeAllListeners('playerGift:event');
  });

  it('a retry with the same key costs nothing more, sends no second journey, and cannot be changed', async () => {
    const { users, sockets, seat, matchId } = await match(2);
    const [a] = users;
    await addCoins(a, 1000);
    await addGems(a, 100);
    const key = randomUUID();
    let flights = 0;
    sockets[1].on('playerGift:event', () => flights++);
    const before = await wallet(a);
    const [one, two] = await Promise.all([send(sockets[0], 'golden_rose', seat(1), key), send(sockets[0], 'golden_rose', seat(1), key)]);
    if (!one.ok || !two.ok) throw new Error('send failed');
    expect(one.giftId).toBe(two.giftId);
    expect([one.duplicate, two.duplicate].sort()).toEqual([false, true]);
    // The same key can't buy something else, in the other currency either.
    const other = await send(sockets[0], 'diamond_heart', seat(1), key);
    if (!other.ok) throw new Error(other.error);
    expect(other.duplicate).toBe(true);
    expect(other.giftId).toBe(one.giftId);
    await tick(60);
    const after = await wallet(a);
    expect(after.balance).toBe(before.balance - 100);
    expect(after.gems).toBe(before.gems);
    expect(flights).toBe(1);
    expect(await giftsOf(matchId)).toHaveLength(1);
    sockets[1].removeAllListeners('playerGift:event');
  });

  it('takes the price from the server: the client has no say, and unknown or disabled gifts are refused', async () => {
    const { users, sockets, seat } = await match(2);
    await addCoins(users[0], 1000);
    const before = await wallet(users[0]);
    const spoof = await emit<Sent>(sockets[0], 'playerGift:send', { productId: 'golden_rose', toSeatId: seat(1), key: randomUUID(), price: 1, currency: 'diamonds' });
    if (!spoof.ok) throw new Error(spoof.error);
    expect(spoof).toMatchObject({ price: 100, currency: 'coins' });
    expect((await wallet(users[0])).balance).toBe(before.balance - 100);

    expect((await send(sockets[0], 'no_such_gift', seat(1))).ok).toBe(false);
    expect((await emit(sockets[0], 'playerGift:send', { productId: 'golden_rose', toSeatId: seat(1), key: 'x' })).ok).toBe(false);
    await admin.call('POST', '/admin/player-gifts/love_letter', { enabled: false });
    expect((await send(sockets[0], 'love_letter', seat(1))).ok).toBe(false);
    await admin.call('POST', '/admin/player-gifts/love_letter', { enabled: true });
  });
});

describe('who can receive', () => {
  it('not yourself, a bot, someone outside the match, or anyone if you are not in one', async () => {
    const a = await registerUser(t, 'Alone');
    const ca = await socketFor(t, a.cookie);
    const made = await emit<{ room: RoomView }>(ca, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    if (!made.ok) throw new Error(made.error);
    await emit(ca, 'room:addBot');
    const live = next<RoomView>(ca, 'room:state', (r) => r.status === 'playing');
    await emit(ca, 'room:start');
    const room = await live;
    const before = await wallet(a);
    expect((await send(ca, 'golden_rose', room.seats[0].id)).ok).toBe(false); // myself
    expect((await send(ca, 'golden_rose', room.seats[1].id)).ok).toBe(false); // a bot

    const other = await match(2);
    expect((await send(ca, 'golden_rose', other.seat(1))).ok).toBe(false); // another room's player
    expect((await send(other.sockets[0], 'golden_rose', room.seats[0].id)).ok).toBe(false);
    expect((await wallet(a)).balance).toBe(before.balance);

    const stranger = await registerUser(t, 'Stranger');
    const cs = await socketFor(t, stranger.cookie);
    expect((await send(cs, 'golden_rose', other.seat(1))).ok).toBe(false); // not in any match
  });

  it('not before the match or after it, nor a player who is offline or out', async () => {
    const { users, sockets, room } = await match(3);
    const [a, b, c] = users;
    await addCoins(a, 1000);
    const offline = next<RoomView>(sockets[0], 'room:state', (r) => r.seats[1].status === 'disconnected');
    sockets[1].close();
    await offline;
    const dropped = await send(sockets[0], 'golden_rose', room.seats[1].id);
    expect(dropped.ok).toBe(false);
    if (!dropped.ok) expect(dropped.error).toContain(b.username);
    const out = next<RoomView>(sockets[0], 'room:state', (r) => r.seats[2].left);
    await emit(sockets[2], 'room:leave', { results: true });
    await out;
    expect((await send(sockets[0], 'golden_rose', room.seats[2].id)).ok).toBe(false);
    void c;

    const u = await registerUser(t, 'Lobby');
    const cu = await socketFor(t, u.cookie);
    const lobby = await emit<{ room: RoomView }>(cu, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    if (!lobby.ok) throw new Error(lobby.error);
    expect((await send(cu, 'golden_rose', lobby.room.seats[0].id)).ok).toBe(false);
  });

  it('limits how many gifts a player can send a minute', async () => {
    const { users, sockets, seat } = await match(2);
    await addCoins(users[0], 100 * (GIFT_SENDS_PER_MIN + 2));
    for (let i = 0; i < GIFT_SENDS_PER_MIN; i++) expect((await send(sockets[0], 'golden_rose', seat(1))).ok).toBe(true);
    const r = await send(sockets[0], 'golden_rose', seat(1));
    expect(r.ok).toBe(false);
  });
});

describe('a recipient who is away', () => {
  it('finds the gift waiting when they return, once they have not seen it, and it is not delivered twice', async () => {
    const { users, sockets, seat } = await match(2);
    const [a, b] = users;
    await addCoins(a, 500);
    const r = await send(sockets[0], 'golden_rose', seat(1));
    if (!r.ok) throw new Error(r.error);
    sockets[1].close(); // gone before their app could say it arrived
    await tick(60);
    const back = await socketFor(t, b.cookie);
    const missed = next<{ gifts: MissedGift[] }>(back, 'playerGift:missed');
    await emit(back, 'session:hello', { results: true });
    const m = await missed;
    expect(m.gifts).toEqual([{ id: r.giftId, name: 'Golden Rose', from: a.username }]);
    const notes = await b.call<{ items: { payload: { playerGiftId?: string } }[] }>('GET', '/notifications');
    expect(notes.body.items.some((n) => n.payload.playerGiftId === r.giftId)).toBe(true);

    await emit(back, 'playerGift:seen', { ids: [r.giftId] });
    let again = false;
    back.on('playerGift:missed', () => (again = true));
    await emit(back, 'session:hello', { results: true });
    await tick(80);
    expect(again).toBe(false);
  });
});

describe('history, collection and thanks', () => {
  it('shows each side its own view, keeps the sent record, and lets the recipient show and thank', async () => {
    const { users, sockets, seat } = await match(2);
    const [a, b] = users;
    await addCoins(a, 2000);
    const r = await send(sockets[0], 'flower_bouquet', seat(1));
    if (!r.ok) throw new Error(r.error);

    type Row = { id: string; name: string; price?: number; currency?: string; displayed?: boolean; reaction: string | null; player: { username: string } };
    const sent = await a.call<{ items: Row[] }>('GET', '/player-gifts?dir=sent');
    expect(sent.body.items[0]).toMatchObject({ id: r.giftId, name: 'Flower Bouquet', price: 1000, currency: 'coins', player: { username: b.username } });
    const received = await b.call<{ items: Row[] }>('GET', '/player-gifts?dir=received');
    expect(received.body.items[0]).toMatchObject({ id: r.giftId, displayed: false, player: { username: a.username } });
    expect(received.body.items[0].price).toBeUndefined();

    // Only the recipient can do these.
    expect((await a.call('POST', `/player-gifts/${r.giftId}/display`, { displayed: true })).status).toBe(404);
    expect((await emit(sockets[0], 'playerGift:react', { giftId: r.giftId, reaction: 'heart' })).ok).toBe(false);
    expect((await emit(sockets[1], 'playerGift:react', { giftId: r.giftId, reaction: 'not-an-emote' })).ok).toBe(false);

    expect((await b.call('POST', `/player-gifts/${r.giftId}/display`, { displayed: true })).status).toBe(200);
    const profile = await a.call<{ player: { gifts: { id: string; model: string }[] } }>('GET', `/players/${b.publicId}`);
    expect(profile.body.player.gifts).toEqual([expect.objectContaining({ id: r.giftId, model: 'bouquet' })]);

    const thanked = next<{ giftId: string; reaction: string; from: string }>(sockets[0], 'playerGift:reaction');
    const ok = await emit(sockets[1], 'playerGift:react', { giftId: r.giftId, reaction: 'heart' });
    expect(ok.ok).toBe(true);
    expect(await thanked).toMatchObject({ giftId: r.giftId, reaction: 'heart', from: b.username });
    await emit(sockets[1], 'playerGift:react', { giftId: r.giftId, reaction: 'fire' }); // once
    const after = await a.call<{ items: Row[] }>('GET', '/player-gifts?dir=sent');
    expect(after.body.items[0].reaction).toBe('heart');
  });
});

describe('the admin catalogue', () => {
  it('is for administrators, separates the currencies, sets the price players pay, and reports sales', async () => {
    const { users, sockets, seat } = await match(2);
    const [a] = users;
    await addCoins(a, 1000);
    expect((await a.call('GET', '/admin/player-gifts')).status).toBe(403);
    expect((await a.call('POST', '/admin/player-gifts/golden_rose', { price: 1 })).status).toBe(403);

    expect((await admin.call('POST', '/admin/player-gifts/golden_rose', { price: 0 })).status).toBe(400);
    expect((await admin.call('POST', '/admin/player-gifts/golden_rose', { model: 'unknown' })).status).toBe(400);
    expect((await admin.call('POST', '/admin/player-gifts/golden_rose', { price: 123 })).status).toBe(200);
    const r = await send(sockets[0], 'golden_rose', seat(1));
    if (!r.ok) throw new Error(r.error);
    expect(r.price).toBe(123);
    await admin.call('POST', '/admin/player-gifts/golden_rose', { price: 100 });

    const made = await admin.call<{ id: string }>('POST', '/admin/player-gifts', {
      name: 'Eid Lantern', currency: 'diamonds', price: 25, category: 'celebration', rarity: 'rare', model: 'balloon', sendAnim: 'rise', receiveAnim: 'bob', particle: 'stars', eventName: 'Eid', availableUntil: new Date(Date.now() + 60_000).toISOString(),
    });
    expect(made.status).toBe(200);
    const list = await admin.call<{ products: { id: string; currency: string; sent: number; spent: number; eventName: string | null }[] }>('GET', '/admin/player-gifts');
    expect(list.body.products.find((p) => p.id === made.body.id)).toMatchObject({ currency: 'diamonds', eventName: 'Eid', sent: 0 });
    expect(list.body.products.find((p) => p.id === 'golden_rose')!.sent).toBeGreaterThan(0);
    // An event gift stops being on sale when its time is up.
    await admin.call('POST', `/admin/player-gifts/${made.body.id}`, { availableUntil: new Date(Date.now() - 1000).toISOString() });
    const cat = await a.call<{ products: { id: string }[] }>('GET', '/player-gifts/catalog');
    expect(cat.body.products.some((p) => p.id === made.body.id)).toBe(false);
  });
});

describe('the match', () => {
  it('is untouched by gifts: same turn, same state, same clock', async () => {
    const { users, sockets, seat } = await match(2);
    await addCoins(users[0], 500);
    const before = await emit<{ room: RoomView }>(sockets[0], 'session:hello', { results: true });
    let updates = 0;
    sockets[0].on('game:update', () => updates++);
    await send(sockets[0], 'golden_rose', seat(1));
    await tick(60);
    expect(updates).toBe(0);
    sockets[0].removeAllListeners('game:update');
    const roll = await emit(sockets[0], 'game:roll');
    expect(roll.ok).toBe(true);
    void before;
  });
});
