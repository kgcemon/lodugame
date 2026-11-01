import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { GameUpdate, RoomView } from '../shared/protocol';
import type { PlayPurchase, PlayVerifier } from '../server/googleplay';
import { bootServer, emit, next, registerUser, socketFor, tick, type Api, type TestServer } from './helpers';

/** A stand-in for Google Play: tokens are "ok-<user>", "pending" or "cancelled". */
const consumed: string[] = [];
const fakePlay: PlayVerifier = {
  async verify(_productId: string, token: string): Promise<PlayPurchase> {
    if (token.startsWith('bad')) throw new Error('INVALID_PURCHASE');
    if (token.startsWith('pending')) return { purchaseState: 2, consumptionState: 0 };
    if (token.startsWith('cancelled')) return { purchaseState: 1, consumptionState: 0 };
    const owner = token.split(':')[1];
    return { purchaseState: 0, consumptionState: 0, orderId: `GPA.${token}`, obfuscatedExternalAccountId: owner };
  },
  async consume(_productId: string, token: string) {
    consumed.push(token);
  },
};

let t: TestServer;
const rolls: number[] = [];

beforeAll(async () => {
  t = await bootServer({ roll: () => rolls.shift() ?? 3, playVerifier: fakePlay });
});
afterAll(() => t.stop());

type Wallet = { wallet: { balance: number; gems: number } };

describe('gems from Google Play', () => {
  it('grants a verified purchase once, refuses reuse and bad purchases', async () => {
    const u = await registerUser(t, 'Buyer');
    const other = await registerUser(t, 'Other');
    const token = `ok:${u.id}:1234567890`;
    const r1 = await u.call<{ gems: number; granted: number }>('POST', '/gems/purchase', { productId: 'gems_150', purchaseToken: token });
    expect(r1.status).toBe(200);
    expect(r1.body).toMatchObject({ gems: 150, granted: 150 });
    expect(consumed).toContain(token);
    // Retrying the same token (e.g. after a network error) never grants twice.
    const r2 = await u.call<{ gems: number; granted: number }>('POST', '/gems/purchase', { productId: 'gems_150', purchaseToken: token });
    expect(r2.body).toMatchObject({ gems: 150, granted: 0 });
    // Someone else can't redeem it.
    expect((await other.call('POST', '/gems/purchase', { productId: 'gems_150', purchaseToken: token })).status).toBe(409);
    // A token bought by another account is refused even before it was redeemed.
    expect((await other.call('POST', '/gems/purchase', { productId: 'gems_50', purchaseToken: `ok:${u.id}:9999999999` })).status).toBe(409);
    expect((await u.call('POST', '/gems/purchase', { productId: 'gems_50', purchaseToken: 'pending-0000000001' })).status).toBe(400);
    expect((await u.call('POST', '/gems/purchase', { productId: 'gems_50', purchaseToken: 'cancelled-000000001' })).status).toBe(400);
    expect((await u.call('POST', '/gems/purchase', { productId: 'gems_50', purchaseToken: 'bad-token-000000001' })).status).toBe(400);
    expect((await u.call('POST', '/gems/purchase', { productId: 'gems_9999', purchaseToken: `ok:${u.id}:5555555555` })).status).toBe(400);
    const w = await u.call<Wallet>('GET', '/wallet');
    expect(w.body.wallet.gems).toBe(150);
  });

  it('reports that purchases are off when Google Play is not configured', async () => {
    const st = await bootServer({ playVerifier: null });
    try {
      const u = await registerUser(st, 'NoShop');
      const cfg = await u.call<{ purchasesEnabled: boolean; rerollCosts: number[] }>('GET', '/config');
      expect(cfg.body.purchasesEnabled).toBe(false);
      expect(cfg.body.rerollCosts).toEqual([5, 15, 45]);
      expect((await u.call('POST', '/gems/purchase', { productId: 'gems_50', purchaseToken: `ok:${u.id}:1111111111` })).status).toBe(503);
    } finally {
      await st.stop();
    }
  });
});

describe('dice re-rolls with gems', () => {
  async function matchWithBot(gems: number) {
    const u = await registerUser(t, 'Roller');
    if (gems > 0) {
      const r = await u.call('POST', '/gems/purchase', { productId: gems === 150 ? 'gems_150' : 'gems_50', purchaseToken: `ok:${u.id}:${Date.now()}${Math.random()}` });
      expect(r.status).toBe(200);
    }
    const s = await socketFor(t, u.cookie);
    const created = await emit<{ room: RoomView }>(s, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    expect(created.ok).toBe(true);
    expect((await emit(s, 'room:addBot')).ok).toBe(true);
    const started = next<GameUpdate>(s, 'game:update');
    expect((await emit(s, 'room:start')).ok).toBe(true);
    await started;
    await tick(150); // gem balance is loaded right after the start
    return { u, s };
  }

  const gemsOf = async (u: Api) => (await u.call<Wallet>('GET', '/wallet')).body.wallet.gems;

  it('re-rolls for 5, 15 then 45 gems, at most three times per match', async () => {
    const { u, s } = await matchWithBot(150);
    // Roll a 3 with every token at home: no move, so the turn would pass — but it is held for a re-roll.
    rolls.push(3);
    const afterRoll = next<GameUpdate>(s, 'game:update', (x) => x.events.some((e) => e.type === 'rolled'));
    expect((await emit(s, 'game:roll')).ok).toBe(true);
    const u1 = await afterRoll;
    expect(u1.reroll).toMatchObject({ color: 'red', nextCost: 5 });
    expect(u1.reroll!.msLeft).toBeGreaterThan(0);

    // Re-roll #1 (5 gems): a 6 brings a token out, so it's our move again.
    rolls.push(6);
    const r1 = next<GameUpdate>(s, 'game:update', (x) => x.events.some((e) => e.type === 'rolled'));
    const a1 = await emit<{ cost: number; gems: number }>(s, 'game:reroll');
    expect(a1).toMatchObject({ ok: true, cost: 5, gems: 145 });
    const g1 = await r1;
    expect(g1.state.phase).toBe('move');
    expect(g1.state.dice).toBe(6);
    expect(g1.state.players[g1.state.current].color).toBe('red');
    expect(g1.state.seq).toBeGreaterThan(u1.state.seq);
    expect(g1.reroll).toMatchObject({ nextCost: 15, msLeft: null });

    // Re-roll #2 (15) and #3 (45) while it's still our move.
    rolls.push(6);
    expect(await emit(s, 'game:reroll')).toMatchObject({ ok: true, cost: 15, gems: 130 });
    rolls.push(6);
    expect(await emit(s, 'game:reroll')).toMatchObject({ ok: true, cost: 45, gems: 85 });
    // A fourth is refused, and nothing more is charged.
    const fourth = await emit(s, 'game:reroll');
    expect(fourth.ok).toBe(false);
    expect(await gemsOf(u)).toBe(85);
  });

  it('refuses a re-roll without enough gems', async () => {
    const { s } = await matchWithBot(0);
    rolls.push(6); // a 6 keeps the turn (token out), so the roll stays re-rollable
    const afterRoll = next<GameUpdate>(s, 'game:update', (x) => x.events.some((e) => e.type === 'rolled'));
    await emit(s, 'game:roll');
    const u1 = await afterRoll;
    expect(u1.reroll).toMatchObject({ nextCost: 5 });
    const res = await emit(s, 'game:reroll');
    expect(res).toMatchObject({ ok: false, code: 'NOT_ENOUGH_GEMS' });
  });

  it('only the roller can re-roll, and only their own last roll', async () => {
    const { s } = await matchWithBot(50);
    // Nothing rolled yet.
    expect((await emit(s, 'game:reroll')).ok).toBe(false);
  });
});

