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
    await u.call('POST', '/gems/purchase', { productId: 'gems_50', purchaseToken: `ok:${u.id}:1111111111` });
    const r1 = await u.call<Skins>('POST', '/skins/ruby/buy');
    expect(r1.status).toBe(200);
    expect(r1.body.owned).toEqual(expect.arrayContaining(['classic', 'ruby']));
    expect(r1.body.equipped).toBe('ruby');
    expect(r1.body.wallet?.gems).toBe(50 - ruby.price);
    // A retry (double tap, flaky network) keeps the dice and the diamonds.
    const r2 = await u.call<Skins>('POST', '/skins/ruby/buy');
    expect(r2.body.wallet?.gems).toBe(50 - ruby.price);
    // Two concurrent taps: still one charge.
    await u.call('POST', '/gems/purchase', { productId: 'gems_50', purchaseToken: `ok:${u.id}:2222222222` });
    const both = await Promise.all([u.call<Skins>('POST', '/skins/emerald/buy'), u.call<Skins>('POST', '/skins/emerald/buy')]);
    expect(both.some((x) => x.status === 200)).toBe(true);
    const w = await u.call<{ wallet: { gems: number } }>('GET', '/wallet');
    expect(w.body.wallet.gems).toBe(100 - ruby.price - DICE_SKINS.find((s) => s.id === 'emerald')!.price);
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
    await u.call('POST', '/gems/purchase', { productId: 'gems_50', purchaseToken: `ok:${u.id}:3333333333` });
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
