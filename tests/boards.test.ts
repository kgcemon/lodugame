import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { RoomView } from '../shared/protocol';
import type { PlayPurchase, PlayVerifier } from '../server/googleplay';
import { BOARDS } from '../shared/boards';
import { bootServer, emit, nextUpdate, registerUser, socketFor, type TestServer } from './helpers';

/**
 * Board skins: bought once with coins on the server's price, then the board of every room
 * the owner hosts — opponents play on it without owning it, it's locked for the match, and
 * the board never changes the rules.
 */

/** Test Google Play: every purchase token is valid (for buying diamonds). */
const play: PlayVerifier = {
  async verify(_p: string, token: string): Promise<PlayPurchase> {
    return { purchaseState: 0, consumptionState: 0, orderId: `GPA.${token}`, obfuscatedExternalAccountId: token.split(':')[1] };
  },
  async consume() {},
};

let t: TestServer;
beforeAll(async () => {
  t = await bootServer({ playVerifier: play });
});
afterAll(() => t.stop());

type Board = { id: string; name: string; price: number; currency: 'coins' | 'gems'; rarity: string; collection: string; labels: string[]; published: boolean; featured: boolean; custom: boolean; look: { emblem: string } };
type Boards = { boards: Board[]; owned: string[]; equipped: string; wallet?: { balance: number } };

let adminApi: Awaited<ReturnType<typeof registerUser>> | null = null;
const getAdmin = async () => (adminApi ??= await registerUser(t, 'Admin', 'admin@example.com'));
let req = 1_000_000;
/** Give a player coins (an audited administrator adjustment). */
async function coins(userId: string, amount: number) {
  const admin = await getAdmin();
  const r = await admin.call('POST', `/admin/users/${userId}/adjust`, { amount, reason: 'Board shop test', requestId: `boards-test-${req++}` });
  expect(r.status).toBe(200);
}

async function balance(u: { call: <T>(m: string, p: string) => Promise<{ body: T }> }) {
  return (await u.call<{ wallet: { balance: number } }>('GET', '/wallet')).body.wallet.balance;
}

describe('board shop', () => {
  it('sells 45 distinct boards in ten collections, priced in coins', async () => {
    const u = await registerUser(t, 'Browser');
    const r = await u.call<Boards>('GET', '/boards');
    expect(r.body.boards.length).toBe(BOARDS.length);
    expect(BOARDS.length).toBeGreaterThanOrEqual(45);
    expect(new Set(r.body.boards.map((b) => b.collection))).toEqual(
      new Set(['couple', 'love', 'royal', 'luxury', 'nature', 'fantasy', 'neon', 'seasonal', 'exclusive', 'free']),
    );
    expect(r.body.boards.filter((b) => b.collection === 'couple').length).toBe(10);
    expect(r.body.boards.find((b) => b.id === 'royal_gold')!.price).toBe(5000);
    expect(r.body.boards.find((b) => b.id === 'love_story')!.price).toBe(3500);
    expect(r.body.boards.find((b) => b.id === 'classic')!.price).toBe(0);
    // Most boards cost coins; the rarest cost diamonds.
    expect(r.body.boards.find((b) => b.id === 'royal_gold')!.currency).toBe('coins');
    expect(r.body.boards.find((b) => b.id === 'ludox_legend')).toMatchObject({ currency: 'gems', price: 1500 });
    const gemBoards = r.body.boards.filter((b) => b.currency === 'gems');
    expect(gemBoards.length).toBeGreaterThanOrEqual(5);
    expect(r.body.boards.filter((b) => b.currency === 'coins').length).toBeGreaterThan(gemBoards.length);
    expect(r.body.owned).toEqual(['classic']);
    // Every board has its own identity: no two share the same colours and emblem.
    const looks = new Set(BOARDS.map((b) => `${b.look.panel}|${b.look.base}|${b.look.emblem}|${b.look.pattern}`));
    expect(looks.size).toBe(BOARDS.length);
    expect(r.body.boards.filter((b) => b.featured).length).toBeGreaterThan(0);
  });

  it('charges coins once, even for a double tap, and refuses without enough coins', async () => {
    const u = await registerUser(t, 'Buyer');
    const start = await balance(u);
    expect((await u.call('POST', '/boards/love_story/buy')).status).toBe(402);
    expect(await balance(u)).toBe(start);
    await coins(u.id, 5000);
    const both = await Promise.all([u.call<Boards>('POST', '/boards/love_story/buy'), u.call<Boards>('POST', '/boards/love_story/buy')]);
    expect(both.some((x) => x.status === 200)).toBe(true);
    expect(await balance(u)).toBe(start + 5000 - 3500);
    const again = await u.call<Boards>('POST', '/boards/love_story/buy');
    expect(again.status).toBe(200);
    expect(again.body.equipped).toBe('love_story');
    expect(await balance(u)).toBe(start + 1500);
    // It shows in the coin ledger as a shop purchase.
    const ledger = await u.call<{ items: { type: string; amount: number }[] }>('GET', '/wallet/transactions?type=shop_purchase');
    expect(ledger.body.items.map((x) => x.amount)).toEqual([-3500]);
    expect((await u.call('POST', '/boards/moonlight_love/equip')).status).toBe(403);
  });

  it('charges diamonds (not coins) for a diamond board, once', async () => {
    const u = await registerUser(t, 'GemBuyer');
    const wallet = async () => (await u.call<{ wallet: { balance: number; gems: number } }>('GET', '/wallet')).body.wallet;
    const start = await wallet();
    expect((await u.call('POST', '/boards/heart_kingdom/buy')).status).toBe(402);
    for (let i = 0; i < 3 && (await wallet()).gems < 650; i++) {
      await u.call('POST', '/gems/purchase', { productId: 'gems_500', purchaseToken: `ok:${u.id}:7700000${i}` });
    }
    const before = await wallet();
    expect(before.gems).toBeGreaterThanOrEqual(650);
    const both = await Promise.all([u.call<Boards>('POST', '/boards/heart_kingdom/buy'), u.call<Boards>('POST', '/boards/heart_kingdom/buy')]);
    expect(both.some((x) => x.status === 200)).toBe(true);
    const after = await wallet();
    expect(after.gems).toBe(before.gems - 650);
    expect(after.balance).toBe(start.balance);
    expect((await u.call<Boards>('GET', '/boards')).body.equipped).toBe('heart_kingdom');
    // An administrator can switch a board to diamonds; sales count the diamonds.
    const admin = await getAdmin();
    expect((await admin.call('PUT', '/admin/settings', { boardProducts: { classic: { currency: 'gems' } } })).status).toBe(400);
    expect((await admin.call('PUT', '/admin/settings', { boardProducts: { love_story: { currency: 'rupees' } } })).status).toBe(400);
    const sales = await admin.call<{ products: (Board & { sales: { gems: number } })[] }>('GET', '/admin/boards');
    expect(sales.body.products.find((b) => b.id === 'heart_kingdom')!.sales.gems).toBe(650);
  });

  it('the host\'s board is everyone\'s, opponents pay nothing, and it stays locked for the match', async () => {
    const host = await registerUser(t, 'Romeo');
    await coins(host.id, 10_000);
    expect((await host.call('POST', '/boards/moonlight_love/buy')).status).toBe(200);
    const guest = await registerUser(t, 'Juliet');
    const guestCoins = await balance(guest);
    const hs = await socketFor(t, host.cookie);
    const created = await emit<{ room: RoomView }>(hs, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    const code = created.ok ? created.room.code : '';
    let view: RoomView | null = null;
    for (let i = 0; i < 20 && view?.board !== 'moonlight_love'; i++) {
      const h = await emit<{ room: RoomView }>(hs, 'session:hello');
      view = h.ok ? h.room : null;
    }
    expect(view?.board).toBe('moonlight_love');
    const gs = await socketFor(t, guest.cookie);
    const joined = await emit<{ room: RoomView }>(gs, 'room:join', { code });
    expect(joined.ok && joined.room.board).toBe('moonlight_love');
    expect((await guest.call<Boards>('GET', '/boards')).body.owned).toEqual(['classic']);

    await emit(gs, 'room:ready', { ready: true });
    const started = nextUpdate(gs);
    expect((await emit(hs, 'room:start')).ok).toBe(true);
    await started;
    // The host switches board mid-match: the match keeps its board.
    expect((await host.call('POST', '/boards/classic/equip')).status).toBe(200);
    await new Promise((r) => setTimeout(r, 150));
    const mid = await emit<{ room: RoomView }>(gs, 'session:hello');
    expect(mid.ok && mid.room.status).toBe('playing');
    expect(mid.ok && mid.room.board).toBe('moonlight_love');
    // A player who drops and reconnects gets the same board.
    gs.disconnect();
    const back = await socketFor(t, guest.cookie);
    const hello = await emit<{ room: RoomView }>(back, 'session:hello');
    expect(hello.ok && hello.room.board).toBe('moonlight_love');
    // The guest never paid for it.
    expect(await balance(guest)).toBe(guestCoins);
  });

  it('with a free host, the room plays on the paid board of whoever sat down first', async () => {
    const host = await registerUser(t, 'FreeHost');
    const first = await registerUser(t, 'FirstPaid');
    const second = await registerUser(t, 'SecondPaid');
    await coins(first.id, 10_000);
    await coins(second.id, 10_000);
    expect((await first.call('POST', '/boards/rose_garden/buy')).status).toBe(200);
    expect((await second.call('POST', '/boards/sunset_date/buy')).status).toBe(200);
    const hs = await socketFor(t, host.cookie);
    const created = await emit<{ room: RoomView }>(hs, 'room:create', { modeId: 'friendly', maxPlayers: 3 });
    const code = created.ok ? created.room.code : '';
    expect(created.ok && created.room.board).toBe('classic');
    expect(created.ok && created.room.boardBy).toBeNull();
    const fs = await socketFor(t, first.cookie);
    expect((await emit(fs, 'room:join', { code })).ok).toBe(true);
    await new Promise((r) => setTimeout(r, 30));
    const ss = await socketFor(t, second.cookie);
    expect((await emit(ss, 'room:join', { code })).ok).toBe(true);
    let view: RoomView | null = null;
    for (let i = 0; i < 20 && view?.board !== 'rose_garden'; i++) {
      const h = await emit<{ room: RoomView }>(hs, 'session:hello');
      view = h.ok ? h.room : null;
    }
    // The first to sit down with a paid board wins over later ones.
    expect(view?.board).toBe('rose_garden');
    expect(view?.boardBy).toMatch(/^FirstPaid/);
    // A host with a paid board always comes first.
    await coins(host.id, 10_000);
    expect((await host.call('POST', '/boards/valentine/buy')).status).toBe(200);
    for (let i = 0; i < 20 && view?.board !== 'valentine'; i++) {
      const h = await emit<{ room: RoomView }>(fs, 'session:hello');
      view = h.ok ? h.room : null;
    }
    expect(view?.board).toBe('valentine');
  });

  it('a board the host doesn\'t own can\'t be put on, so a room never shows it', async () => {
    const cheeky = await registerUser(t, 'Cheeky');
    expect((await cheeky.call('POST', '/boards/dragon_kingdom/equip')).status).toBe(403);
    const s = await socketFor(t, cheeky.cookie);
    const created = await emit<{ room: RoomView }>(s, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    expect(created.ok && created.room.board).toBe('classic');
  });

  it('an unpublished board leaves the shop and cannot be bought; owners keep it', async () => {
    const admin = await getAdmin();
    const owner = await registerUser(t, 'Gardener');
    await coins(owner.id, 5000);
    expect((await owner.call('POST', '/boards/rose_garden/buy')).status).toBe(200);
    expect((await admin.call('PUT', '/admin/settings', { boardProducts: { rose_garden: { published: false } } })).status).toBe(200);
    const late = await registerUser(t, 'LateGardener');
    await coins(late.id, 5000);
    const before = await balance(late);
    expect((await late.call<Boards>('GET', '/boards')).body.boards.some((b) => b.id === 'rose_garden')).toBe(false);
    expect((await late.call('POST', '/boards/rose_garden/buy')).status).toBe(404);
    expect(await balance(late)).toBe(before);
    expect((await owner.call<Boards>('GET', '/boards')).body.boards.some((b) => b.id === 'rose_garden')).toBe(true);
    await admin.call('PUT', '/admin/settings', { boardProducts: { rose_garden: { published: true } } });
  });

  it('administrators edit and create boards and see sales; others cannot', async () => {
    const admin = await getAdmin();
    const edit = await admin.call('PUT', '/admin/settings', {
      boardProducts: { sunset_date: { name: 'Golden Hour', rarity: 'legendary', featured: true, look: { ambient: 'hearts', emblem: 'twin_hearts' } } },
      shopPrices: { 'board:sunset_date': 2000 },
    });
    expect(edit.status).toBe(200);
    const created = await admin.call('PUT', '/admin/settings', {
      boardCustom: [
        {
          id: 'custom_eid_night',
          name: 'Eid Night',
          price: 2500,
          rarity: 'epic',
          collection: 'seasonal',
          look: { ...BOARDS.find((b) => b.id === 'moonlight_love')!.look, panel: '#204a3a', emblem: 'moon', ambient: 'stars' },
        },
      ],
    });
    expect(created.status).toBe(200);
    const u = await registerUser(t, 'Shopper');
    const list = (await u.call<Boards>('GET', '/boards')).body.boards;
    const sd = list.find((b) => b.id === 'sunset_date')!;
    expect(sd.name).toBe('Golden Hour');
    expect(sd.price).toBe(2000);
    expect(sd.rarity).toBe('legendary');
    expect(sd.featured).toBe(true);
    expect(sd.look.emblem).toBe('twin_hearts');
    const eid = list.find((b) => b.id === 'custom_eid_night')!;
    expect(eid.custom).toBe(true);
    expect(eid.collection).toBe('seasonal');
    // A custom board is bought and hosted like any other.
    await coins(u.id, 3000);
    expect((await u.call<Boards>('POST', '/boards/custom_eid_night/buy')).body.equipped).toBe('custom_eid_night');
    const s = await socketFor(t, u.cookie);
    await emit(s, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    let view: RoomView | null = null;
    for (let i = 0; i < 20 && view?.board !== 'custom_eid_night'; i++) {
      const h = await emit<{ room: RoomView }>(s, 'session:hello');
      view = h.ok ? h.room : null;
    }
    expect(view?.board).toBe('custom_eid_night');
    // Sales are real.
    const stats = await admin.call<{ products: (Board & { sales: { owners: number; coins: number } })[] }>('GET', '/admin/boards');
    expect(stats.body.products.find((p) => p.id === 'custom_eid_night')!.sales).toMatchObject({ owners: 1, coins: 2500 });
    expect(stats.body.products.find((p) => p.id === 'love_story')!.sales.owners).toBeGreaterThan(0);
    // Not for players; bad input refused.
    expect((await u.call('GET', '/admin/boards')).status).toBe(403);
    expect((await u.call('PUT', '/admin/settings', { boardProducts: { love_story: { published: false } } })).status).toBe(403);
    expect((await admin.call('PUT', '/admin/settings', { boardProducts: { love_story: { look: { panel: 'pink' } } } })).status).toBe(400);
    expect((await admin.call('PUT', '/admin/settings', { boardProducts: { classic: { published: false } } })).status).toBe(400);
    expect((await admin.call('PUT', '/admin/settings', { boardCustom: [{ id: 'royal_gold', name: 'X', price: 1, look: {} }] })).status).toBe(400);
    expect((await admin.call('PUT', '/admin/settings', { boardCustom: [{ id: 'custom_x', name: 'X', price: 1, look: { panel: '#ffffff' } }] })).status).toBe(400);
  });
});
