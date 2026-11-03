import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { RoomView } from '../shared/protocol';
import type { PlayPurchase, PlayVerifier } from '../server/googleplay';
import { DICE_SKINS } from '../shared/skins';
import { bootServer, emit, next, registerUser, socketFor, type TestServer } from './helpers';

/** Dice skins: bought once with diamonds on the server's price, then shown in matches. */

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

type Skins = { skins: typeof DICE_SKINS; owned: string[]; equipped: string; wallet?: { gems: number } };

const ruby = DICE_SKINS.find((s) => s.id === 'ruby')!;
let token = 7_000_000_000;
let adminApi: Awaited<ReturnType<typeof registerUser>> | null = null;
/** The one administrator (the test server knows a single admin email). */
const getAdmin = async () => (adminApi ??= await registerUser(t, 'Admin', 'admin@example.com'));
/** Give a player diamonds through (test) Google Play purchases of 50. */
async function fund(u: { id: string; call: (m: string, p: string, b?: unknown) => Promise<unknown> }, packs: number) {
  for (let i = 0; i < packs; i++) await u.call('POST', '/gems/purchase', { productId: 'gems_50', purchaseToken: `ok:${u.id}:${token++}` });
}

describe('dice skin shop', () => {
  it('lists the catalogue; everyone starts with the classic dice', async () => {
    const u = await registerUser(t, 'Shopper');
    const r = await u.call<Skins>('GET', '/skins');
    expect(r.body.skins.map((s) => s.id)).toContain('ruby');
    expect(r.body.owned).toEqual(['classic']);
    expect(r.body.equipped).toBe('classic');
    const cfg = await u.call<{ diceSkins: unknown[] }>('GET', '/config');
    expect(cfg.body.diceSkins.length).toBe(DICE_SKINS.length);
  });

  it('refuses a purchase without enough diamonds and charges nothing', async () => {
    const u = await registerUser(t, 'Broke');
    const r = await u.call<{ code: string }>('POST', '/skins/ruby/buy');
    expect(r.status).toBe(402);
    expect((await u.call<Skins>('GET', '/skins')).body.owned).toEqual(['classic']);
  });

  it('buys once with diamonds, equips it, and never charges twice', async () => {
    const u = await registerUser(t, 'Buyer');
    await fund(u, 3);
    const r1 = await u.call<Skins>('POST', '/skins/ruby/buy');
    expect(r1.status).toBe(200);
    expect(r1.body.owned).toEqual(expect.arrayContaining(['classic', 'ruby']));
    expect(r1.body.equipped).toBe('ruby');
    expect(r1.body.wallet?.gems).toBe(150 - ruby.price);
    // A retry (double tap, flaky network) keeps the dice and the diamonds.
    const r2 = await u.call<Skins>('POST', '/skins/ruby/buy');
    expect(r2.body.wallet?.gems).toBe(150 - ruby.price);
    // Two concurrent taps: still one charge.
    await fund(u, 3);
    const both = await Promise.all([u.call<Skins>('POST', '/skins/emerald/buy'), u.call<Skins>('POST', '/skins/emerald/buy')]);
    expect(both.some((x) => x.status === 200)).toBe(true);
    const w = await u.call<{ wallet: { gems: number } }>('GET', '/wallet');
    expect(w.body.wallet.gems).toBe(300 - ruby.price - DICE_SKINS.find((s) => s.id === 'emerald')!.price);
  });

  it('only equips dice the player owns, and rejects unknown ones', async () => {
    const u = await registerUser(t, 'Picky');
    expect((await u.call('POST', '/skins/gold/equip')).status).toBe(403);
    expect((await u.call('POST', '/skins/nope/buy')).status).toBe(404);
    const back = await u.call<Skins>('POST', '/skins/classic/equip');
    expect(back.body.equipped).toBe('classic');
  });

  it('shows the equipped dice on the player\'s seat, and updates it live', async () => {
    const u = await registerUser(t, 'Show');
    await fund(u, 3);
    await u.call('POST', '/skins/ruby/buy');
    const s = await socketFor(t, u.cookie);
    const created = await emit<{ room: RoomView }>(s, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    expect(created.ok).toBe(true);
    // The skin is looked up as the seat is taken; ask the server what the room shows now.
    let shown: RoomView | null = null;
    for (let i = 0; i < 20 && shown?.seats[shown.you]?.diceSkin !== 'ruby'; i++) {
      const h = await emit<{ room: RoomView }>(s, 'session:hello');
      shown = h.ok ? h.room : null;
    }
    expect(shown?.seats[shown.you].diceSkin).toBe('ruby');
    const changed = next<RoomView>(s, 'room:state', (r) => r.seats[r.you]?.diceSkin === 'classic');
    await u.call('POST', '/skins/classic/equip');
    expect((await changed).seats[0].diceSkin).toBe('classic');
  });
});

type Frames = { frames: { id: string; price: number }[]; owned: string[]; equipped: string | null; wallet?: { gems: number } };

describe('profile frames and shop prices', () => {
  it('sells frames at the price the administrator sets, and shows the frame at the table', async () => {
    const admin = await getAdmin();
    const set = await admin.call<{ settings: { shopPrices: Record<string, number> } }>('PUT', '/admin/settings', { shopPrices: { 'frame:fire': 40, 'dice:ruby': 10 } });
    expect(set.status).toBe(200);
    expect(set.body.settings.shopPrices['frame:fire']).toBe(40);
    expect((await admin.call('PUT', '/admin/settings', { shopPrices: { 'frame:nope': 1 } })).status).toBe(400);
    expect((await admin.call('PUT', '/admin/settings', { shopPrices: { 'frame:fire': -5 } })).status).toBe(400);

    const u = await registerUser(t, 'Framed');
    const list = await u.call<Frames>('GET', '/frames');
    expect(list.body.frames.find((f) => f.id === 'fire')?.price).toBe(40);
    expect(list.body.owned).toEqual([]);
    expect(list.body.equipped).toBeNull();
    const cfg = await u.call<{ diceSkins: { id: string; price: number }[] }>('GET', '/config');
    expect(cfg.body.diceSkins.find((d) => d.id === 'ruby')?.price).toBe(10);

    await u.call('POST', '/gems/purchase', { productId: 'gems_50', purchaseToken: `ok:${u.id}:4444444444` });
    const bought = await u.call<Frames>('POST', '/frames/fire/buy');
    expect(bought.status).toBe(200);
    expect(bought.body.equipped).toBe('fire');
    expect(bought.body.wallet?.gems).toBe(10);
    expect((await u.call<Frames>('POST', '/frames/fire/buy')).body.wallet?.gems).toBe(10);
    expect((await u.call('POST', '/frames/hearts/equip')).status).toBe(403);

    const me = await u.call<{ player: { frame: string | null } }>('GET', `/players/${(await u.call<{ user: { publicId: string } }>('GET', '/me/profile')).body.user.publicId}`);
    expect(me.body.player.frame).toBe('fire');

    const s = await socketFor(t, u.cookie);
    await emit<{ room: RoomView }>(s, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    let shown: RoomView | null = null;
    for (let i = 0; i < 20 && shown?.seats[shown.you]?.frame !== 'fire'; i++) {
      const h = await emit<{ room: RoomView }>(s, 'session:hello');
      shown = h.ok ? h.room : null;
    }
    expect(shown?.seats[shown.you].frame).toBe('fire');
    const off = next<RoomView>(s, 'room:state', (r) => r.seats[r.you]?.frame === null);
    const r = await u.call<Frames>('POST', '/frames/none/equip');
    expect(r.body.equipped).toBeNull();
    await off;
  });
});

type Boards = { boards: { id: string; price: number; royal: boolean }[]; owned: string[]; equipped: string; wallet?: { gems: number } };

describe('royal boards', () => {
  it('everyone has the classic board; a bought board is the room\'s board for every player while its owner hosts', async () => {
    const host = await registerUser(t, 'King');
    const start = await host.call<Boards>('GET', '/boards');
    expect(start.body.owned).toEqual(['classic']);
    expect(start.body.equipped).toBe('classic');
    expect(start.body.boards.find((b) => b.id === 'classic')?.price).toBe(0);
    expect(start.body.boards.filter((b) => b.royal).length).toBeGreaterThan(0);
    expect((await host.call('POST', '/boards/royal_gold/buy')).status).toBe(402);

    for (let i = 0; i < 4; i++) {
      await host.call('POST', '/gems/purchase', { productId: 'gems_50', purchaseToken: `ok:${host.id}:555555555${i}` });
    }
    const price = start.body.boards.find((b) => b.id === 'royal_gold')!.price;
    const bought = await host.call<Boards>('POST', '/boards/royal_gold/buy');
    expect(bought.status).toBe(200);
    expect(bought.body.equipped).toBe('royal_gold');
    expect(bought.body.wallet?.gems).toBe(200 - price);
    expect((await host.call('POST', '/boards/maharaja/equip')).status).toBe(403);

    const hs = await socketFor(t, host.cookie);
    const created = await emit<{ room: RoomView }>(hs, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    expect(created.ok).toBe(true);
    const code = created.ok ? created.room.code : '';
    let shown: RoomView | null = null;
    for (let i = 0; i < 20 && shown?.board !== 'royal_gold'; i++) {
      const h = await emit<{ room: RoomView }>(hs, 'session:hello');
      shown = h.ok ? h.room : null;
    }
    expect(shown?.board).toBe('royal_gold');

    // The guest owns no board, yet plays on the host's.
    const guest = await registerUser(t, 'Visitor');
    const gs = await socketFor(t, guest.cookie);
    const joined = await emit<{ room: RoomView }>(gs, 'room:join', { code });
    expect(joined.ok && joined.room.board).toBe('royal_gold');

    // The host switches back: everyone sees the classic board at once.
    const back = next<RoomView>(gs, 'room:state', (r) => r.board === 'classic');
    await host.call('POST', '/boards/classic/equip');
    expect((await back).board).toBe('classic');
  });
});

type Product = { id: string; name: string; price: number; rarity: string; collection: string; effect: string; labels: string[]; limit: number | null; left: number | null; published: boolean; featured: boolean };
type DiceShop = { skins: Product[]; owned: string[]; equipped: string; wallet?: { gems: number } };

describe('premium dice collection', () => {
  it('lists 25 premium dice across five collections, every rarity, with honest labels', async () => {
    const u = await registerUser(t, 'Collector');
    const shop = (await u.call<DiceShop>('GET', '/skins')).body.skins;
    const premium = shop.filter((d) => ['royal', 'gemstone', 'fantasy', 'special', 'exclusive'].includes(d.collection));
    expect(premium.length).toBe(25);
    expect(new Set(shop.map((d) => d.rarity))).toEqual(new Set(['common', 'rare', 'epic', 'legendary', 'mythic', 'ultimate']));
    for (const c of ['royal', 'gemstone', 'fantasy', 'special', 'exclusive']) expect(premium.filter((d) => d.collection === c).length).toBe(5);
    const ultimate = shop.find((d) => d.id === 'ludox_ultimate')!;
    expect(ultimate.rarity).toBe('ultimate');
    expect(ultimate.labels).toContain('EXCLUSIVE');
    expect(shop.find((d) => d.id === 'dragon')!.labels).toContain('LEGENDARY');
    // Few sales so far in this test run: no sales labels, and nothing is limited.
    expect(shop.some((d) => d.labels.includes('BEST SELLER') || d.labels.includes('MOST POPULAR'))).toBe(false);
    expect(shop.some((d) => d.labels.includes('LIMITED EDITION'))).toBe(false);
    expect(shop.filter((d) => d.featured).length).toBeGreaterThan(0);
  });

  it('an unpublished die leaves the shop and cannot be bought, but its owners keep it', async () => {
    const admin = await getAdmin();
    const owner = await registerUser(t, 'EarlyBird');
    await fund(owner, 12);
    expect((await owner.call('POST', '/skins/dragon/buy')).status).toBe(200);
    const off = await admin.call('PUT', '/admin/settings', { diceProducts: { dragon: { published: false } } });
    expect(off.status).toBe(200);
    const late = await registerUser(t, 'LateBird');
    await fund(late, 12);
    expect((await late.call<DiceShop>('GET', '/skins')).body.skins.some((d) => d.id === 'dragon')).toBe(false);
    expect((await late.call('POST', '/skins/dragon/buy')).status).toBe(404);
    expect((await late.call<{ wallet: { gems: number } }>('GET', '/wallet')).body.wallet.gems).toBe(600);
    // The owner still has it in their collection and can put it on.
    expect((await owner.call<DiceShop>('GET', '/skins')).body.skins.some((d) => d.id === 'dragon')).toBe(true);
    expect((await owner.call('POST', '/skins/classic/equip')).status).toBe(200);
    expect((await owner.call<DiceShop>('POST', '/skins/dragon/equip')).body.equipped).toBe('dragon');
    await admin.call('PUT', '/admin/settings', { diceProducts: { dragon: { published: true } } });
  });

  it('a limited edition sells exactly its stock; a sold-out purchase charges nothing', async () => {
    const admin = await getAdmin();
    const set = await admin.call('PUT', '/admin/settings', { diceProducts: { amethyst: { limit: 1 } }, shopPrices: { 'dice:amethyst': 10 } });
    expect(set.status).toBe(200);
    const a = await registerUser(t, 'FirstBuyer');
    const b = await registerUser(t, 'SecondBuyer');
    await fund(a, 1);
    await fund(b, 1);
    const before = (await b.call<DiceShop>('GET', '/skins')).body.skins.find((d) => d.id === 'amethyst')!;
    expect(before.labels).toContain('LIMITED EDITION');
    expect(before.left).toBe(1);
    expect((await a.call('POST', '/skins/amethyst/buy')).status).toBe(200);
    expect((await a.call('POST', '/skins/amethyst/buy')).status).toBe(200); // a retry doesn't take another copy
    const sold = await b.call<{ error: string }>('POST', '/skins/amethyst/buy');
    expect(sold.status).toBe(409);
    expect((await b.call<{ wallet: { gems: number } }>('GET', '/wallet')).body.wallet.gems).toBe(50);
    expect((await b.call<DiceShop>('GET', '/skins')).body.skins.find((d) => d.id === 'amethyst')!.left).toBe(0);
    await admin.call('PUT', '/admin/settings', { diceProducts: { amethyst: { limit: null } } });
  });

  it('administrators edit dice and see real sales; others cannot; bad input is refused', async () => {
    const admin = await getAdmin();
    const rename = await admin.call('PUT', '/admin/settings', { diceProducts: { galaxy: { name: 'Milky Way', rarity: 'legendary', effect: 'sparkle', featured: true } } });
    expect(rename.status).toBe(200);
    const u = await registerUser(t, 'Browser');
    const g = (await u.call<DiceShop>('GET', '/skins')).body.skins.find((d) => d.id === 'galaxy')!;
    expect(g.name).toBe('Milky Way');
    expect(g.rarity).toBe('legendary');
    expect(g.effect).toBe('sparkle');
    expect(g.featured).toBe(true);
    const stats = await admin.call<{ products: (Product & { sales: { owners: number; diamonds: number } })[] }>('GET', '/admin/dice');
    expect(stats.status).toBe(200);
    expect(stats.body.products.find((p) => p.id === 'ruby')!.sales.owners).toBeGreaterThan(0);
    expect(stats.body.products.find((p) => p.id === 'ruby')!.sales.diamonds).toBeGreaterThan(0);
    expect((await u.call('GET', '/admin/dice')).status).toBe(403);
    expect((await u.call('PUT', '/admin/settings', { diceProducts: { galaxy: { published: false } } })).status).toBe(403);
    expect((await admin.call('PUT', '/admin/settings', { diceProducts: { galaxy: { rarity: 'godlike' } } })).status).toBe(400);
    expect((await admin.call('PUT', '/admin/settings', { diceProducts: { classic: { published: false } } })).status).toBe(400);
    expect((await admin.call('PUT', '/admin/settings', { diceProducts: { nope: { featured: true } } })).status).toBe(400);
  });

  it("every player at the table sees each player's own die", async () => {
    const host = await registerUser(t, 'GoldHost');
    await fund(host, 10);
    expect((await host.call('POST', '/skins/gold/buy')).status).toBe(200);
    const guest = await registerUser(t, 'IceGuest');
    await fund(guest, 6);
    expect((await guest.call('POST', '/skins/ice_king/buy')).status).toBe(200);
    const hs = await socketFor(t, host.cookie);
    const created = await emit<{ room: RoomView }>(hs, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    const code = created.ok ? created.room.code : '';
    const gs = await socketFor(t, guest.cookie);
    await emit<{ room: RoomView }>(gs, 'room:join', { code });
    // Cosmetics are looked up as seats are taken: wait until the guest sees both.
    let view: RoomView | null = null;
    for (let i = 0; i < 30; i++) {
      const h = await emit<{ room: RoomView }>(gs, 'session:hello');
      view = h.ok ? h.room : null;
      const skins = view?.seats.map((x) => x.diceSkin) ?? [];
      if (skins.includes('gold') && skins.includes('ice_king')) break;
    }
    const byName = Object.fromEntries((view?.seats ?? []).map((x) => [x.name, x.diceSkin]));
    expect(byName[host.username]).toBe('gold');
    expect(byName[guest.username]).toBe('ice_king');
  });
});
