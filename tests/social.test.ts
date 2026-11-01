import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { ChatMessage, EmoteMessage, RoomView } from '../shared/protocol';
import { CHAT_MAX_CHARS, EMOTE_CATEGORIES, EMOTES, INSTANT_REACTIONS, normalizeChat, RateLimiter } from '../shared/social';
import { bootServer, emit, nextUpdate, registerUser, socketFor, tick, type C, type TestServer } from './helpers';

let t: TestServer;
beforeAll(async () => {
  t = await bootServer({ timings: { botThinkMs: 60_000 } });
});
afterAll(() => t.stop());

const call = emit;
function collect<K extends 'chat:message' | 'emote'>(c: C, ev: K) {
  const got: (K extends 'emote' ? EmoteMessage : ChatMessage)[] = [];
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  c.on(ev as any, (m: any) => got.push(m));
  return got;
}

/** Host + guest in one started (free) room, plus an outsider in a different room. */
async function setup() {
  const uh = await registerUser(t, 'Emon');
  const ug = await registerUser(t, 'রাফি');
  const uo = await registerUser(t, 'Outsider');
  const [host, guest, outsider] = await Promise.all([socketFor(t, uh.cookie), socketFor(t, ug.cookie), socketFor(t, uo.cookie)]);
  const created = await call<{ room: RoomView }>(host, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
  const code = created.ok ? created.room.code : '';
  const joined = await call<{ room: RoomView }>(guest, 'room:join', { code });
  await call(outsider, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
  await call(guest, 'room:ready', { ready: true });
  const started = nextUpdate(host);
  await call(host, 'room:start');
  await started;
  const room = joined.ok ? joined.room : null;
  return { host, guest, outsider, code, uh, ug, guestSeatId: room!.seats[1].id, hostSeatId: room!.seats[0].id };
}

describe('emote catalogue and chat validation', () => {
  it('only references approved emotes', () => {
    for (const cat of EMOTE_CATEGORIES) for (const id of cat.emotes) expect(EMOTES[id]).toBeDefined();
    for (const id of INSTANT_REACTIONS) expect(EMOTES[id]).toBeDefined();
  });

  it('normalises text but keeps emoji, Bangla and markup characters as plain text', () => {
    expect(normalizeChat('  Good game!   🎉  ')).toBe('Good game! 🎉');
    expect(normalizeChat('আমি জিতব! 😂😂')).toBe('আমি জিতব! 😂😂');
    expect(normalizeChat('👨‍👩‍👧 family')).toBe('👨‍👩‍👧 family'); // ZWJ sequence survives
    expect(normalizeChat('<script>alert(1)</script>')).toBe('<script>alert(1)</script>');
    expect(normalizeChat('line\nbreak‮evil')).toBe('line break evil');
    expect(normalizeChat('   ')).toBeNull();
    expect(normalizeChat(42)).toBeNull();
    expect(normalizeChat('x'.repeat(5000))).toBeNull();
    expect(Array.from(normalizeChat('😂'.repeat(300))!)).toHaveLength(CHAT_MAX_CHARS);
  });

  it('rate limiter enforces gap and burst', () => {
    const r = new RateLimiter(3, 1000, 100);
    expect(r.take(0)).toBe(true);
    expect(r.take(50)).toBe(false); // gap
    expect(r.take(150)).toBe(true);
    expect(r.take(300)).toBe(true);
    expect(r.take(450)).toBe(false); // burst of 3 in window
    expect(r.wait(450)).toBe(550);
    expect(r.take(1001)).toBe(true);
  });
});

describe('room chat and emotes over Socket.IO', () => {
  it('delivers chat to everyone in the room, only that room, with server-assigned identity', async () => {
    const { host, guest, outsider, guestSeatId, ug } = await setup();
    const hostGot = collect(host, 'chat:message');
    const guestGot = collect(guest, 'chat:message');
    const outsiderGot = collect(outsider, 'chat:message');

    // The client tries to claim to be someone else — the server ignores those fields.
    const res = await call(guest, 'chat:send', { text: 'ভালো খেলা! Good game 🎉 <b>hi</b>', seatId: 'fake', name: 'Admin', color: 'blue' });
    expect(res.ok).toBe(true);
    await tick();
    expect(hostGot).toHaveLength(1);
    expect(guestGot).toHaveLength(1);
    expect(outsiderGot).toHaveLength(0);
    expect(hostGot[0]).toMatchObject({ seatId: guestSeatId, name: ug.username, color: 'yellow', text: 'ভালো খেলা! Good game 🎉 <b>hi</b>', quick: false });
  });

  it('broadcasts approved emotes to the room and rejects anything else', async () => {
    const { host, guest, outsider, hostSeatId, uh } = await setup();
    const guestGot = collect(guest, 'emote');
    const outsiderGot = collect(outsider, 'emote');
    expect((await call(host, 'emote:send', { id: 'fire' })).ok).toBe(true);
    await tick();
    expect(guestGot).toHaveLength(1);
    expect(guestGot[0]).toMatchObject({ id: 'fire', seatId: hostSeatId, name: uh.username, color: 'red' });
    expect(outsiderGot).toHaveLength(0);

    await tick(1300);
    expect(await call(host, 'emote:send', { id: 'constructor' })).toEqual({ ok: false, error: 'Unknown emote' });
    expect(await call(host, 'emote:send', { id: '<img src=x>' })).toEqual({ ok: false, error: 'Unknown emote' });
    expect((await call(host, 'emote:send', 'fire')).ok).toBe(false); // malformed payload
    // An outsider cannot emote into a room it is not seated in (it has its own lobby room).
    expect(await call(outsider, 'emote:send', { id: 'fire' })).toEqual({ ok: false, error: 'Emotes are available during a match' });
  });

  it('rate-limits emotes and chat separately on the server', async () => {
    const { host } = await setup();
    expect((await call(host, 'emote:send', { id: 'clap' })).ok).toBe(true);
    expect(await call(host, 'emote:send', { id: 'clap' })).toEqual({ ok: false, error: 'Emote cooling down' });
    // Chat is unaffected by the emote cooldown.
    expect((await call(host, 'chat:send', { text: 'one' })).ok).toBe(true);
    expect(await call(host, 'chat:send', { text: 'two' })).toEqual({ ok: false, error: 'You are sending messages too fast' });
    let sent = 1;
    for (let i = 0; i < 8; i++) {
      await tick(520);
      if ((await call(host, 'chat:send', { text: `m${i}` })).ok) sent++;
    }
    expect(sent).toBe(5); // burst of 5 per window
  });

  it('rejects empty, oversized and non-string messages', async () => {
    const { host } = await setup();
    expect(await call(host, 'chat:send', { text: '   ' })).toEqual({ ok: false, error: 'Message is empty or too long' });
    expect(await call(host, 'chat:send', { text: 'x'.repeat(1001) })).toEqual({ ok: false, error: 'Message is empty or too long' });
    expect(await call(host, 'chat:send', { text: { $gt: '' } })).toEqual({ ok: false, error: 'Message is empty or too long' });
  });

  it('replays recent chat to a player who reconnects', async () => {
    const { host, code, ug, uh } = await setup();
    await call(host, 'chat:send', { text: 'Good luck! 🍀', quick: true });
    const again = await socketFor(t, ug.cookie);
    const history = new Promise<ChatMessage[]>((r) => again.on('chat:history', r));
    const hello = await call<{ room: RoomView | null }>(again, 'session:hello');
    expect(hello.ok && hello.room?.code).toBe(code);
    const h = await history;
    expect(h.at(-1)).toMatchObject({ text: 'Good luck! 🍀', quick: true, name: uh.username });
  });
});
