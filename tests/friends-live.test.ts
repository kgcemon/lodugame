import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FriendCard } from '../shared/protocol';
import { bootServer, next, registerUser, socketFor, type TestServer } from './helpers';

/** Friend requests: live popups both ways, cancelling, and the two-requests limit. */

let t: TestServer;
beforeAll(async () => {
  t = await bootServer();
});
afterAll(() => t.stop());

type Live = { id: string; player: FriendCard };
type Sent = { requestId: string; sendsLeft: number };
type Friendship = { isFriend: boolean; pending: string | null; requestId: string | null; sendsLeft: number };

describe('live friend requests', () => {
  it('pops up for the other player; accepting celebrates on both sides', async () => {
    const a = await registerUser(t, 'Asker');
    const b = await registerUser(t, 'Bee');
    const sa = await socketFor(t, a.cookie);
    const sb = await socketFor(t, b.cookie);

    const asked = next<Live>(sb, 'friend:request');
    const r = await a.call<Sent>('POST', '/friends/requests', { player: b.publicId });
    expect(r.body.sendsLeft).toBe(1);
    const live = await asked;
    expect(live).toMatchObject({ id: r.body.requestId, player: { username: a.username, publicId: a.publicId } });

    const cheers = next<Live>(sa, 'friend:accepted');
    expect((await b.call('POST', `/friends/requests/${live.id}/accept`)).status).toBe(200);
    expect((await cheers).player.username).toBe(b.username);
    const prof = await a.call<{ player: { friendship: Friendship } }>('GET', `/players/${b.publicId}`);
    expect(prof.body.player.friendship.isFriend).toBe(true);
  });

  it('declining tells the sender; cancelling closes the popup; two requests at most', async () => {
    const a = await registerUser(t, 'Sender');
    const b = await registerUser(t, 'Picky');
    const sa = await socketFor(t, a.cookie);
    const sb = await socketFor(t, b.cookie);

    const first = await a.call<Sent>('POST', '/friends/requests', { player: b.publicId });
    let prof = await a.call<{ player: { friendship: Friendship } }>('GET', `/players/${b.publicId}`);
    expect(prof.body.player.friendship).toMatchObject({ pending: 'outgoing', requestId: first.body.requestId, sendsLeft: 1 });

    const sad = next<Live>(sa, 'friend:rejected');
    await b.call('POST', `/friends/requests/${first.body.requestId}/reject`);
    expect((await sad).player.username).toBe(b.username);

    const second = await a.call<Sent>('POST', '/friends/requests', { player: b.publicId });
    expect(second.body.sendsLeft).toBe(0);
    const withdrawn = next<{ id: string }>(sb, 'friend:cancelled');
    const c = await a.call<{ friendship: Friendship }>('POST', `/friends/requests/${second.body.requestId}/cancel`);
    expect(c.body.friendship).toMatchObject({ pending: null, sendsLeft: 0 });
    expect((await withdrawn).id).toBe(second.body.requestId);

    const third = await a.call<{ code: string }>('POST', '/friends/requests', { player: b.publicId });
    expect(third.status).toBe(429);
    expect(third.body.code).toBe('FRIEND_REQUEST_LIMIT');
    // The other way round is still open: their request to you is their own two.
    expect((await b.call('POST', '/friends/requests', { player: a.publicId })).status).toBe(200);
  });
});
