import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { GameUpdate, RoomView } from '../shared/protocol';
import type { PlayPurchase, PlayVerifier } from '../server/googleplay';
import { bootServer, emit, nextUpdate, registerUser, socketFor, tick, type C, type TestServer } from './helpers';

/**
 * Two people in one match must never both be able to roll. Whatever the clients send and
 * however fast, only the player whose turn it is gets a roll through, and while the previous
 * roller may still pay to re-roll, the next player waits (the update tells their client how
 * long, so their dice stays asleep instead of looking rollable).
 */

const play: PlayVerifier = {
  async verify(_p: string, token: string): Promise<PlayPurchase> {
    return { purchaseState: 0, consumptionState: 0, orderId: `GPA.${token}`, obfuscatedExternalAccountId: token.split(':')[1] };
  },
  async consume() {},
};

let t: TestServer;
const rolls: number[] = [];

beforeAll(async () => {
  t = await bootServer({ roll: () => rolls.shift() ?? 3, playVerifier: play });
});
afterAll(() => t.stop());

/** A started two player match: a is red (first), b is yellow. `gems` gives a diamonds first. */
async function match(gems = false, srv: TestServer = t): Promise<{ a: C; b: C; seq: number }> {
  const ua = await registerUser(srv, 'Ria');
  const ub = await registerUser(srv, 'Sam');
  if (gems) await ua.call('POST', '/gems/purchase', { productId: 'gems_50', purchaseToken: `ok:${ua.id}:${Date.now()}000` });
  const a = await socketFor(srv, ua.cookie);
  const b = await socketFor(srv, ub.cookie);
  const created = await emit<{ room: RoomView }>(a, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
  const code = created.ok ? created.room.code : '';
  await emit(b, 'room:join', { code });
  await emit(b, 'room:ready', { ready: true });
  const started = nextUpdate(b);
  await emit(a, 'room:start');
  const first = await started;
  // Diamond balances are read when the match starts.
  await tick(100);
  return { a, b, seq: first.state.seq };
}

describe('only the player whose turn it is can roll', () => {
  it('both players tapping roll at the same moment: exactly one roll, by the current player', async () => {
    const { a, b, seq } = await match();
    // Red's roll passes the turn; yellow's tap was made on the state before it, so it is
    // refused rather than becoming a second roll at the same moment.
    rolls.push(2);
    const [ra, rb] = await Promise.all([emit(a, 'game:roll', { seq }), emit(b, 'game:roll', { seq })]);
    expect(ra.ok).toBe(true);
    expect(rb.ok).toBe(false);
  });

  it('the next player cannot roll while the previous roll is still being shown', async () => {
    const slow = await bootServer({ roll: () => 2, timings: { animScale: 1 } });
    try {
      const { a, b } = await match(false, slow);
      const passed = nextUpdate(b, (u: GameUpdate) => u.events.some((e) => e.type === 'rolled'));
      expect((await emit(a, 'game:roll')).ok).toBe(true);
      const u = await passed;
      expect(u.state.current).toBe(1);
      // Without a version (an older app), the server still makes yellow wait for the animation.
      expect(await emit(b, 'game:roll')).toEqual({ ok: false, error: 'Wait a moment…' });
      await tick(1400);
      expect((await emit(b, 'game:roll')).ok).toBe(true);
    } finally {
      await slow.stop();
    }
  }, 15_000);

  it('holds the next player while the previous roller may re-roll, and says how long', async () => {
    const { a, b } = await match(true);
    // Red rolls a 4 with everything in the yard: the turn passes, but red could re-roll.
    rolls.push(4);
    const passed = nextUpdate(b, (u: GameUpdate) => u.events.some((e) => e.type === 'rolled'));
    expect((await emit(a, 'game:roll')).ok).toBe(true);
    const u = await passed;
    expect(u.state.current).toBe(1);
    expect(u.reroll?.color).toBe('red');
    expect(u.reroll?.msLeft).toBeGreaterThan(0);
    // Yellow can't roll during the hold (their client keeps the dice asleep until msLeft).
    expect(await emit(b, 'game:roll')).toEqual({ ok: false, error: 'Wait a moment…' });
    // Red can't take a normal roll on yellow's turn either.
    expect(await emit(a, 'game:roll')).toEqual({ ok: false, error: 'It is not your turn' });
    await tick((u.reroll?.msLeft ?? 0) + 50);
    rolls.push(2);
    expect((await emit(b, 'game:roll')).ok).toBe(true);
  });
});
