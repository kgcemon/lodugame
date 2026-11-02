import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FriendCard } from '../shared/protocol';
import { bootServer, next, registerUser, socketFor, tick, type TestServer } from './helpers';

/** "Your friend is online" alerts. */

let t: TestServer;
beforeAll(async () => {
  t = await bootServer();
});
afterAll(() => t.stop());

type Online = { player: FriendCard; played: number; best: boolean };

describe('friend online alerts', () => {
  it('tells online friends (not strangers) when a player comes online, but not on a quick reconnect', async () => {
    const ua = await registerUser(t, 'Comer');
    const ub = await registerUser(t, 'Waiter');
    const uc = await registerUser(t, 'Nobody');
    const r = await ua.call<{ requestId: string }>('POST', '/friends/requests', { player: ub.publicId });
    await ub.call('POST', `/friends/requests/${r.body.requestId}/accept`);
    const [b, c] = await Promise.all([socketFor(t, ub.cookie), socketFor(t, uc.cookie)]);
    let strangerHeard = false;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    c.on('friend:online' as any, () => (strangerHeard = true));

    const alert = next<Online>(b, 'friend:online');
    const a = await socketFor(t, ua.cookie);
    expect(await alert).toMatchObject({ player: { username: ua.username, publicId: ua.publicId }, played: 0, best: false });
    expect(strangerHeard).toBe(false);

    // Dropped and straight back: no second alert.
    let again = 0;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    b.on('friend:online' as any, () => again++);
    a.disconnect();
    await tick(100);
    await socketFor(t, ua.cookie);
    await tick(200);
    expect(again).toBe(0);
  });
});
