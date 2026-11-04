import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { RECONNECT_GRACE_MS, type GameUpdate, type MatchResult, type RoomView } from '../shared/protocol';
import { DEFAULT_TIMINGS } from '../server/rooms';
import { MatchLedger } from '../server/ledger';
import { Presence } from '../server/presence';
import { bootServer, emit, next, nextUpdate, registerUser, socketFor, tick, type C, type TestServer } from './helpers';

/** Connection loss, reconnection, leaving and forfeits, against the real server over real sockets. */

const DROP_MS = 500;
const FEE = 100; // the Pro Match entry fee
const PAYOUT = 200; // 2 x the fee

let t: TestServer;
const rolls: number[] = [];

beforeAll(async () => {
  t = await bootServer({ roll: () => rolls.shift() ?? 3, timings: { dropMs: DROP_MS } });
});
afterAll(() => t.stop());
afterEach(() => {
  vi.restoreAllMocks();
  rolls.length = 0;
});

type User = Awaited<ReturnType<typeof registerUser>>;
type Hello = { room: RoomView | null; result: MatchResult | null };

/** A started match of `n` humans; players[0] is red and moves first. */
async function match(n: number, server = t, modeId = 'pro', teams = false) {
  const users: User[] = [];
  const sockets: C[] = [];
  for (let i = 0; i < n; i++) {
    users.push(await registerUser(server, `Player ${i}`));
    sockets.push(await socketFor(server, users[i].cookie));
  }
  const created = await emit<{ room: RoomView }>(sockets[0], 'room:create', { modeId, maxPlayers: n, teams });
  if (!created.ok) throw new Error(created.error);
  for (let i = 1; i < n; i++) {
    await emit(sockets[i], 'room:join', { code: created.room.code });
    await emit(sockets[i], 'room:ready', { ready: true });
  }
  const go = nextUpdate(sockets[0]);
  const start = await emit(sockets[0], 'room:start');
  if (!start.ok) throw new Error(start.error);
  const first = await go;
  return { users, sockets, code: created.room.code, matchId: created.room.matchId, first };
}

const balance = async (u: User) => (await u.call<{ wallet: { balance: number } }>('GET', '/wallet')).body.wallet.balance;
const out = (c: C, color: string) => nextUpdate(c, (u) => u.state.players.find((p) => p.color === color)!.left === true);
const offline = (c: C, seat: number) => next<RoomView>(c, 'room:state', (r) => r.seats[seat].status === 'disconnected');
const hello = async (c: C) => {
  const r = await emit<Hello>(c, 'session:hello', { results: true });
  if (!r.ok) throw new Error(r.error);
  return r;
};
const leave = async (c: C) => {
  const r = await emit<{ result: MatchResult | null }>(c, 'room:leave', { results: true });
  if (!r.ok) throw new Error(r.error);
  return r.result;
};
const rewards = async (matchId: string) =>
  (await t.server.db.query<{ user_id: string; amount: number }>(`select user_id, amount from wallet_transactions where match_id = $1 and type = 'match_reward'`, [matchId])).rows;
const row = async (matchId: string, u: User, server = t) =>
  (await server.server.db.query<Record<string, unknown>>('select * from match_players where match_id = $1 and user_id = $2', [matchId, u.id])).rows[0];
const matchRow = async (matchId: string, server = t) =>
  (await server.server.db.query<Record<string, unknown>>('select * from matches where id = $1', [matchId])).rows[0];
const settled = () => tick(150);

describe('the reconnection grace period', () => {
  it('is exactly 20 seconds, and the room says so', async () => {
    expect(RECONNECT_GRACE_MS).toBe(20_000);
    expect(DEFAULT_TIMINGS.dropMs).toBe(20_000);
    const { sockets, matchId } = await match(2);
    const hi = await hello(sockets[1]);
    expect(hi.room?.dropAfterMs).toBe(DROP_MS); // this test server's shortened value, from the server
    expect(hi.room?.matchId).toBe(matchId);
  });

  it('restores everything on a reconnect in time, without a second seat or a second fee', async () => {
    const { users, sockets, code, matchId } = await match(2);
    const [a, b] = sockets;
    rolls.push(6);
    await emit(a, 'game:roll');
    await emit(a, 'game:move', { token: 1 });
    rolls.push(6);
    const rolled = nextUpdate(b, (u) => u.state.phase === 'move');
    await emit(a, 'game:roll');
    const before = await rolled;

    const seen = offline(b, 0);
    a.close();
    const view = await seen;
    expect(view.seats[0]).toMatchObject({ connected: false, left: false, status: 'disconnected' });
    expect(view.seats[0].reconnectMsLeft).toBeGreaterThan(0);
    expect(view.seats[0].reconnectMsLeft).toBeLessThanOrEqual(DROP_MS);

    await tick(120);
    const a2 = await socketFor(t, users[0].cookie);
    const back = next<RoomView>(b, 'room:state', (r) => r.seats[0].status === 'connected');
    const snapshot = nextUpdate(a2);
    const hi = await hello(a2);
    expect(hi.room?.code).toBe(code);
    expect(hi.result).toBeNull();
    expect(hi.room?.you).toBe(0);
    expect(hi.room?.seats).toHaveLength(2);
    expect(hi.room?.seats[0]).toMatchObject({ color: 'red', connected: true, reconnectMsLeft: null, status: 'connected' });
    expect((await back).seats[0].status).toBe('connected');
    const s = (await snapshot).state;
    expect(s.seq).toBe(before.state.seq);
    expect(s.players[0].tokens).toEqual(before.state.players[0].tokens);
    expect(s.dice).toBe(6);
    expect(s.phase).toBe('move');
    expect(s.current).toBe(0);

    // Still their move, and only one entry fee was ever charged.
    expect((await emit(a2, 'game:move', { token: 1 })).ok).toBe(true);
    expect(await balance(users[0])).toBe(1000 - FEE);
    await settled();
    const r = await row(matchId, users[0]);
    expect(r.connection_status).toBe('reconnected');
    expect(r.disconnected_at).toBeTruthy();
    expect(r.reconnected_at).toBeTruthy();
    expect(r.reconnect_deadline).toBeNull();
    expect(r.status).toBe('playing');
  });

  it('keeps the player in at the last moment before the deadline', async () => {
    const { users, sockets } = await match(2);
    sockets[0].close();
    await offline(sockets[1], 0);
    await tick(DROP_MS - 150);
    const a2 = await socketFor(t, users[0].cookie);
    const hi = await hello(a2);
    expect(hi.room?.seats[0].left).toBe(false);
    await tick(DROP_MS + 100); // the old countdown is really gone
    expect((await emit(a2, 'game:roll')).ok).toBe(true);
  });

  it('makes one decision at the deadline: a reconnect arriving after it cannot undo the forfeit, even before the timer has run', async () => {
    const { users, sockets, matchId } = await match(2);
    const over = out(sockets[1], 'red');
    sockets[0].close();
    await offline(sockets[1], 0);
    const a2 = await socketFor(t, users[0].cookie);
    // The clock passes the deadline while this reply is on its way, with the timer not yet run.
    const real = Date.now;
    vi.spyOn(Date, 'now').mockImplementation(() => real() + DROP_MS + 1);
    const hi = await hello(a2);
    vi.restoreAllMocks();
    expect(hi.room).toBeNull();
    expect(hi.result).toMatchObject({ outcome: 'lost', reason: 'timeout', matchId });
    expect((await over).state.phase).toBe('over');
    await settled();
    expect((await rewards(matchId)).map((x) => x.user_id)).toEqual([users[1].id]);
  });

  it('does not start again for a connection that comes back and drops at once', async () => {
    const { users, sockets } = await match(2);
    const [a, b] = sockets;
    a.close();
    await offline(b, 0);
    await tick(DROP_MS - 150);
    const a2 = await socketFor(t, users[0].cookie);
    await hello(a2);
    const seen = offline(b, 0);
    a2.close();
    const view = await seen;
    // Carries on with what was left, far less than a full new countdown.
    expect(view.seats[0].reconnectMsLeft).toBeLessThan(DROP_MS / 2);
  });

  it('gives a new countdown after the connection has held', async () => {
    const st = await bootServer({ timings: { dropMs: DROP_MS, stableMs: 100 } });
    try {
      const { users, sockets } = await match(2, st, 'friendly');
      const [a, b] = sockets;
      a.close();
      await offline(b, 0);
      await tick(DROP_MS - 150);
      const a2 = await socketFor(st, users[0].cookie);
      await hello(a2);
      await tick(150);
      const seen = offline(b, 0);
      a2.close();
      expect((await seen).seats[0].reconnectMsLeft).toBeGreaterThan(DROP_MS - 100);
    } finally {
      await st.stop();
    }
  });

  it('is not touched by a new socket that has not resumed the match', async () => {
    const { users, sockets } = await match(2);
    const [a, b] = sockets;
    const seen = offline(b, 0);
    a.close();
    const first = (await seen).seats[0].reconnectMsLeft!;
    await tick(100);
    const a2 = await socketFor(t, users[0].cookie);
    expect(await emit(a2, 'game:roll')).toMatchObject({ ok: false }); // no game action on the bare socket
    const again = await hello(b);
    expect(again.room?.seats[0].reconnectMsLeft).toBeLessThan(first);
    expect(again.room?.seats[0].status).toBe('disconnected');
  });
});

describe('turns while a player is offline', () => {
  it('restores the turn clock instead of handing out a fresh one', async () => {
    const st = await bootServer({ roll: () => 3, timings: { dropMs: 2000, turnMs: 3000, resumeFloorMs: 0, autoMoveMs: 60_000 } });
    try {
      const { users, sockets, first } = await match(2, st, 'friendly');
      const [a, b] = sockets;
      const startedLeft = first.deadline! - first.serverTime;
      await tick(1000);
      const waiting = next<GameUpdate>(b, 'game:update', (u) => u.deadline !== null && u.deadline !== first.deadline);
      a.close();
      await waiting;
      await tick(500); // offline time does not run the turn clock
      const a2 = await socketFor(st, users[0].cookie);
      const snap = nextUpdate(a2);
      await hello(a2);
      const u = await snap;
      const left = u.deadline! - u.serverTime;
      expect(left).toBeLessThan(startedLeft - 700);
      expect(left).toBeGreaterThan(startedLeft - 1500);
    } finally {
      await st.stop();
    }
  });

  it('a repeated hello does not refresh the turn clock', async () => {
    const st = await bootServer({ timings: { turnMs: 3000 } });
    try {
      const { sockets, first } = await match(2, st, 'friendly');
      await tick(300);
      const snap = nextUpdate(sockets[0]);
      await hello(sockets[0]);
      expect((await snap).deadline).toBe(first.deadline);
    } finally {
      await st.stop();
    }
  });

  it('plays nothing for an offline player, not even their only move', async () => {
    const st = await bootServer({ roll: () => 6, timings: { dropMs: 800, autoMoveMs: 150 } });
    try {
      const { users, sockets } = await match(2, st, 'friendly');
      const [a, b] = sockets;
      const rolled = nextUpdate(b, (u) => u.state.phase === 'move');
      await emit(a, 'game:roll');
      const { state } = await rolled;
      a.close();
      await tick(350);
      const a2 = await socketFor(st, users[0].cookie);
      const snap = nextUpdate(a2);
      await hello(a2);
      const now = (await snap).state;
      expect(now.seq).toBe(state.seq);
      expect(now.phase).toBe('move');
      expect(now.players[0].tokens).toEqual([-1, -1, -1, -1]);
    } finally {
      await st.stop();
    }
  });

  it('refuses every game action from a player who is out', async () => {
    const { sockets } = await match(3);
    await leave(sockets[0]);
    expect((await emit(sockets[0], 'game:roll')).ok).toBe(false);
    expect((await emit(sockets[0], 'game:move', { token: 0 })).ok).toBe(false);
    expect((await emit(sockets[0], 'chat:send', { text: 'still here' })).ok).toBe(false);
  });

  it('moves the turn on when the current player is out, without giving anyone an extra turn', async () => {
    const { sockets } = await match(4);
    const [a, b, c, d] = sockets;
    const reachB = nextUpdate(c, (u) => u.state.current === 1);
    await emit(a, 'game:roll'); // a 3 with nothing to move: on to b
    const before = await reachB;
    const toC = out(d, 'green');
    await leave(b); // it's b's turn
    const { state } = await toC;
    expect(state.current).toBe(2);
    expect(state.phase).toBe('roll');
    expect(state.turnNo).toBe(before.state.turnNo + 1);
    // Not current: the turn stays where it is when someone else goes.
    const stay = out(c, 'red');
    await leave(a);
    expect((await stay).state.current).toBe(2);
    // The order carries on past those who left.
    const toD = nextUpdate(d, (u) => u.state.current === 3);
    expect((await emit(c, 'game:roll')).ok).toBe(true);
    await toD;
    const backToC = nextUpdate(c, (u) => u.state.current === 2);
    expect((await emit(d, 'game:roll')).ok).toBe(true);
    await backToC;
  });

  it('does not get stuck waiting for an offline player whose turn comes up', async () => {
    const { sockets } = await match(3);
    const [a, b, c] = sockets;
    b.close();
    await offline(a, 1);
    const eliminated = out(a, 'green');
    await emit(a, 'game:roll'); // to b, who is offline: the clock now shown is their deadline
    const { state } = await eliminated;
    expect(state.current).toBe(2);
    expect((await emit(c, 'game:roll')).ok).toBe(true);
  });
});

describe('forfeit by timeout', () => {
  it('two players: the one still here wins at once and is paid, once', async () => {
    const { users, sockets, matchId } = await match(2);
    const [a, b] = sockets;
    const over = nextUpdate(b, (u) => u.state.phase === 'over');
    const paid = next<{ payout: number; winnerName: string }>(b, 'match:settled');
    a.close();
    const u = await over;
    expect(u.events.map((e) => e.type)).toEqual(['playerLeft', 'gameOver']);
    expect(u.state.ranking).toEqual([1, 0]);
    expect((await paid)).toMatchObject({ payout: PAYOUT, winnerName: users[1].username });
    await settled();
    expect(await balance(users[1])).toBe(1000 - FEE + PAYOUT);
    expect(await balance(users[0])).toBe(1000 - FEE);
    expect(await rewards(matchId)).toHaveLength(1);

    expect(await row(matchId, users[0])).toMatchObject({ status: 'forfeited', connection_status: 'forfeited', forfeit_reason: 'timeout', won: false, payout: 0, rank: 2 });
    expect((await row(matchId, users[0])).forfeited_at).toBeTruthy();
    expect(await row(matchId, users[1])).toMatchObject({ status: 'finished', connection_status: 'winner', won: true, payout: PAYOUT, rank: 1 });
    const m = await matchRow(matchId);
    expect(m).toMatchObject({ status: 'completed', winner_user_id: users[1].id, end_reason: 'opponent_forfeit' });
    expect(m.ended_at).toBeTruthy();

    // Match history tells the same story.
    const hist = await users[0].call<{ items: { id: string; result: string; forfeitReason: string; net: number }[] }>('GET', '/matches/history');
    expect(hist.body.items.find((x) => x.id === matchId)).toMatchObject({ result: 'forfeited', forfeitReason: 'timeout', net: -FEE });
    const won = await users[1].call<{ items: { id: string; result: string; net: number }[] }>('GET', '/matches/history');
    expect(won.body.items.find((x) => x.id === matchId)).toMatchObject({ result: 'won', net: PAYOUT - FEE });
  });

  it('shows the player who comes back afterwards YOU LOST with the exact reason, and no way back in', async () => {
    const { users, sockets, matchId, code } = await match(2);
    const over = nextUpdate(sockets[1], (u) => u.state.phase === 'over');
    sockets[0].close();
    await over;
    await settled();
    const a2 = await socketFor(t, users[0].cookie);
    const hi = await hello(a2);
    expect(hi.room).toBeNull();
    expect(hi.result).toEqual({
      matchId,
      outcome: 'lost',
      reason: 'timeout',
      message: 'Your connection was lost for more than 20 seconds. Your opponent won the match.',
      opponents: [users[1].username],
      winnerName: users[1].username,
      status: 'completed',
      coinsPaid: FEE,
      coinsReceived: 0,
      net: -FEE,
      playAgain: { modeId: 'pro', name: 'Pro Match', entryFee: FEE },
    });
    // No rejoining, by any route.
    expect(await emit(a2, 'room:join', { code })).toMatchObject({ ok: false });
    expect((await emit(a2, 'game:roll')).ok).toBe(false);
    // The result is kept until the app confirms it was shown, then it's done.
    const again = await socketFor(t, users[0].cookie);
    expect((await hello(again)).result?.matchId).toBe(matchId);
    expect((await emit(again, 'match:resultSeen', { matchId })).ok).toBe(true);
    expect((await hello(again)).result).toBeNull();
    expect((await emit(again, 'match:resultSeen', { matchId: 'nope' })).ok).toBe(false);
  });

  it('three players: only that player is out; the other two carry on with their tokens', async () => {
    const { users, sockets, matchId } = await match(3);
    const [a, b, c] = sockets;
    rolls.push(6, 3);
    await emit(a, 'game:roll');
    await emit(a, 'game:move', { token: 2 });
    const toB = nextUpdate(c, (u) => u.state.current === 1);
    await emit(a, 'game:roll');
    await emit(a, 'game:move', { token: 2 });
    await toB;
    const eliminated = next<RoomView>(a, 'room:state', (r) => r.seats[2].status === 'eliminated');
    const gone = out(a, 'yellow');
    c.close();
    const { state, events } = await gone;
    expect((await eliminated).seats[2].left).toBe(true);
    expect(events.map((e) => e.type)).toEqual(['playerLeft']);
    expect(state.phase).toBe('roll');
    expect(state.current).toBe(1); // still b's turn
    expect(state.players[0].tokens).toEqual([-1, -1, 3, -1]);
    expect((await emit(b, 'game:roll')).ok).toBe(true);
    await settled();
    expect(await matchRow(matchId)).toMatchObject({ status: 'in_progress', winner_user_id: null });
    expect(await rewards(matchId)).toHaveLength(0);
    expect(await row(matchId, users[2])).toMatchObject({ status: 'forfeited', connection_status: 'eliminated', forfeit_reason: 'timeout' });

    const c2 = await socketFor(t, users[2].cookie);
    const hi = await hello(c2);
    expect(hi.room).toBeNull();
    expect(hi.result).toMatchObject({
      message: 'You were eliminated because you did not reconnect within the 20-second grace period.',
      status: 'in_progress',
      winnerName: null,
    });
    expect(hi.result?.opponents.sort()).toEqual([users[0].username, users[1].username].sort());
  });

  it('four players: the other three carry on and nobody is declared the winner', async () => {
    const { users, sockets, matchId } = await match(4);
    const [a, , , d] = sockets;
    const gone = out(a, 'blue');
    d.close();
    const { state } = await gone;
    expect(state.phase).toBe('roll');
    expect(state.ranking).toEqual([]);
    expect(state.players.filter((p) => !p.left)).toHaveLength(3);
    await settled();
    expect((await matchRow(matchId)).winner_user_id).toBeNull();
    expect(await balance(users[0])).toBe(1000 - FEE);
  });

  it('several eliminations: the last one standing wins, once', async () => {
    const { users, sockets, matchId } = await match(4);
    const [a, b, c, d] = sockets;
    const threeLeft = out(a, 'blue');
    d.close();
    await threeLeft;
    const twoLeft = out(a, 'yellow');
    c.close();
    expect((await twoLeft).state.phase).toBe('roll');
    const over = nextUpdate(a, (u) => u.state.phase === 'over');
    b.close();
    const end = (await over).state;
    expect(end.ranking[0]).toBe(0);
    await settled();
    expect(await rewards(matchId)).toEqual([{ user_id: users[0].id, amount: PAYOUT }]);
    expect(await balance(users[0])).toBe(1000 - FEE + PAYOUT);
    for (const u of users.slice(1)) expect(await balance(u)).toBe(1000 - FEE);
  });

  it('two at once: the one who comes back in time wins, and neither wins without returning', async () => {
    const { users, sockets, matchId } = await match(2);
    const [a, b] = sockets;
    a.close();
    await tick(200);
    b.close();
    await tick(DROP_MS - 200 + 80); // a is out now; b has about 120 ms left
    expect(await rewards(matchId)).toHaveLength(0);
    expect((await matchRow(matchId)).status).toBe('in_progress');
    const b2 = await socketFor(t, users[1].cookie);
    const over = nextUpdate(b2, (u) => u.state.phase === 'over');
    const hi = await hello(b2);
    expect(hi.room?.status).toBe('over');
    expect((await over).state.ranking).toEqual([1, 0]);
    await settled();
    expect(await rewards(matchId)).toEqual([{ user_id: users[1].id, amount: PAYOUT }]);
  });

  it('two at once, neither returns: no winner, no payout, and the match is closed', async () => {
    const { users, sockets, matchId } = await match(2);
    sockets[0].close();
    sockets[1].close();
    await tick(DROP_MS + 200);
    expect(await rewards(matchId)).toHaveLength(0);
    expect(await matchRow(matchId)).toMatchObject({ status: 'abandoned', winner_user_id: null, end_reason: 'no eligible players' });
    for (const u of users) {
      expect(await balance(u)).toBe(1000 - FEE); // the existing policy: no refunds
      const s = await socketFor(t, u.cookie);
      const hi = await hello(s);
      expect(hi.room).toBeNull();
      expect(hi.result).toMatchObject({ outcome: 'lost', status: 'abandoned', winnerName: null });
      expect(hi.result?.message).toMatch(/Nobody won/);
    }
  });

  it('alone against bots: the match ends, no coins are paid and nothing keeps running', async () => {
    const st = await bootServer({ timings: { dropMs: DROP_MS, matchWaitMs: 50 } });
    try {
      const u = await registerUser(st, 'Solo');
      const s = await socketFor(st, u.cookie);
      const started = nextUpdate(s);
      const q = await emit<{ room: RoomView }>(s, 'room:quick', { modeId: 'classic' });
      await started;
      const matchId = q.ok ? q.room.matchId : '';
      s.close();
      await tick(DROP_MS + 200);
      expect(st.server.rooms.roomCount).toBe(0);
      const m = await matchRow(matchId, st);
      expect(m).toMatchObject({ status: 'completed', winner_user_id: null });
      expect(await row(matchId, u, st)).toMatchObject({ status: 'forfeited', payout: 0 });
      expect((await balance(u))).toBe(950);
    } finally {
      await st.stop();
    }
  });

  it('a match that ends during a countdown cancels it: the player comes back to the result, not to a forfeit', async () => {
    const { users, sockets, matchId } = await match(4, t, 'friendly', true);
    const [a, b, c] = sockets;
    // a (team red + yellow) drops; then b, on the other team, walks out: a's team wins.
    a.close();
    await offline(c, 0);
    const over = nextUpdate(c, (u) => u.state.phase === 'over');
    await leave(b);
    const { state } = await over;
    expect(state.ranking.slice(0, 2).sort()).toEqual([0, 2]);
    await tick(DROP_MS + 100);
    await settled();
    expect(await row(matchId, users[0])).toMatchObject({ status: 'finished', won: true });
    const a2 = await socketFor(t, users[0].cookie);
    const hi = await hello(a2);
    expect(hi.room?.status).toBe('over');
    expect(hi.result).toBeNull();
  });
});

describe('leaving a match', () => {
  it('forfeits at once with no countdown: the opponent wins and the leaver gets the result', async () => {
    const { users, sockets, matchId } = await match(2);
    const [a, b] = sockets;
    const over = nextUpdate(b, (u) => u.state.phase === 'over');
    const updatesToLeaver: GameUpdate[] = [];
    a.on('game:update', (u) => updatesToLeaver.push(u));
    const started = Date.now();
    const result = await leave(a);
    expect(Date.now() - started).toBeLessThan(DROP_MS);
    expect(result).toMatchObject({
      matchId,
      outcome: 'lost',
      reason: 'left',
      message: 'You voluntarily left the match.',
      status: 'completed',
      winnerName: users[1].username,
      net: -FEE,
    });
    expect((await over).state.players[0].left).toBe(true);
    expect(updatesToLeaver).toHaveLength(0); // never told they won anything
    await settled();
    expect(await balance(users[1])).toBe(1000 - FEE + PAYOUT);
    expect(await row(matchId, users[0])).toMatchObject({ status: 'forfeited', forfeit_reason: 'left', connection_status: 'forfeited', won: false });
  });

  it('three players: only the leaver is out', async () => {
    const { users, sockets, matchId } = await match(3);
    const gone = out(sockets[1], 'red');
    const seat = next<RoomView>(sockets[1], 'room:state', (r) => r.seats[0].status === 'forfeited');
    const result = await leave(sockets[0]);
    expect(result).toMatchObject({ reason: 'left', status: 'in_progress', winnerName: null });
    expect((await gone).state.phase).toBe('roll');
    await seat;
    // Nothing more about this match reaches the leaver.
    const toLeaver: unknown[] = [];
    sockets[0].on('room:state', (r) => toLeaver.push(r));
    sockets[0].on('game:update', (u) => toLeaver.push(u));
    await emit(sockets[1], 'game:roll');
    await tick(100);
    expect(toLeaver).toEqual([]);
    await settled();
    expect((await matchRow(matchId)).status).toBe('in_progress');
    expect(await balance(users[0])).toBe(1000 - FEE);
  });

  it('pressing Leave repeatedly changes nothing', async () => {
    const { users, sockets, matchId } = await match(2);
    const [a] = sockets;
    const results = await Promise.all([leave(a), leave(a), leave(a)]);
    for (const r of results) expect(r).toMatchObject({ matchId, reason: 'left' });
    expect(await leave(a)).toMatchObject({ matchId, reason: 'left' });
    await settled();
    expect(await rewards(matchId)).toHaveLength(1);
    expect(await balance(users[0])).toBe(1000 - FEE);
    expect(await balance(users[1])).toBe(1000 - FEE + PAYOUT);
  });

  it('leaving and the connection dropping at the same moment count once', async () => {
    const { users, sockets, matchId } = await match(2);
    const [a] = sockets;
    void leave(a).catch(() => {});
    a.close();
    await tick(DROP_MS + 200);
    expect(await rewards(matchId)).toHaveLength(1);
    expect(await row(matchId, users[0])).toMatchObject({ status: 'forfeited' });
    expect(await balance(users[1])).toBe(1000 - FEE + PAYOUT);
  });

  it('gives back the seat and the fee in a lobby: no defeat, no forfeit', async () => {
    const u = await registerUser(t, 'Lobby Lou');
    const h = await registerUser(t, 'Lobby Host');
    const s = await socketFor(t, u.cookie);
    const hs = await socketFor(t, h.cookie);
    const c = await emit<{ room: RoomView }>(hs, 'room:create', { modeId: 'pro', maxPlayers: 2 });
    const room = c.ok ? c.room : null;
    await emit(s, 'room:join', { code: room!.code });
    expect(await balance(u)).toBe(1000 - FEE);
    expect(await leave(s)).toBeNull();
    await tick(100);
    expect(await balance(u)).toBe(1000);
    expect(await row(room!.matchId, u)).toMatchObject({ status: 'refunded', forfeit_reason: null, forfeited_at: null });
    expect((await hello(s)).result).toBeNull();
  });

  it('starting something else cannot walk out of a running match', async () => {
    const { sockets, code } = await match(2);
    const [a] = sockets;
    expect(await emit(a, 'room:create', { modeId: 'pro', maxPlayers: 2 })).toMatchObject({ ok: false, error: 'Finish or leave your current match first' });
    expect(await emit(a, 'room:quick', { modeId: 'quick' })).toMatchObject({ ok: false });
    const other = await registerUser(t, 'Other Host');
    const os = await socketFor(t, other.cookie);
    const c = await emit<{ room: RoomView }>(os, 'room:create', { modeId: 'pro', maxPlayers: 2 });
    expect(await emit(a, 'room:join', { code: c.ok ? c.room.code : '' })).toMatchObject({ ok: false });
    expect((await hello(a)).room?.code).toBe(code);
  });

  it('after being out, the player can queue for a new match while the old one carries on', async () => {
    const { sockets, code } = await match(3);
    await leave(sockets[0]);
    const again = await emit<{ room: RoomView }>(sockets[0], 'room:create', { modeId: 'pro', maxPlayers: 2 });
    expect(again.ok && again.room.code).not.toBe(code);
    // The old room ending later doesn't touch their new seat.
    const hi = await hello(sockets[0]);
    expect(hi.room?.code).toBe(again.ok ? again.room.code : '');
    await leave(sockets[1]);
    await settled();
    expect((await hello(sockets[0])).room?.code).toBe(again.ok ? again.room.code : '');
  });
});

describe('settlement', () => {
  it('never pays a forfeited player, even ranked first, and pays a match only once', async () => {
    const { users, sockets, matchId } = await match(3);
    await leave(sockets[0]);
    await settled();
    const ledger = new MatchLedger({ db: t.server.db, settings: t.server.settings, presence: new Presence(), requireEmailVerification: false });
    const res = await ledger.settle(matchId, [users[0].id, users[1].id, users[2].id], 'red');
    expect(res).toMatchObject({ payout: 0, winnerUserId: null });
    expect(await ledger.settle(matchId, [users[1].id, users[0].id, users[2].id], 'green')).toBeNull();
    expect(await rewards(matchId)).toEqual([]);
    expect(await balance(users[0])).toBe(1000 - FEE);
  });
});

describe('session safety', () => {
  it('only the newest connection plays; an old socket going away starts no countdown', async () => {
    const { users, sockets } = await match(2);
    const [a, b] = sockets;
    const a2 = await socketFor(t, users[0].cookie);
    const replaced = next(a, 'session:replaced');
    const hi = await hello(a2);
    expect(hi.room?.seats).toHaveLength(2);
    await replaced;
    expect(await emit(a, 'game:roll')).toMatchObject({ ok: false, error: 'This room is open in another tab' });
    a.close();
    await tick(DROP_MS + 100);
    const seen = await hello(b);
    expect(seen.room?.seats[0]).toMatchObject({ status: 'connected', left: false });
    expect((await emit(a2, 'game:roll')).ok).toBe(true);
  });

  it('nobody can act for another player, and the session decides who you are', async () => {
    const { sockets, code } = await match(2);
    const [, b] = sockets;
    const stranger = await registerUser(t, 'Stranger');
    const s = await socketFor(t, stranger.cookie);
    expect(await emit(s, 'room:join', { code })).toMatchObject({ ok: false, error: 'That match has already started' });
    expect((await emit(s, 'game:roll')).ok).toBe(false);
    expect(await emit(b, 'game:roll')).toMatchObject({ ok: false, error: 'It is not your turn' });
    // A client's claim about itself goes nowhere.
    const hi = await emit<Hello>(s, 'session:hello', { results: true, playerId: 'someone-else', seat: 0 });
    expect(hi.ok && hi.room).toBeNull();
  });

  it('rate-limits a flood of events', async () => {
    const { sockets } = await match(2);
    const replies = await Promise.all(Array.from({ length: 40 }, () => emit(sockets[1], 'game:roll')));
    expect(replies.filter((r) => !r.ok && r.error === 'Slow down').length).toBeGreaterThan(0);
  });
});

describe('older apps', () => {
  it('still get match:dropped, once', async () => {
    const { users, sockets } = await match(2);
    const over = nextUpdate(sockets[1], (u) => u.state.phase === 'over');
    sockets[0].close();
    await over;
    await settled();
    const a2 = await socketFor(t, users[0].cookie);
    const told = next<{ message: string }>(a2, 'match:dropped');
    await emit(a2, 'session:hello');
    expect((await told).message).toMatch(/opponent won/i);
    expect((await hello(a2)).result).toBeNull();
  });
});

describe('a server restart', () => {
  it('cancels the match and tells the players coming back; those who forfeited stay forfeited', async () => {
    const st = await bootServer({ timings: { dropMs: DROP_MS } });
    try {
      const { users, sockets, matchId } = await match(3, st);
      await leave(sockets[0]);
      await settled();
      // Rooms live in memory: a restart loses them, and boot recovery settles the database.
      st.server.rooms.shutdown();
      const ledger = new MatchLedger({ db: st.server.db, settings: st.server.settings, presence: new Presence(), requireEmailVerification: false });
      expect((await ledger.recover()).abandoned).toBe(1);

      const quitter = await hello(await socketFor(st, users[0].cookie));
      expect(quitter.result).toMatchObject({ outcome: 'lost', reason: 'left', status: 'abandoned', net: -FEE });
      const stayer = await hello(await socketFor(st, users[1].cookie));
      expect(stayer.room).toBeNull();
      expect(stayer.result).toMatchObject({ outcome: 'cancelled', reason: 'server_restart', matchId, net: 0, coinsReceived: FEE });
      expect(await balance(users[1])).toBe(1000);
      expect(await balance(users[0])).toBe(1000 - FEE);
    } finally {
      await st.stop();
    }
  });
});
