import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { GameUpdate, MatchIntro, MatchResult, MatchSettled, RoomView } from '../shared/protocol';
import { bootServer, emit, next, nextUpdate, registerUser, socketFor, tick, type C, type TestServer } from './helpers';

/** The Match Found introduction and what stands behind its coins, against the real server over real sockets. */

const INTRO_MS = 500;
const DROP_MS = 1_000;
const FEE = 10; // the Quick Match entry fee
const REWARD = 20; // 2 x the fee

let t: TestServer;
const rolls: number[] = [];

beforeAll(async () => {
  t = await bootServer({
    roll: () => rolls.shift() ?? 3,
    timings: { introMs: INTRO_MS, introFreeMs: INTRO_MS / 2, dropMs: DROP_MS, lobbyGraceMs: 300, matchWaitMs: 60_000 },
  });
});
afterAll(() => t.stop());
afterEach(() => {
  rolls.length = 0;
});

type User = Awaited<ReturnType<typeof registerUser>>;

const balance = async (u: User) => (await u.call<{ wallet: { balance: number } }>('GET', '/wallet')).body.wallet.balance;
const ledgerRows = async (matchId: string, type: string) =>
  (await t.server.db.query<{ user_id: string; amount: number }>('select user_id, amount from wallet_transactions where match_id = $1 and type = $2', [matchId, type])).rows;
const matchRow = async (matchId: string) => (await t.server.db.query<Record<string, unknown>>('select * from matches where id = $1', [matchId])).rows[0];

async function player(name: string): Promise<{ u: User; c: C }> {
  const u = await registerUser(t, name);
  return { u, c: await socketFor(t, u.cookie) };
}

const introOf = (c: C) => next<RoomView>(c, 'room:state', (r) => r.intro !== null, 3_000);
const introEnds = (c: C) => next<RoomView>(c, 'room:state', (r) => r.status === 'playing' && r.intro === null, 3_000);

async function quick(c: C, modeId: string): Promise<RoomView> {
  const r = await emit<{ room: RoomView }>(c, 'room:quick', { modeId });
  if (!r.ok) throw new Error(r.error);
  return r.room;
}

/** Two players queue for the same mode, which only they use in this test. */
async function matched(modeId = 'quick') {
  const a = await player('Emon');
  const b = await player('Imam');
  const first = await quick(a.c, modeId);
  const introA = introOf(a.c);
  const introB = introOf(b.c);
  await quick(b.c, modeId);
  const [ra, rb] = await Promise.all([introA, introB]);
  return { a, b, code: first.code, matchId: first.matchId, ra, rb };
}

describe('Match Found', () => {
  it('is the same for both players, with their real identities and the official numbers', async () => {
    const { a, b, ra, rb, matchId } = await matched();
    const ia = ra.intro as MatchIntro;
    const ib = rb.intro as MatchIntro;

    const { msLeft: _a, ...sameA } = ia;
    const { msLeft: _b, ...sameB } = ib;
    expect(sameA).toEqual(sameB);
    expect(ia.matchId).toBe(matchId);
    expect(ia.players.map((p) => p.name)).toEqual([a.u.username, b.u.username]);
    expect(ia.players.map((p) => p.publicId)).toEqual([a.u.publicId, b.u.publicId]);
    expect(ia.players.every((p) => p.kind === 'human')).toBe(true);
    expect(new Set(ia.players.map((p) => p.color)).size).toBe(2);
    expect(ia.players.map((p) => p.contribution)).toEqual([FEE, FEE]);
    expect(ia.entryFee).toBe(FEE);
    expect(ia.pool).toBe(2 * FEE);
    expect(ia.winnerReward).toBe(REWARD);
    expect(ia.durationMs).toBe(INTRO_MS);
    expect(ia.msLeft).toBeGreaterThan(0);
    expect(Math.abs(ia.msLeft - ib.msLeft)).toBeLessThan(100);
    // Each player finds themselves in it.
    expect(ia.players[ra.you].name).toBe(a.u.username);
    expect(ib.players[rb.you].name).toBe(b.u.username);
  });

  it('has charged each fee exactly once and recorded the pool before any coin flies', async () => {
    const { a, b, ra, matchId } = await matched();
    const entries = await ledgerRows(matchId, 'match_entry');
    expect(entries.map((e) => [e.user_id, Number(e.amount)]).sort()).toEqual([[a.u.id, -FEE], [b.u.id, -FEE]].sort());
    expect(Number((await matchRow(matchId)).prize_pool)).toBe(ra.intro!.pool);
    expect((await matchRow(matchId)).status).toBe('in_progress');
    const before = await balance(a.u);
    await tick(INTRO_MS + 100);
    expect(await balance(a.u)).toBe(before); // playing it charges nothing
    expect(await ledgerRows(matchId, 'match_entry')).toHaveLength(2);
    void b;
  });

  it('holds the first turn until it ends, then starts the clock for both at once', async () => {
    const { a, b } = await matched();
    const early = await emit(a.c, 'game:roll');
    expect(early.ok).toBe(false);
    const updates = [nextUpdate(a.c, (u) => u.deadline !== null), nextUpdate(b.c, (u) => u.deadline !== null)];
    const ends = [introEnds(a.c), introEnds(b.c)];
    const [ua, ub] = (await Promise.all(updates)) as [GameUpdate, GameUpdate];
    await Promise.all(ends);
    expect(ua.deadline).toBe(ub.deadline);
    expect(ua.state.seq).toBe(ub.state.seq);
    const roll = await emit(a.c, 'game:roll');
    expect(roll.ok).toBe(true);
  });

  it('is sent once: after it ends nothing brings it back', async () => {
    const { a } = await matched();
    await introEnds(a.c);
    let seen = 0;
    a.c.on('room:state', (r) => {
      if (r.intro) seen++;
    });
    const hi = await emit<{ room: RoomView }>(a.c, 'session:hello', { results: true });
    expect(hi.ok && hi.room.intro).toBeNull();
    await tick(100);
    expect(seen).toBe(0);
    a.c.removeAllListeners('room:state');
  });

  it('shows a free private room with a bot for what it is', async () => {
    const a = await player('Host');
    const made = await emit<{ room: RoomView }>(a.c, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    if (!made.ok) throw new Error(made.error);
    await emit(a.c, 'room:addBot');
    const intro = introOf(a.c);
    await emit(a.c, 'room:start');
    const r = await intro;
    expect(r.intro!.pool).toBe(0);
    expect(r.intro!.durationMs).toBe(INTRO_MS / 2);
    expect(r.intro!.players[0].contribution).toBe(0);
    expect(r.intro!.players[1].kind).toBe('ai');
    expect(r.intro!.players[1].name).toMatch(/\(bot\)$/);
    expect(r.intro!.players[1].contribution).toBe(0);
    expect(r.intro!.players[1].publicId).toBeNull();
  });

  it('handles four players, each with their own contribution', async () => {
    const ps = await Promise.all(['A1', 'B2', 'C3', 'D4'].map(player));
    const made = await emit<{ room: RoomView }>(ps[0].c, 'room:create', { modeId: 'pro', maxPlayers: 4 });
    if (!made.ok) throw new Error(made.error);
    for (const p of ps.slice(1)) {
      await emit(p.c, 'room:join', { code: made.room.code });
      await emit(p.c, 'room:ready', { ready: true });
    }
    const intros = ps.map((p) => introOf(p.c));
    const start = await emit(ps[0].c, 'room:start');
    expect(start.ok).toBe(true);
    const views = await Promise.all(intros);
    for (const v of views) {
      expect(v.intro!.players).toHaveLength(4);
      expect(v.intro!.pool).toBe(400);
      expect(v.intro!.players.every((p) => p.contribution === 100)).toBe(true);
      expect(v.intro!.winners).toBe(1);
    }
    expect(new Set(views.map((v) => v.intro!.matchId)).size).toBe(1);
    expect(await ledgerRows(views[0].matchId, 'match_entry')).toHaveLength(4);
  });
});

describe('before the match is confirmed', () => {
  it('searching shows only real players, and cancelling refunds in full', async () => {
    const a = await player('Solo');
    const start = await balance(a.u);
    const room = await quick(a.c, 'quick');
    expect(room.seats.map((s) => s.name)).toEqual([a.u.username]);
    expect(room.intro).toBeNull();
    expect(room.status).toBe('lobby');
    const fee = room.entryFee;
    expect(await balance(a.u)).toBe(start - fee);
    await emit(a.c, 'room:leave');
    await tick(50);
    expect(await balance(a.u)).toBe(start);
    expect(await ledgerRows(room.matchId, 'match_refund')).toHaveLength(1);
  });

  it('does not start with a player offline: they lose the seat, are refunded, and the other keeps searching', async () => {
    const a = await player('Gone');
    const startA = await balance(a.u);
    const room = await quick(a.c, 'quick');
    const fee = room.entryFee;
    a.c.close();
    await tick(30);
    const b = await player('Waiting');
    const startB = await balance(b.u);
    // The room has nobody connected, so B opens a new one rather than being paired with someone offline.
    const roomB = await quick(b.c, 'quick');
    expect(roomB.code).not.toBe(room.code);
    expect(roomB.seats).toHaveLength(1);
    expect(roomB.intro).toBeNull();
    await tick(400);
    expect(await balance(a.u)).toBe(startA);
    expect((await matchRow(room.matchId)).status).toBe('cancelled');
    expect(await balance(b.u)).toBe(startB - fee);
    await emit(b.c, 'room:leave');
  });

  it('holds a full quick room for a player who dropped, and starts when they are back', async () => {
    const a = await player('Blinks');
    const b = await player('Stays');
    const room = await quick(a.c, 'quick');
    a.c.close();
    await tick(30);
    const joined = await emit<{ room: RoomView }>(b.c, 'room:join', { code: room.code });
    if (!joined.ok) throw new Error(joined.error);
    await tick(50);
    expect((await matchRow(room.matchId)).status).toBe('waiting');
    expect(await ledgerRows(room.matchId, 'match_entry')).toHaveLength(2);
    const started = introOf(b.c);
    const back = await socketFor(t, a.u.cookie);
    await emit(back, 'session:hello', { results: true });
    const r = await started;
    expect(r.intro!.players).toHaveLength(2);
    expect((await matchRow(room.matchId)).status).toBe('in_progress');
  });

  it('gives up on a player who stays away: refunded, and the other carries on searching', async () => {
    const a = await player('Vanishes');
    const b = await player('Patient');
    const startA = await balance(a.u);
    const room = await quick(a.c, 'quick');
    a.c.close();
    await tick(30);
    await emit(b.c, 'room:join', { code: room.code });
    const startB = await balance(b.u);
    const alone = next<RoomView>(b.c, 'room:state', (r) => r.seats.length === 1 && r.seats[0].name === b.u.username, 3_000);
    const v = await alone;
    expect(v.status).toBe('lobby');
    expect(v.intro).toBeNull();
    await tick(50);
    expect(await balance(a.u)).toBe(startA);
    expect(await balance(b.u)).toBe(startB); // still paid for the seat they kept
    expect((await matchRow(room.matchId)).status).toBe('waiting');
    await emit(b.c, 'room:leave');
  });

  it('closes and refunds everyone when the ledger does not show a fee was paid', async () => {
    const a = await player('Tampered');
    const b = await player('Innocent');
    const startA = await balance(a.u);
    const startB = await balance(b.u);
    const room = await quick(a.c, 'quick');
    const closed = next<string>(b.c, 'room:closed', () => true, 3_000);
    await t.server.db.query(`delete from wallet_transactions where match_id = $1 and user_id = $2 and type = 'match_entry'`, [room.matchId, a.u.id]);
    await quick(b.c, 'quick');
    expect(await closed).toMatch(/refunded/);
    await tick(150);
    const m = await matchRow(room.matchId);
    expect(m.status).toBe('cancelled');
    expect(m.prize_pool).toBeNull();
    expect(await balance(a.u)).toBe(startA);
    expect(await balance(b.u)).toBe(startB);
  });

  it('never seats anyone in two matches at once, and a repeat search keeps the same room and fee', async () => {
    const a = await player('Twice');
    const start = await balance(a.u);
    const r1 = await quick(a.c, 'quick');
    const r2 = await quick(a.c, 'quick');
    expect(r2.code).toBe(r1.code);
    expect(await balance(a.u)).toBe(start - r1.entryFee);
    expect(await ledgerRows(r1.matchId, 'match_entry')).toHaveLength(1);
    await emit(a.c, 'room:leave');
  });
});

describe('during the introduction', () => {
  it('another match cannot be started and leaving is a forfeit, not a refund', async () => {
    const { a, b, matchId } = await matched();
    const other = await emit(a.c, 'room:quick', { modeId: 'master' });
    expect(other.ok).toBe(false);
    const made = await emit(a.c, 'room:create', { modeId: 'quick', maxPlayers: 2 });
    expect(made.ok).toBe(false);

    const before = await balance(a.u);
    const settled = next<MatchSettled>(b.c, 'match:settled');
    const left = await emit<{ result: MatchResult | null }>(a.c, 'room:leave', { results: true });
    expect(left.ok).toBe(true);
    const s = await settled;
    expect(s.pool).toBe(2 * FEE);
    expect(s.payout).toBe(REWARD);
    await tick(100);
    expect(await balance(a.u)).toBe(before);
    expect(await ledgerRows(matchId, 'match_reward')).toHaveLength(1);
    expect(await ledgerRows(matchId, 'match_refund')).toHaveLength(0);
    // The winner is released at once, not held for the rest of the introduction.
    const hi = await emit<{ room: RoomView }>(b.c, 'session:hello', { results: true });
    expect(hi.ok && hi.room.intro).toBeNull();
    expect(hi.ok && hi.room.status).toBe('over');
  });

  it('survives a reconnect: the same match, later in the animation, no new charge', async () => {
    const { a, b, code, matchId } = await matched();
    const before = await balance(a.u);
    const offline = next<RoomView>(b.c, 'room:state', (r) => r.seats[0].status === 'disconnected');
    a.c.close();
    await offline;
    await tick(60);
    const back = await socketFor(t, a.u.cookie);
    const hi = await emit<{ room: RoomView }>(back, 'session:hello', { results: true });
    if (!hi.ok) throw new Error(hi.error);
    expect(hi.room.code).toBe(code);
    expect(hi.room.matchId).toBe(matchId);
    expect(hi.room.intro?.matchId).toBe(matchId);
    expect(hi.room.intro!.msLeft).toBeLessThan(INTRO_MS - 50);
    expect(hi.room.seats[0].status).toBe('connected');
    expect(await balance(a.u)).toBe(before);
    expect(await ledgerRows(matchId, 'match_entry')).toHaveLength(2);
    // And it still ends on time for the player who never left.
    await introEnds(b.c);
  });

  it('a player who never comes back is out after the usual grace period, and the opponent is paid once', async () => {
    const { a, b, matchId } = await matched();
    const beforeB = await balance(b.u);
    const settled = next<MatchSettled>(b.c, 'match:settled', () => true, DROP_MS + 3_000);
    a.c.close();
    const s = await settled;
    expect(s.payout).toBe(REWARD);
    await tick(100);
    expect(await balance(b.u)).toBe(beforeB + REWARD);
    expect(await ledgerRows(matchId, 'match_reward')).toHaveLength(1);
    expect(await ledgerRows(matchId, 'match_refund')).toHaveLength(0);
  });

  it('a player offline when the turn clock would start does not lose their turn clock to the introduction', async () => {
    const { a, b } = await matched();
    a.c.close(); // red, who moves first
    const during = await nextUpdate(b.c, () => true);
    expect(during.deadline).toBeNull();
    // When the introduction ends the match waits for them: the deadline is their grace deadline.
    const after = await nextUpdate(b.c, (u) => u.deadline !== null);
    expect(after.deadline! - Date.now()).toBeLessThanOrEqual(DROP_MS);
  });
});

describe('settling the pool', () => {
  it('credits the reward exactly once, and reports the pool', async () => {
    const { a, b, matchId, rb } = await matched();
    await introEnds(a.c);
    const beforeB = await balance(b.u);
    const settled = next<MatchSettled>(b.c, 'match:settled');
    await emit(a.c, 'room:leave', { results: true });
    const s = await settled;
    expect(s.pool).toBe(rb.intro!.pool);
    expect(s.payout).toBe(rb.intro!.winnerReward);
    await tick(100);
    expect(await balance(b.u)).toBe(beforeB + REWARD);
    expect(await ledgerRows(matchId, 'match_reward')).toHaveLength(1);
    const house = (await t.server.db.query<{ total_pot: number; payout: number; house_amount: number }>(
      'select total_pot, payout, house_amount from match_settlements where match_id = $1', [matchId],
    )).rows;
    expect(house).toHaveLength(1);
    expect(Number(house[0].total_pot)).toBe(2 * FEE);
    expect(Number(house[0].payout)).toBe(REWARD);
    expect(Number(house[0].house_amount)).toBe(0);
  });

  it('a bot filling a paid seat pays nothing into the pool and is marked as a bot', async () => {
    const short = await bootServer({ timings: { introMs: 200, introFreeMs: 100, matchWaitMs: 50 } });
    try {
      const u = await registerUser(short, 'Lonely');
      const c = await socketFor(short, u.cookie);
      const intro = introOf(c);
      const r = await emit<{ room: RoomView }>(c, 'room:quick', { modeId: 'quick' });
      if (!r.ok) throw new Error(r.error);
      const v = await intro;
      expect(v.intro!.players.map((p) => p.kind)).toEqual(['human', 'ai']);
      expect(v.intro!.players[1].name).toMatch(/\(bot\)$/);
      expect(v.intro!.players[1].contribution).toBe(0);
      expect(v.intro!.pool).toBe(r.room.entryFee);
    } finally {
      await short.stop();
    }
  });
});
