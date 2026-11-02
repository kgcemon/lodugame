import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { InvalidGoogleToken, type GoogleIdentity, type GoogleVerifier } from '../server/google';
import { countryForIp } from '../server/country';
import { DICE_SKINS } from '../shared/skins';
import { api, bootServer, type TestServer } from './helpers';

/** Guest accounts (and linking them to Google later), countries from IPs, flag dice. */

const fake: GoogleVerifier = {
  async verify(t: string): Promise<GoogleIdentity> {
    const [kind, sub, email, name] = t.split(':');
    if (kind !== 'google') throw new InvalidGoogleToken('bad');
    return { sub, email, name: name ?? '', picture: null };
  },
};

let t: TestServer;
beforeAll(async () => {
  t = await bootServer({ googleVerifier: fake });
});
afterAll(() => t.stop());

type Me = { user: { id: string; guest: boolean; email: string; fullName: string; country: string | null } | null; wallet: { balance: number } };

describe('guest accounts', () => {
  it('plays without signing in, with the welcome bonus', async () => {
    const g = api(t);
    const r = await g.call<{ welcomeBonus: number }>('POST', '/auth/guest');
    expect(r.status).toBe(201);
    const me = await g.call<Me>('GET', '/auth/me');
    expect(me.body.user).toMatchObject({ guest: true, fullName: 'Guest' });
    expect(me.body.wallet.balance).toBe(r.body.welcomeBonus);
    // Two guests are two accounts.
    const g2 = api(t);
    await g2.call('POST', '/auth/guest');
    expect((await g2.call<Me>('GET', '/auth/me')).body.user!.id).not.toBe(me.body.user!.id);
  });

  it('links to Google later and keeps everything', async () => {
    const g = api(t);
    await g.call('POST', '/auth/guest');
    const before = (await g.call<Me>('GET', '/auth/me')).body;
    await g.call('POST', '/wallet/daily-bonus');
    const coins = (await g.call<Me>('GET', '/auth/me')).body.wallet.balance;

    const r = await g.call<{ linked: boolean; created: boolean }>('POST', '/auth/google', { idToken: 'google:sub-guest-1:saved@gmail.com:Saved Player' });
    expect(r.body).toMatchObject({ linked: true, created: false });
    const after = (await g.call<Me>('GET', '/auth/me')).body;
    expect(after.user).toMatchObject({ id: before.user!.id, guest: false, email: 'saved@gmail.com', fullName: 'Saved Player' });
    expect(after.wallet.balance).toBe(coins);

    // Signing in with that Google account on another phone opens the same player.
    const other = api(t);
    await other.call('POST', '/auth/google', { idToken: 'google:sub-guest-1:saved@gmail.com:Saved Player' });
    expect((await other.call<Me>('GET', '/auth/me')).body.user!.id).toBe(before.user!.id);
  });

  it('a guest picking a Google account that already plays signs in to it (the guest stays apart)', async () => {
    const owner = api(t);
    await owner.call('POST', '/auth/google', { idToken: 'google:sub-old:old@gmail.com:Old Player' });
    const ownerId = (await owner.call<Me>('GET', '/auth/me')).body.user!.id;
    const g = api(t);
    await g.call('POST', '/auth/guest');
    const r = await g.call<{ linked: boolean }>('POST', '/auth/google', { idToken: 'google:sub-old:old@gmail.com:Old Player' });
    expect(r.body.linked).toBe(false);
    expect((await g.call<Me>('GET', '/auth/me')).body.user!.id).toBe(ownerId);
  });
});

describe('countries', () => {
  it('looks up public IPs and gives nothing for private ones', () => {
    expect(countryForIp('103.240.4.29')).toBe('BD');
    expect(countryForIp('::ffff:8.8.8.8')).toBe('US');
    expect(countryForIp('127.0.0.1')).toBeNull();
    expect(countryForIp(undefined)).toBeNull();
  });

  it('shows a player with no known country as null (the app draws a globe)', async () => {
    const g = api(t);
    await g.call('POST', '/auth/guest');
    expect((await g.call<Me>('GET', '/auth/me')).body.user!.country).toBeNull();
  });
});

describe('flag dice', () => {
  it('sells flag dice for the region', () => {
    const flags = DICE_SKINS.filter((d) => d.flag).map((d) => d.flag);
    expect(flags).toEqual(expect.arrayContaining(['BD', 'IN', 'PK', 'NP', 'LK']));
  });
});
