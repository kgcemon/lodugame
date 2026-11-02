import { createSign, generateKeyPairSync, type JsonWebKey } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { GoogleIdTokenVerifier, InvalidGoogleToken, type GoogleIdentity, type GoogleVerifier } from '../server/google';
import { api, bootServer, registerUser, type TestServer } from './helpers';

/** Google Sign-In: the token check itself, and how accounts are found, linked or created. */

const CLIENT = 'web-client.apps.googleusercontent.com';
const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const jwk = { ...(publicKey.export({ format: 'jwk' }) as JsonWebKey), kid: 'k1', alg: 'RS256', use: 'sig' };

function token(claims: Record<string, unknown>, kid = 'k1', key = privateKey): string {
  const enc = (o: object) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const body = `${enc({ alg: 'RS256', kid, typ: 'JWT' })}.${enc(claims)}`;
  return `${body}.${createSign('RSA-SHA256').update(body).sign(key).toString('base64url')}`;
}

const now = () => Math.floor(Date.now() / 1000);
const good = (over: Record<string, unknown> = {}) => ({
  iss: 'https://accounts.google.com', aud: CLIENT, sub: '1234567890', email: 'Player@Gmail.com', email_verified: true,
  name: 'Gmail Player', iat: now(), exp: now() + 3600, ...over,
});

describe('Google ID token check', () => {
  const v = new GoogleIdTokenVerifier([CLIENT], async () => ({ keys: [jwk], maxAgeMs: 3600_000 }));

  it('accepts a token Google signed for our app', async () => {
    expect(await v.verify(token(good()))).toEqual({ sub: '1234567890', email: 'player@gmail.com', name: 'Gmail Player', picture: null });
    const pic = 'https://lh3.googleusercontent.com/a/abc=s96-c';
    expect((await v.verify(token(good({ picture: pic })))).picture).toBe(pic);
    expect((await v.verify(token(good({ picture: 'http://insecure.example/p.png' })))).picture).toBeNull();
  });

  it('rejects forged, foreign, expired and unverified tokens', async () => {
    const other = generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey;
    const bad = [
      token(good(), 'k1', other), // signed by someone else
      token(good({ aud: 'another-app' })),
      token(good({ iss: 'evil.example.com' })),
      token(good({ exp: now() - 120 })),
      token(good({ email_verified: false })),
      token(good(), 'unknown-key'),
      'not.a.token',
    ];
    for (const t of bad) await expect(v.verify(t)).rejects.toBeInstanceOf(InvalidGoogleToken);
  });
});

/** Tokens are "google:<sub>:<email>:<name>[|<picture url>]" for the server tests. */
const fake: GoogleVerifier = {
  async verify(t: string): Promise<GoogleIdentity> {
    const [id, picture] = t.split('|');
    const [kind, sub, email, name] = id.split(':');
    if (kind !== 'google') throw new InvalidGoogleToken('bad');
    return { sub, email, name: name ?? '', picture: picture ?? null };
  },
};

let t: TestServer;
beforeAll(async () => {
  t = await bootServer({ googleVerifier: fake });
});
afterAll(() => t.stop());

type Me = { user: { id: string; email: string; fullName: string; emailVerified: boolean; avatarUrl: string | null } | null; wallet?: { balance: number } };

describe('sign in with Google', () => {
  it('creates an account with the welcome bonus, then signs back into the same one', async () => {
    const a = api(t);
    const r1 = await a.call<{ created: boolean; welcomeBonus: number }>('POST', '/auth/google', { idToken: 'google:sub-new:new.player@gmail.com:New Player' });
    expect(r1.status).toBe(201);
    expect(r1.body.created).toBe(true);
    const me = await a.call<Me>('GET', '/auth/me');
    expect(me.body.user).toMatchObject({ email: 'new.player@gmail.com', fullName: 'New Player', emailVerified: true });
    expect(me.body.wallet?.balance).toBe(r1.body.welcomeBonus);

    const b = api(t);
    const r2 = await b.call<{ created: boolean }>('POST', '/auth/google', { idToken: 'google:sub-new:new.player@gmail.com:New Player' });
    expect(r2.status).toBe(200);
    expect(r2.body.created).toBe(false);
    expect((await b.call<Me>('GET', '/auth/me')).body.user?.id).toBe(me.body.user?.id);
  });

  it('links an existing email account so the player keeps their coins', async () => {
    const old = await registerUser(t, 'Old Timer', 'old.timer@gmail.com');
    const a = api(t);
    const r = await a.call<{ created: boolean }>('POST', '/auth/google', { idToken: 'google:sub-old:old.timer@gmail.com:Old Timer' });
    expect(r.body.created).toBe(false);
    expect((await a.call<Me>('GET', '/auth/me')).body.user?.id).toBe(old.id);
    // Another Google account can't take that email over.
    const thief = await api(t).call('POST', '/auth/google', { idToken: 'google:sub-other:old.timer@gmail.com:X' });
    expect(thief.status).toBe(409);
  });

  it('keeps the Google profile picture, updated on each sign-in', async () => {
    const a = api(t);
    await a.call('POST', '/auth/google', { idToken: 'google:sub-pic:pic@gmail.com:Pic|https://lh3.googleusercontent.com/a/one=s96-c' });
    expect((await a.call<Me>('GET', '/auth/me')).body.user?.avatarUrl).toBe('https://lh3.googleusercontent.com/a/one=s96-c');
    const b = api(t);
    await b.call('POST', '/auth/google', { idToken: 'google:sub-pic:pic@gmail.com:Pic|https://lh3.googleusercontent.com/a/two=s96-c' });
    expect((await b.call<Me>('GET', '/auth/me')).body.user?.avatarUrl).toBe('https://lh3.googleusercontent.com/a/two=s96-c');
  });

  it('refuses bad tokens and suspended accounts', async () => {
    expect((await api(t).call('POST', '/auth/google', { idToken: 'forged' })).status).toBe(401);
    expect((await api(t).call('POST', '/auth/google', {})).status).toBe(400);

    const admin = await registerUser(t, 'Admin', 'admin@example.com');
    const a = api(t);
    await a.call('POST', '/auth/google', { idToken: 'google:sub-bad:bad.actor@gmail.com:Bad Actor' });
    const id = (await a.call<Me>('GET', '/auth/me')).body.user!.id;
    expect((await admin.call('POST', `/admin/users/${id}/suspend`, { reason: 'cheating' })).status).toBe(200);
    expect((await api(t).call('POST', '/auth/google', { idToken: 'google:sub-bad:bad.actor@gmail.com:Bad Actor' })).status).toBe(403);
  });

  it('says so when Google sign-in is not configured', async () => {
    const off = await bootServer({ googleVerifier: null });
    try {
      expect((await api(off).call('POST', '/auth/google', { idToken: 'google:x:y@gmail.com' })).status).toBe(503);
    } finally {
      await off.stop();
    }
  });
});

describe('legal pages', () => {
  it('serves the privacy policy and terms (linked from the Google consent screen)', async () => {
    for (const [path, title] of [['/privacy', 'Privacy policy'], ['/terms', 'Terms of service']]) {
      const res = await fetch(`${t.url}${path}`);
      expect(res.status).toBe(200);
      expect(res.headers.get('content-type')).toMatch(/text\/html/);
      expect(await res.text()).toContain(title);
    }
  });
});
