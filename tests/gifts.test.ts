import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { toMysql } from '../server/db';
import { DUPLICATE_REFUND_SHARE, rewardStyle, type RewardEvent } from '../shared/gifts';
import { bootServer, next, registerUser, socketFor, tick, type C, type TestServer } from './helpers';

/** Administrator gifts against the real server: who may send, delivery, and crediting exactly once. */

let t: TestServer;
let admin: Awaited<ReturnType<typeof registerUser>>;

type Reg = Awaited<ReturnType<typeof registerUser>>;
interface GiftOut {
  id: string;
  status: string;
  box: { id: string; label: string } | null;
  content: { kind: string; itemId: string | null; name: string; quantity: number; rarity: string } | null;
  received: { kind: string; itemId: string | null; amount: number; duplicate: boolean } | null;
}
interface Inbox {
  items: GiftOut[];
  unclaimed: number;
}
interface ClaimOut {
  gift: GiftOut;
  duplicate: boolean;
  reward: RewardEvent | null;
  wallet: { balance: number; gems: number };
  unclaimed: number;
}

/** A server per group: sends are rate limited per administrator, and each test sends a few. */
function freshServer(): void {
  beforeAll(async () => {
    t = await bootServer();
    admin = await registerUser(t, 'Gift Admin', 'admin@example.com');
  });
  afterAll(() => t.stop());
}

const send = (body: Record<string, unknown>) => admin.call<{ sent: number; skipped: number; batchId: string; code?: string }>('POST', '/admin/gifts', { requestId: randomUUID(), ...body });
const inbox = async (u: Reg) => (await u.call<Inbox>('GET', '/gifts')).body;
const only = async (u: Reg) => (await inbox(u)).items[0];
const claim = (u: Reg, id: string) => u.call<ClaimOut>('POST', `/gifts/${id}/claim`);
const wallet = async (u: Reg) => (await u.call<{ wallet: { balance: number; gems: number } }>('GET', '/wallet')).body.wallet;
const coinRows = (u: Reg, id: string) => t.server.db.query('select * from wallet_transactions where user_id = $1 and idempotency_key = $2', [u.id, `gift:${id}`]);
const auditFor = async (action: string, giftId: string) => {
  const rows = (await t.server.db.query<{ details: unknown; target_user_id: string }>(`select details, target_user_id from audit_logs where action = $1`, [action])).rows;
  return rows.filter((r) => (typeof r.details === 'string' ? JSON.parse(r.details) : (r.details as { giftId?: string })).giftId === giftId);
};

async function giftTo(u: Reg, body: Record<string, unknown> = { kind: 'coins', quantity: 500 }): Promise<string> {
  const r = await send({ userIds: [u.id], ...body });
  expect(r.status).toBe(200);
  return (await only(u)).id;
}

describe('sending', () => {
  freshServer();

  it('is for administrators only, on every route', async () => {
    const p = await registerUser(t, 'Not Admin');
    const body = { requestId: randomUUID(), kind: 'coins', quantity: 100, userIds: [p.id] };
    expect((await p.call('POST', '/admin/gifts', body)).status).toBe(403);
    expect((await p.call('POST', '/admin/gifts/preview', body)).status).toBe(403);
    expect((await p.call('GET', '/admin/gifts')).status).toBe(403);
    expect((await p.call('GET', '/admin/gifts/options')).status).toBe(403);
    expect((await p.call('POST', `/admin/gifts/${randomUUID()}/cancel`)).status).toBe(403);
    expect((await inbox(p)).items).toHaveLength(0);
    const anon = await fetch(`${t.url}/api/admin/gifts`, { headers: { 'x-requested-with': 'ludo3d' } });
    expect(anon.status).toBe(401);
  });

  it('sends to one player, and writes an audit record and a notification', async () => {
    const p = await registerUser(t, 'One');
    const r = await send({ userIds: [p.id], kind: 'coins', quantity: 777, message: 'Thanks for playing' });
    expect(r.body.sent).toBe(1);
    const g = await only(p);
    expect(g.status).toBe('pending');
    expect(g.content).toMatchObject({ kind: 'coins', quantity: 777, name: 'Coins' });
    const audit = await auditFor('gift.send', g.id);
    expect(audit).toHaveLength(1);
    expect(audit[0].target_user_id).toBe(p.id);
    const notes = await p.call<{ items: { type: string; payload: { giftId?: string; message: string } }[] }>('GET', '/notifications');
    const note = notes.body.items.find((n) => n.payload.giftId === g.id)!;
    // 'system' with `message`: an app that knows nothing about gifts still shows a sensible line.
    expect(note.type).toBe('system');
    expect(note.payload.message).toContain('gift');
  });

  it('sends to several players only when confirmed', async () => {
    const a = await registerUser(t, 'Many A');
    const b = await registerUser(t, 'Many B');
    const unconfirmed = await send({ userIds: [a.id, b.id], kind: 'diamonds', quantity: 5 });
    expect(unconfirmed.status).toBe(400);
    expect(unconfirmed.body.code).toBe('CONFIRM_REQUIRED');
    expect((await inbox(a)).items).toHaveLength(0);
    const r = await send({ userIds: [a.id, b.id], kind: 'diamonds', quantity: 5, confirm: true });
    expect(r.body.sent).toBe(2);
    expect((await only(a)).content?.quantity).toBe(5);
    expect((await only(b)).content?.quantity).toBe(5);
  });

  it('sends to everyone eligible, never the suspended, after a count check', async () => {
    const st = await bootServer();
    try {
      const boss = await registerUser(st, 'Boss', 'admin@example.com');
      const fine = await registerUser(st, 'Fine');
      const also = await registerUser(st, 'Also Fine');
      const banned = await registerUser(st, 'Banned');
      await boss.call('POST', `/admin/users/${banned.id}/suspend`, { reason: 'cheating' });
      const options = await boss.call<{ eligible: number }>('GET', '/admin/gifts/options');
      expect(options.body.eligible).toBe(2);
      const body = { requestId: randomUUID(), all: true, kind: 'coins', quantity: 50, confirm: true };
      expect((await boss.call('POST', '/admin/gifts', body)).status).toBe(409); // no count confirmed
      const r = await boss.call<{ sent: number }>('POST', '/admin/gifts', { ...body, confirmCount: 2 });
      expect(r.body.sent).toBe(2);
      for (const u of [fine, also]) expect((await u.call<Inbox>('GET', '/gifts')).body.unclaimed).toBe(1);
      const rows = await st.server.db.query('select 1 from gifts where recipient_id = $1', [banned.id]);
      expect(rows.rows).toHaveLength(0);
      const refused = await boss.call('POST', '/admin/gifts', { requestId: randomUUID(), userIds: [banned.id], kind: 'coins', quantity: 1 });
      expect(refused.status).toBe(400);
    } finally {
      await st.stop();
    }
  });

  it('a repeated request never gives the same gift twice', async () => {
    const p = await registerUser(t, 'Double Click');
    const requestId = randomUUID();
    const body = { requestId, userIds: [p.id], kind: 'coins', quantity: 10 };
    expect((await admin.call<{ sent: number }>('POST', '/admin/gifts', body)).body.sent).toBe(1);
    const again = await admin.call<{ sent: number; skipped: number }>('POST', '/admin/gifts', body);
    expect(again.body).toMatchObject({ sent: 0, skipped: 1 });
    expect((await inbox(p)).items).toHaveLength(1);
  });

  it('validates what is sent', async () => {
    const p = await registerUser(t, 'Validate');
    const bad = async (b: Record<string, unknown>) => (await send({ userIds: [p.id], ...b })).status;
    expect(await bad({ kind: 'token_skin', itemId: 'x' })).toBe(400);
    expect(await bad({ kind: 'coins', quantity: 0 })).toBe(400);
    expect(await bad({ kind: 'coins', quantity: -5 })).toBe(400);
    expect(await bad({ kind: 'coins', quantity: 1.5 })).toBe(400);
    expect(await bad({ kind: 'coins', quantity: 10_000_001 })).toBe(400);
    expect(await bad({ kind: 'dice', itemId: 'no_such_die' })).toBe(400);
    expect(await bad({ kind: 'dice', itemId: 'classic' })).toBe(400); // free for everyone
    expect(await bad({ kind: 'coins', quantity: 5, box: 'golden_ticket' })).toBe(400);
    expect(await bad({ kind: 'coins', quantity: 5, message: 'x'.repeat(201) })).toBe(400);
    expect(await bad({ kind: 'coins', quantity: 5, message: '<script>' })).toBe(400);
    expect(await bad({ kind: 'coins', quantity: 5, expiresAt: new Date(Date.now() - 1000).toISOString() })).toBe(400);
    expect(await bad({ kind: 'coins', quantity: 5, expiresAt: 'soon' })).toBe(400);
    expect((await send({ userIds: [randomUUID()], kind: 'coins', quantity: 5 })).status).toBe(400);
    expect((await admin.call('POST', '/admin/gifts', { userIds: [p.id], kind: 'coins', quantity: 5 })).status).toBe(400); // no request id
    expect((await inbox(p)).items).toHaveLength(0);
  });

  it('limits sends per administrator', async () => {
    const st = await bootServer();
    try {
      const boss = await registerUser(st, 'Boss', 'admin@example.com');
      const p = await registerUser(st, 'Target');
      const codes: number[] = [];
      for (let i = 0; i < 31; i++) codes.push((await boss.call('POST', '/admin/gifts', { requestId: randomUUID(), userIds: [p.id], kind: 'coins', quantity: 1 })).status);
      expect(codes.slice(0, 30).every((c) => c === 200)).toBe(true);
      expect(codes[30]).toBe(429);
    } finally {
      await st.stop();
    }
  });

  it('previews without sending, and says who already owns a cosmetic', async () => {
    const owner = await registerUser(t, 'Owner');
    const fresh = await registerUser(t, 'Fresh');
    await t.server.db.query('insert into user_frames (user_id, frame_id) values ($1, $2)', [owner.id, 'fire']);
    const r = await admin.call<{ recipients: number; alreadyOwn: number; gift: { name: string } }>('POST', '/admin/gifts/preview', { userIds: [owner.id, fresh.id], kind: 'frame', itemId: 'fire' });
    expect(r.body).toMatchObject({ recipients: 2, alreadyOwn: 1, gift: { name: 'Fire Ring' } });
    expect((await inbox(owner)).items).toHaveLength(0);
  });
});

describe('claiming', () => {
  freshServer();

  it('credits coins once, through the ledger, and reports the confirmed reward', async () => {
    const p = await registerUser(t, 'Coins');
    const before = (await wallet(p)).balance;
    const id = await giftTo(p, { kind: 'coins', quantity: 500 });
    const r = await claim(p, id);
    expect(r.status).toBe(200);
    expect(r.body.duplicate).toBe(false);
    expect(r.body.gift.status).toBe('claimed');
    expect(r.body.reward).toMatchObject({ kind: 'coins', amount: 500, before, after: before + 500, source: 'gift', style: rewardStyle('coins', 500) });
    expect(r.body.wallet.balance).toBe(before + 500);
    const rows = await coinRows(p, id);
    expect(rows.rows).toHaveLength(1);
    expect((rows.rows[0] as { type: string; created_by: string }).type).toBe('gift');
    expect((rows.rows[0] as { created_by: string }).created_by).toBe(admin.id);
    expect(await auditFor('gift.claim', id)).toHaveLength(1);
    expect((await inbox(p)).unclaimed).toBe(0);
  });

  it('a second claim moves nothing and answers with the first', async () => {
    const p = await registerUser(t, 'Twice');
    const id = await giftTo(p, { kind: 'coins', quantity: 300 });
    await claim(p, id);
    const after = (await wallet(p)).balance;
    const again = await claim(p, id);
    expect(again.status).toBe(200);
    expect(again.body.duplicate).toBe(true);
    expect(again.body.reward).toBeNull();
    expect(again.body.gift.received?.amount).toBe(300);
    expect((await wallet(p)).balance).toBe(after);
  });

  it('concurrent claims credit exactly once', async () => {
    const p = await registerUser(t, 'Racer');
    const before = await wallet(p);
    const coins = await giftTo(p, { kind: 'coins', quantity: 200 });
    const results = await Promise.all(Array.from({ length: 10 }, () => claim(p, coins)));
    expect(results.every((r) => r.status === 200)).toBe(true);
    expect(results.filter((r) => !r.body.duplicate)).toHaveLength(1);
    expect(results.filter((r) => r.body.reward)).toHaveLength(1);
    expect((await wallet(p)).balance).toBe(before.balance + 200);
    expect((await coinRows(p, coins)).rows).toHaveLength(1);
    const claims = await t.server.db.query('select * from gift_claims where gift_id = $1', [coins]);
    expect(claims.rows).toHaveLength(1);
  });

  it('the database itself refuses a second claim row', async () => {
    const p = await registerUser(t, 'Constraint');
    const id = await giftTo(p, { kind: 'coins', quantity: 1 });
    await claim(p, id);
    await expect(
      t.server.db.query(`insert into gift_claims (gift_id, user_id, granted_kind, granted_amount) values ($1, $2, 'coins', 1)`, [id, p.id]),
    ).rejects.toThrow();
  });

  it('refuses another player, without saying whether the gift exists', async () => {
    const p = await registerUser(t, 'Owner Of Gift');
    const thief = await registerUser(t, 'Thief');
    const id = await giftTo(p);
    const before = await wallet(thief);
    expect((await claim(thief, id)).status).toBe(404);
    expect((await claim(thief, randomUUID())).status).toBe(404);
    expect((await thief.call('POST', `/gifts/${id}/open`)).status).toBe(404);
    expect((await thief.call('GET', `/gifts/${id}`)).status).toBe(404);
    expect((await wallet(thief)).balance).toBe(before.balance);
    expect((await only(p)).status).toBe('pending');
  });

  it('refuses an expired gift, and records that it expired', async () => {
    const p = await registerUser(t, 'Late');
    const before = (await wallet(p)).balance;
    const id = await giftTo(p, { kind: 'coins', quantity: 100, expiresAt: new Date(Date.now() + 3_600_000).toISOString() });
    await t.server.db.query('update gifts set expires_at = $2 where id = $1', [id, new Date(Date.now() - 1000)]);
    const r = await claim(p, id);
    expect(r.status).toBe(410);
    expect(r.body.code).toBe('GIFT_EXPIRED');
    expect((await wallet(p)).balance).toBe(before);
    const row = await t.server.db.query<{ status: string }>('select status from gifts where id = $1', [id]);
    expect(row.rows[0].status).toBe('expired');
    expect((await inbox(p)).unclaimed).toBe(0);
    expect((await claim(p, id)).status).toBe(410);
  });

  it('an expiring gift shows as expired in the inbox and the history without anyone claiming', async () => {
    const p = await registerUser(t, 'Forgot');
    const id = await giftTo(p, { kind: 'coins', quantity: 100, expiresAt: new Date(Date.now() + 3_600_000).toISOString() });
    await t.server.db.query('update gifts set expires_at = $2 where id = $1', [id, new Date(Date.now() - 1000)]);
    expect((await only(p)).status).toBe('expired');
    const hist = await admin.call<{ items: { id: string }[] }>('GET', `/admin/gifts?status=expired&q=${p.username}`);
    expect(hist.body.items.map((x) => x.id)).toEqual([id]);
  });

  it('refuses a cancelled gift; a claimed one cannot be cancelled', async () => {
    const p = await registerUser(t, 'Cancelled');
    const before = (await wallet(p)).balance;
    const id = await giftTo(p);
    expect((await admin.call('POST', `/admin/gifts/${id}/cancel`)).status).toBe(200);
    expect(await auditFor('gift.cancel', id)).toHaveLength(1);
    const r = await claim(p, id);
    expect(r.status).toBe(410);
    expect(r.body.code).toBe('GIFT_CANCELLED');
    expect((await wallet(p)).balance).toBe(before);
    expect((await admin.call('POST', `/admin/gifts/${id}/cancel`)).status).toBe(409);

    const other = await giftTo(p);
    await claim(p, other);
    expect((await admin.call('POST', `/admin/gifts/${other}/cancel`)).status).toBe(409);
    expect((await only(p)).status).toBe('claimed');
  });

  it('a cancel racing a claim leaves exactly one outcome', async () => {
    const p = await registerUser(t, 'Race Cancel');
    const before = (await wallet(p)).balance;
    const id = await giftTo(p, { kind: 'coins', quantity: 90 });
    const [c, x] = await Promise.all([claim(p, id), admin.call('POST', `/admin/gifts/${id}/cancel`)]);
    const status = (await only(p)).status;
    if (status === 'claimed') {
      expect(c.status).toBe(200);
      expect(x.status).toBe(409);
      expect((await wallet(p)).balance).toBe(before + 90);
    } else {
      expect(status).toBe('cancelled');
      expect(c.status).toBe(410);
      expect((await wallet(p)).balance).toBe(before);
    }
  });

  it('credits diamonds through their ledger', async () => {
    const p = await registerUser(t, 'Diamonds');
    const before = (await wallet(p)).gems;
    const id = await giftTo(p, { kind: 'diamonds', quantity: 150 });
    const r = await claim(p, id);
    expect(r.body.reward).toMatchObject({ kind: 'diamonds', amount: 150, before, after: before + 150, style: 'legendary_diamond' });
    expect(r.body.wallet.gems).toBe(before + 150);
    const rows = await t.server.db.query('select 1 from gem_transactions where user_id = $1 and idempotency_key = $2', [p.id, `gift:${id}`]);
    expect(rows.rows).toHaveLength(1);
    await claim(p, id);
    expect((await wallet(p)).gems).toBe(before + 150);
  });

  it.each([
    ['dice', 'gold', '/skins'],
    ['board', 'love_story', '/boards'],
    ['frame', 'fire', '/frames'],
  ])('puts a %s in the inventory, usable through the normal shop routes', async (kind, itemId, shop) => {
    const p = await registerUser(t, 'Cosmetic');
    const id = await giftTo(p, { kind, itemId });
    const before = await wallet(p);
    const r = await claim(p, id);
    expect(r.status).toBe(200);
    expect(r.body.reward).toBeNull();
    expect(r.body.gift.received).toMatchObject({ kind, itemId, duplicate: false });
    const state = await p.call<{ owned: string[] }>('GET', shop);
    expect(state.body.owned).toContain(itemId);
    expect((await p.call('POST', `${shop}/${itemId}/equip`)).status).toBe(200);
    expect(await wallet(p)).toMatchObject({ balance: before.balance, gems: before.gems });
  });

  it('a cosmetic already owned is not granted twice: the player gets its refund, once', async () => {
    const p = await registerUser(t, 'Has It');
    await t.server.db.query('insert into user_frames (user_id, frame_id) values ($1, $2)', [p.id, 'fire']);
    const before = await wallet(p);
    const id = await giftTo(p, { kind: 'frame', itemId: 'fire' });
    const r = await claim(p, id);
    expect(r.status).toBe(200);
    const price = t.server.settings.price('frame', 'fire');
    const refund = Math.round(price * DUPLICATE_REFUND_SHARE);
    expect(r.body.gift.received).toMatchObject({ kind: 'diamonds', amount: refund, duplicate: true });
    expect(r.body.reward).toMatchObject({ kind: 'diamonds', amount: refund });
    expect((await wallet(p)).gems).toBe(before.gems + refund);
    const owned = await t.server.db.query('select 1 from user_frames where user_id = $1 and frame_id = $2', [p.id, 'fire']);
    expect(owned.rows).toHaveLength(1);
    await claim(p, id);
    expect((await wallet(p)).gems).toBe(before.gems + refund);
  });

  it('two gifts of the same cosmetic: the second is the duplicate', async () => {
    const p = await registerUser(t, 'Two Boards');
    await send({ userIds: [p.id], kind: 'board', itemId: 'love_story' });
    await send({ userIds: [p.id], kind: 'board', itemId: 'love_story' });
    const [second, first] = (await inbox(p)).items;
    const coins = (await wallet(p)).balance;
    expect((await claim(p, first.id)).body.gift.received?.duplicate).toBe(false);
    const dup = await claim(p, second.id);
    expect(dup.body.gift.received).toMatchObject({ kind: 'coins', duplicate: true });
    expect((await wallet(p)).balance).toBe(coins + dup.body.gift.received!.amount);
  });

  it('a gift does not put a cosmetic on by itself', async () => {
    const p = await registerUser(t, 'Not Equipped');
    await claim(p, await giftTo(p, { kind: 'dice', itemId: 'gold' }));
    const state = await p.call<{ equipped: string }>('GET', '/skins');
    expect(state.body.equipped).toBe('classic');
  });
});

describe('gift boxes and states', () => {
  freshServer();

  it('a box hides its real content until opened, and credits exactly that', async () => {
    const p = await registerUser(t, 'Box');
    const id = await giftTo(p, { kind: 'coins', quantity: 1234, box: 'mystery', message: 'Surprise!' });
    const listed = await only(p);
    expect(listed.box).toEqual({ id: 'mystery', label: 'Mystery Box' });
    expect(listed.content).toBeNull();
    const body = JSON.stringify((await inbox(p)).items);
    expect(body).not.toContain('1234');

    const opened = await p.call<{ gift: GiftOut }>('POST', `/gifts/${id}/open`);
    expect(opened.body.gift.status).toBe('opened');
    expect(opened.body.gift.content?.quantity).toBe(1234);
    // Opening can be replayed; it changes nothing.
    expect((await p.call<{ gift: GiftOut }>('POST', `/gifts/${id}/open`)).body.gift.status).toBe('opened');
    const before = (await wallet(p)).balance;
    expect((await claim(p, id)).body.reward?.amount).toBe(1234);
    expect((await wallet(p)).balance).toBe(before + 1234);
    // Replaying a claimed gift still shows it and pays nothing.
    const replay = await p.call<{ gift: GiftOut }>('POST', `/gifts/${id}/open`);
    expect(replay.body.gift.status).toBe('claimed');
    expect((await wallet(p)).balance).toBe(before + 1234);
  });

  it('an unopened box that expires keeps its secret', async () => {
    const p = await registerUser(t, 'Secret');
    const id = await giftTo(p, { kind: 'coins', quantity: 4321, box: 'royal', expiresAt: new Date(Date.now() + 3_600_000).toISOString() });
    await t.server.db.query('update gifts set expires_at = $2 where id = $1', [id, new Date(Date.now() - 1000)]);
    const g = await only(p);
    expect(g.status).toBe('expired');
    expect(g.content).toBeNull();
  });

  it('claiming directly from PENDING works (a phone that lost the open reply)', async () => {
    const p = await registerUser(t, 'Direct');
    const id = await giftTo(p, { kind: 'coins', quantity: 5, box: 'vip' });
    expect((await claim(p, id)).status).toBe(200);
  });
});

describe('delivery', () => {
  freshServer();

  it('reaches an online player live, and their other screens hear of the claim', async () => {
    const p = await registerUser(t, 'Online');
    const s: C = await socketFor(t, p.cookie);
    const live = next<{ giftId: string; title: string }>(s, 'gift:new');
    const note = next<{ type: string; payload: { giftId: string } }>(s, 'notify').catch(() => null);
    await send({ userIds: [p.id], kind: 'coins', quantity: 60, box: 'festival' });
    const g = await live;
    expect(g.title).toContain('Festival');
    expect((await note)?.payload.giftId).toBe(g.giftId);
    const walletEvent = next<{ balance: number }>(s, 'wallet');
    const update = next<{ status: string }>(s, 'gift:update');
    const r = await claim(p, g.giftId);
    expect((await walletEvent).balance).toBe(r.body.wallet.balance);
    expect((await update).status).toBe('claimed');
  });

  it('waits for an offline player and is there when they return', async () => {
    const p = await registerUser(t, 'Offline');
    await send({ userIds: [p.id], kind: 'coins', quantity: 40 });
    // No socket was ever open: the inbox and the notification list still have it.
    const s = await socketFor(t, p.cookie);
    await tick(50);
    const box = await inbox(p);
    expect(box.unclaimed).toBe(1);
    expect(box.items[0].status).toBe('pending');
    const n = await p.call<{ unread: number }>('GET', '/notifications');
    expect(n.body.unread).toBeGreaterThan(0);
    expect((await claim(p, box.items[0].id)).status).toBe(200);
    s.close();
  });
});

describe('history', () => {
  freshServer();

  it('lists gifts with sender and recipient, filtered by status and type', async () => {
    const p = await registerUser(t, 'History');
    const coins = await giftTo(p, { kind: 'coins', quantity: 11 });
    await send({ userIds: [p.id], kind: 'diamonds', quantity: 22 });
    await send({ userIds: [p.id], kind: 'frame', itemId: 'neon' });
    await claim(p, coins);
    const list = async (qs: string) => (await admin.call<{ items: { id: string; kind: string; status: string; sender: string; recipient: string }[] }>('GET', `/admin/gifts?q=${p.username}&${qs}`)).body.items;
    const all = await list('');
    expect(all).toHaveLength(3);
    expect(all.every((g) => g.recipient === p.username && g.sender === admin.username)).toBe(true);
    expect((await list('status=claimed')).map((g) => g.id)).toEqual([coins]);
    expect((await list('status=pending')).map((g) => g.kind).sort()).toEqual(['diamonds', 'frame']);
    expect((await list('type=coins')).map((g) => g.kind)).toEqual(['coins']);
    expect((await list('type=diamonds')).map((g) => g.kind)).toEqual(['diamonds']);
    expect((await list('type=cosmetics')).map((g) => g.kind)).toEqual(['frame']);
  });
});

describe('rewards from the existing paths', () => {
  freshServer();

  it('the daily bonus and the daily spin report what the ledger confirmed', async () => {
    const p = await registerUser(t, 'Daily');
    const coinsBefore = (await wallet(p)).balance;
    const d = await p.call<{ reward: RewardEvent; wallet: { balance: number } }>('POST', '/wallet/daily-bonus');
    expect(d.body.reward).toMatchObject({ source: 'daily_bonus', kind: 'coins', before: coinsBefore, after: d.body.wallet.balance });
    const s = await p.call<{ reward: RewardEvent; prize: number; gems: number }>('POST', '/daily-spin');
    expect(s.body.reward).toMatchObject({ source: 'daily_spin', kind: 'diamonds', amount: s.body.prize, after: s.body.gems, before: s.body.gems - s.body.prize });
    expect(s.body.reward.id).toBeTruthy();
  });

  it.each([
    ['coins', 50, 'silver_coin'],
    ['coins', 100, 'gold_coin'],
    ['coins', 5000, 'crown_coin'],
    ['diamonds', 99, 'diamond'],
    ['diamonds', 100, 'legendary_diamond'],
  ] as const)('%s × %i looks like %s', (kind, amount, style) => {
    expect(rewardStyle(kind, amount)).toBe(style);
  });
});

describe('the SQL the MySQL adapter will run (no MySQL here, so the translation is what is checked)', () => {
  it('turns a many-row insert into plain placeholders, in order', () => {
    const r = toMysql('insert into notifications (id, user_id, type, payload) values ($1, $2, $3, $4::jsonb), ($5, $6, $7, $8::jsonb)', ['a', 'b', 'c', '{}', 'd', 'e', 'f', '{}']);
    expect(r.sql).toBe('insert into notifications (id, user_id, type, payload) values (?, ?, ?, ?), (?, ?, ?, ?)');
    expect(r.params).toEqual(['a', 'b', 'c', '{}', 'd', 'e', 'f', '{}']);
  });

  it('repeats a parameter used twice, and turns ignoring a conflict into insert ignore', () => {
    const r = toMysql(`insert into audit_logs (actor_id, action, target_user_id, details) values ($1, 'gift.claim', $1, $2::jsonb)`, ['u', '{}']);
    expect(r.sql).toBe(`insert into audit_logs (actor_id, action, target_user_id, details) values (?, 'gift.claim', ?, ?)`);
    expect(r.params).toEqual(['u', 'u', '{}']);
    expect(toMysql('insert into user_frames (user_id, frame_id, gem_tx_id) values ($1, $2, null) on conflict do nothing', ['u', 'f']).sql).toBe(
      'insert ignore into user_frames (user_id, frame_id, gem_tx_id) values (?, ?, null) ',
    );
  });
});
