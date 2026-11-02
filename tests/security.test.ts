import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { safeNext } from '../src/ui/platform/AuthPages';
import { api, bootServer, registerUser, type TestServer } from './helpers';

/** Hardening: admin addresses must be verified, big uploads need an admin, safe redirects. */

let t: TestServer;
beforeAll(async () => {
  t = await bootServer({ config: { adminNeedsVerifiedEmail: true, adminEmails: ['boss@example.com'] } });
});
afterAll(() => t.stop());

type Me = { user: { role: string } | null };

describe('security', () => {
  it('an admin address registered by someone without the mailbox stays a player', async () => {
    const squatter = await registerUser(t, 'Squatter', 'boss@example.com');
    expect((await squatter.call<Me>('GET', '/auth/me')).body.user!.role).toBe('player');
    expect((await squatter.call('GET', '/admin/overview')).status).toBe(403);
    // Signing in again doesn't make it admin either.
    const again = api(t);
    await again.call('POST', '/auth/login', { email: 'boss@example.com', password: 'goodPass123' });
    expect((await again.call<Me>('GET', '/auth/me')).body.user!.role).toBe('player');

    // Following the verification link (from the real mailbox) does.
    // The verification mail is sent in the background: give it a moment.
    await new Promise((r) => setTimeout(r, 100));
    const msg = [...t.server.mailer.outbox].reverse().find((m) => m.to === 'boss@example.com');
    const token = msg ? /token=([A-Za-z0-9_-]+)/.exec(msg.text)?.[1] : undefined;
    expect(token).toBeTruthy();
    await again.call('POST', '/auth/verify-email', { token });
    expect((await again.call<Me>('GET', '/auth/me')).body.user!.role).toBe('admin');
  });

  it('reads a big announcement body only for an administrator', async () => {
    const player = await registerUser(t, 'Player');
    const big = { title: 'x', image: `data:image/png;base64,${'A'.repeat(200_000)}` };
    expect((await player.call('POST', '/admin/announcements', big)).status).toBe(403);
    expect((await api(t).call('POST', '/admin/announcements', big)).status).toBe(401);
  });

  it('only redirects to paths on this site after signing in', () => {
    expect(safeNext('/play?mode=quick')).toBe('/play?mode=quick');
    for (const bad of ['//evil.example', '/\\evil.example', '/a\\..\\\\evil', 'https://evil.example', 'javascript:alert(1)', '/a b']) {
      expect(safeNext(bad)).toBe('/');
    }
  });
});
