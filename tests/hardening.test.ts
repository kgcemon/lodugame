import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { io as connect } from 'socket.io-client';
import { loadConfig } from '../server/config';
import { createMailer } from '../server/mailer';
import type { RoomView } from '../shared/protocol';
import { api, bootServer, emit, registerUser, socketFor, tick, type C, type TestServer } from './helpers';

/** Attacks that were possible, or floods that had no limit, against the real server. */

const fakeGoogle = {
  verify: async (token: string) => {
    const [sub, email] = token.split('|');
    return { sub, email, name: 'Real Owner', picture: null };
  },
};

let t: TestServer;
beforeAll(async () => {
  t = await bootServer({ googleVerifier: fakeGoogle, config: { adminEmails: ['boss@example.com'], adminNeedsVerifiedEmail: true } });
});
afterAll(() => t.stop());
afterEach(() => vi.restoreAllMocks());

const closed = (c: C) => new Promise<void>((r) => (c.connected ? c.once('disconnect', () => r()) : r()));
const me = async (a: ReturnType<typeof api>) => (await a.call<{ user: { id: string; role: string } | null }>('GET', '/auth/me')).body.user;
const login = (email: string, password = 'goodPass123') => api(t).call('POST', '/auth/login', { email, password });

describe('registering someone else\'s email before they arrive', () => {
  it('locks the squatter out when the real owner signs in with Google', async () => {
    const squatter = await registerUser(t, 'Squatter', 'victim@example.com');
    const squatterSocket = await socketFor(t, squatter.cookie);
    expect((await login('victim@example.com')).status).toBe(200);

    const owner = api(t);
    const signIn = await owner.call('POST', '/auth/google', { idToken: 'sub-victim|victim@example.com' });
    expect(signIn.status).toBe(200);
    expect((await me(owner))?.id).toBe(squatter.id); // same account (nothing lost for a real owner)

    expect((await login('victim@example.com')).status).toBe(401); // the password no longer opens it
    expect(await me(squatter)).toBeNull(); // nor does the session
    await closed(squatterSocket);
    expect(squatterSocket.connected).toBe(false);
  });

  it('cannot become an administrator that way', async () => {
    const squatter = await registerUser(t, 'Fake Boss', 'boss@example.com');
    expect((await me(squatter))?.role).toBe('player');
    const owner = api(t);
    await owner.call('POST', '/auth/google', { idToken: 'sub-boss|boss@example.com' });
    expect((await me(owner))?.role).toBe('admin');
    expect(await me(squatter)).toBeNull();
    expect((await login('boss@example.com')).status).toBe(401);
  });

  it('leaves a verified account its password when Google is linked', async () => {
    const u = await registerUser(t, 'Honest', 'honest@example.com');
    const mail = t.server.mailer.outbox.filter((m) => m.to === 'honest@example.com').pop()!;
    const token = /token=([^\s]+)/.exec(mail.text)![1];
    expect((await u.call('POST', '/auth/verify-email', { token })).status).toBe(200);
    await api(t).call('POST', '/auth/google', { idToken: 'sub-honest|honest@example.com' });
    expect((await login('honest@example.com')).status).toBe(200);
    expect(await me(u)).not.toBeNull();
  });
});

describe('ending a session ends its live connection', () => {
  it('sign-out closes that session\'s sockets and no other session\'s', async () => {
    const u = await registerUser(t, 'Two Phones');
    const phone1 = await socketFor(t, u.cookie);
    const other = api(t);
    await other.call('POST', '/auth/login', { email: u.email, password: 'goodPass123' });
    const phone2 = await socketFor(t, other.cookie);
    await u.call('POST', '/auth/logout');
    await closed(phone1);
    await tick(100);
    expect(phone1.connected).toBe(false);
    expect(phone2.connected).toBe(true);
  });

  it('a stolen session stops working the moment the password changes or is reset', async () => {
    const u = await registerUser(t, 'Changer');
    const stolen = api(t);
    await stolen.call('POST', '/auth/login', { email: u.email, password: 'goodPass123' });
    const s1 = await socketFor(t, stolen.cookie);
    await u.call('POST', '/auth/change-password', { currentPassword: 'goodPass123', password: 'newPass456', confirmPassword: 'newPass456' });
    await closed(s1);
    expect(s1.connected).toBe(false);
    expect(await me(stolen)).toBeNull();

    const stolen2 = api(t);
    await stolen2.call('POST', '/auth/login', { email: u.email, password: 'newPass456' });
    const s2 = await socketFor(t, stolen2.cookie);
    await api(t).call('POST', '/auth/forgot-password', { email: u.email });
    const reset = t.server.mailer.outbox.filter((m) => m.to === u.email && /reset-password/.test(m.text)).pop()!;
    const token = /token=([^\s]+)/.exec(reset.text)![1];
    expect((await api(t).call('POST', '/auth/reset-password', { token, password: 'thirdPass789', confirmPassword: 'thirdPass789' })).status).toBe(200);
    await closed(s2);
    expect(s2.connected).toBe(false);
  });
});

describe('email', () => {
  it('forgot-password answers the same whether the account exists or mail is failing', async () => {
    const u = await registerUser(t, 'Forgetful');
    vi.spyOn(t.server.mailer, 'send').mockRejectedValue(new Error('provider down'));
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const known = await api(t).call('POST', '/auth/forgot-password', { email: u.email });
    const unknown = await api(t).call('POST', '/auth/forgot-password', { email: 'nobody@example.com' });
    expect(known.status).toBe(200);
    expect(known.body).toEqual(unknown.body);
  });

  it('production without a provider sends nothing and keeps links out of the logs', async () => {
    const cfg = loadConfig({ NODE_ENV: 'production' });
    expect(cfg.mail.mode).toBe('disabled');
    const log = vi.spyOn(console, 'log').mockImplementation(() => {});
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    await createMailer(cfg.mail).send({ to: 'a@example.com', subject: 'Reset your password', text: 'https://x/reset-password?token=SECRET' });
    const logged = JSON.stringify([...log.mock.calls, ...warn.mock.calls]);
    expect(logged).not.toContain('SECRET');
    expect(logged).not.toContain('a@example.com');
    expect(loadConfig({ NODE_ENV: 'production', RESEND_API_KEY: 'k' }).mail.mode).toBe('resend');
    expect(loadConfig({}).mail.mode).toBe('console');
  });
});

describe('floods', () => {
  it('limits guesses at one account from many addresses', async () => {
    const was = process.env.TRUST_PROXY;
    process.env.TRUST_PROXY = 'true';
    const st = await bootServer();
    if (was === undefined) delete process.env.TRUST_PROXY;
    else process.env.TRUST_PROXY = was;
    try {
      const u = await registerUser(st, 'Target');
      const attempt = (i: number) =>
        fetch(`${st.url}/api/auth/login`, {
          method: 'POST',
          headers: { 'content-type': 'application/json', 'x-requested-with': 'ludo3d', 'x-forwarded-for': `10.0.${i}.1` },
          body: JSON.stringify({ email: u.email, password: `wrong${i}` }),
        }).then((r) => r.status);
      const statuses: number[] = [];
      for (let i = 0; i < 31; i++) statuses.push(await attempt(i));
      expect(statuses.slice(0, 30).every((s) => s === 401)).toBe(true);
      expect(statuses[30]).toBe(429);
    } finally {
      await st.stop();
    }
  });

  it('limits API calls per client across all paths', async () => {
    const st = await bootServer({ config: { limits: { apiPerMin: 5, socketsPerMin: 1000 } } });
    try {
      const codes: number[] = [];
      for (let i = 0; i < 7; i++) codes.push((await fetch(`${st.url}/api/${i % 2 ? 'config' : 'nope'}${i}`)).status);
      expect(codes.slice(0, 5)).not.toContain(429);
      expect(codes[6]).toBe(429);
    } finally {
      await st.stop();
    }
  });

  it('limits new socket connections per client, even with no valid session', async () => {
    const st = await bootServer({ config: { limits: { apiPerMin: 100000, socketsPerMin: 3 } } });
    try {
      const errors: string[] = [];
      for (let i = 0; i < 5; i++) {
        const c = connect(st.url, { transports: ['websocket'], forceNew: true, reconnection: false });
        errors.push(await new Promise<string>((r) => c.on('connect_error', (e) => r(e.message))));
        c.close();
      }
      expect(errors).toEqual(['unauthorized', 'unauthorized', 'unauthorized', 'busy', 'busy']);
    } finally {
      await st.stop();
    }
  });

  it('limits how many connections one account holds', async () => {
    const u = await registerUser(t, 'Many Tabs');
    const held: C[] = [];
    for (let i = 0; i < 8; i++) held.push(await socketFor(t, u.cookie));
    await expect(socketFor(t, u.cookie)).rejects.toThrow('busy');
    held[0].close();
    await tick(100);
    expect((await socketFor(t, u.cookie)).connected).toBe(true);
  });

  it('limits room creation and code guessing per player', async () => {
    const u = await registerUser(t, 'Room Spammer');
    const s = await socketFor(t, u.cookie);
    const results: boolean[] = [];
    for (let i = 0; i < 31; i++) {
      const r = await emit<{ room: RoomView }>(s, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
      results.push(r.ok);
      await tick(60); // the per-socket limit is not what's being tested
    }
    expect(results.slice(0, 30).every(Boolean)).toBe(true);
    expect(results[30]).toBe(false);

    const g = await registerUser(t, 'Code Guesser');
    const gs = await socketFor(t, g.cookie);
    const guesses: string[] = [];
    for (let i = 0; i < 31; i++) {
      const r = await emit(gs, 'room:join', { code: 'ZZZZZ' });
      guesses.push(r.ok ? 'ok' : r.error);
      await tick(60);
    }
    expect(guesses[0]).toBe('Room not found');
    expect(guesses[30]).toBe('Too many rooms, please wait a moment');
  });
});

describe('what the server tells the outside', () => {
  it('sends strict headers and no internals', async () => {
    const res = await fetch(`${t.url}/healthz`);
    expect(Object.keys((await res.json()) as object).sort()).toEqual(['ok', 'rooms']);
    expect(res.headers.get('x-powered-by')).toBeNull();
    expect(res.headers.get('x-frame-options')).toBe('DENY');
    expect(res.headers.get('cross-origin-opener-policy')).toBe('same-origin');
    const csp = res.headers.get('content-security-policy')!;
    expect(csp).toContain("connect-src 'self';");
    expect(csp).toContain("object-src 'none'");
    expect(csp).not.toMatch(/ws:|wss:|unsafe-eval/);
  });
});
