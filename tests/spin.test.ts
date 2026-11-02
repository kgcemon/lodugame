import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { SPIN_PRIZES } from '../server/spin';
import { bootServer, registerUser, type TestServer } from './helpers';

/** Daily spin (1 to 10 diamonds once a day) and announcements an administrator shows on home. */

let t: TestServer;
beforeAll(async () => {
  t = await bootServer();
});
afterAll(() => t.stop());

type Spin = { spin: { available: boolean; prizes: number[]; wonToday: number | null } };
type Announcement = { id: string; title: string; body: string; imageUrl: string | null; buttonText: string; active: boolean };

describe('daily spin', () => {
  it('pays 1 to 10 diamonds once per day, even on concurrent taps', async () => {
    const u = await registerUser(t, 'Spinner');
    const before = await u.call<Spin>('GET', '/daily-spin');
    expect(before.body.spin).toMatchObject({ available: true, prizes: SPIN_PRIZES, wonToday: null });

    const both = await Promise.all([u.call<{ prize: number; index: number }>('POST', '/daily-spin'), u.call('POST', '/daily-spin')]);
    const won = both.filter((r) => r.status === 200);
    expect(won).toHaveLength(1);
    expect(both.find((r) => r.status !== 200)?.status).toBe(409);
    const { prize, index } = won[0].body as { prize: number; index: number };
    expect(prize).toBeGreaterThanOrEqual(1);
    expect(prize).toBeLessThanOrEqual(10);
    expect(SPIN_PRIZES[index]).toBe(prize);

    const w = await u.call<{ wallet: { gems: number } }>('GET', '/wallet');
    expect(w.body.wallet.gems).toBe(prize);
    const after = await u.call<Spin>('GET', '/daily-spin');
    expect(after.body.spin).toMatchObject({ available: false, wonToday: prize });
  });
});

describe('announcements', () => {
  it('lets only administrators post; players see the newest active one with its picture', async () => {
    const admin = await registerUser(t, 'Admin Ann', 'admin@example.com');
    const p = await registerUser(t, 'Reader');
    expect((await p.call('POST', '/admin/announcements', { title: 'Hi', body: 'x' })).status).toBe(403);
    expect((await p.call<{ announcement: null }>('GET', '/announcements/current')).body.announcement).toBeNull();

    const png = Buffer.from('89504e470d0a1a0a0000000d49484452', 'hex').toString('base64');
    const made = await admin.call<{ announcement: Announcement }>('POST', '/admin/announcements', {
      title: 'Weekend event',
      body: 'Double diamonds all weekend!',
      image: `data:image/png;base64,${png}`,
      buttonText: 'Play now',
    });
    expect(made.status).toBe(200);
    const a = made.body.announcement;
    expect(a).toMatchObject({ title: 'Weekend event', buttonText: 'Play now', active: true, imageUrl: `/api/announcements/${a.id}/image` });

    const cur = await p.call<{ announcement: Announcement }>('GET', '/announcements/current');
    expect(cur.body.announcement.id).toBe(a.id);
    const img = await fetch(`${t.url}${a.imageUrl}`);
    expect(img.headers.get('content-type')).toBe('image/png');
    expect(Buffer.from(await img.arrayBuffer()).toString('base64')).toBe(png);

    // Bad pictures and empty popups are refused.
    expect((await admin.call('POST', '/admin/announcements', { title: 'X', image: 'data:text/html;base64,PGI+' })).status).toBe(400);
    expect((await admin.call('POST', '/admin/announcements', { title: 'X', imageUrl: 'http://insecure.example/a.png' })).status).toBe(400);
    expect((await admin.call('POST', '/admin/announcements', { title: 'X' })).status).toBe(400);

    // Hidden: players no longer see it.
    expect((await admin.call('POST', `/admin/announcements/${a.id}/active`, { active: false })).status).toBe(200);
    expect((await p.call<{ announcement: null }>('GET', '/announcements/current')).body.announcement).toBeNull();
    expect((await admin.call<{ items: Announcement[] }>('GET', '/admin/announcements')).body.items[0]).toMatchObject({ id: a.id, active: false });
    expect((await admin.call('DELETE', `/admin/announcements/${a.id}`)).status).toBe(200);
    expect((await admin.call<{ items: Announcement[] }>('GET', '/admin/announcements')).body.items).toHaveLength(0);
  });
});
