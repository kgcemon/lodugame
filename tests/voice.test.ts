import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { RoomView } from '../shared/protocol';
import { bootServer, emit, registerUser, socketFor, tick, type C, type TestServer } from './helpers';

/** Voice chat: packets reach friends in the same room, and nobody else. */

let t: TestServer;
beforeAll(async () => {
  t = await bootServer({ timings: { botThinkMs: 60_000 } });
});
afterAll(() => t.stop());

function heard(c: C): { seatId: string; bytes: number[] }[] {
  const got: { seatId: string; bytes: number[] }[] = [];
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  c.on('voice:frame' as any, (seatId: string, data: ArrayBuffer | Uint8Array) => got.push({ seatId, bytes: [...new Uint8Array(data)] }));
  return got;
}

describe('voice chat', () => {
  it('forwards a voice packet only to friends seated in the same room', async () => {
    const ua = await registerUser(t, 'Talker');
    const ub = await registerUser(t, 'Friend');
    const uc = await registerUser(t, 'Stranger');
    const ud = await registerUser(t, 'Elsewhere');
    // Talker and Friend are friends; so are Talker and Elsewhere (but in another room).
    for (const other of [ub, ud]) {
      const r = await ua.call<{ requestId: string }>('POST', '/friends/requests', { player: other.publicId });
      await other.call('POST', `/friends/requests/${r.body.requestId}/accept`);
    }
    const [a, b, c, d] = await Promise.all([ua, ub, uc, ud].map((u) => socketFor(t, u.cookie)));
    const created = await emit<{ room: RoomView }>(a, 'room:create', { modeId: 'friendly', maxPlayers: 3 });
    const code = created.ok ? created.room.code : '';
    await emit(b, 'room:join', { code });
    await emit(c, 'room:join', { code });
    await emit(d, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    const [gotB, gotC, gotD] = [heard(b), heard(c), heard(d)];

    const packet = Buffer.from([1, 2, 3, 4, 5, 6]);
    a.emit('voice:frame', packet);
    await tick(150);
    expect(gotB).toEqual([{ seatId: created.ok ? created.room.seats[0].id : '', bytes: [1, 2, 3, 4, 5, 6] }]);
    expect(gotC).toEqual([]);
    expect(gotD).toEqual([]);

    // Oversized or empty packets are dropped.
    a.emit('voice:frame', Buffer.alloc(3000));
    a.emit('voice:frame', Buffer.alloc(0));
    await tick(150);
    expect(gotB).toHaveLength(1);
  });

  it('strangers who become friends mid-match can talk at once', async () => {
    const ua = await registerUser(t, 'Random One');
    const ub = await registerUser(t, 'Random Two');
    const [a, b] = await Promise.all([socketFor(t, ua.cookie), socketFor(t, ub.cookie)]);
    const created = await emit<{ room: RoomView }>(a, 'room:create', { modeId: 'friendly', maxPlayers: 2 });
    await emit(b, 'room:join', { code: created.ok ? created.room.code : '' });
    const gotB = heard(b);

    a.emit('voice:frame', Buffer.from([9, 9, 9, 9]));
    await tick(150);
    expect(gotB).toEqual([]);

    const r = await ua.call<{ requestId: string }>('POST', '/friends/requests', { player: ub.publicId });
    await ub.call('POST', `/friends/requests/${r.body.requestId}/accept`);
    a.emit('voice:frame', Buffer.from([7, 7, 7, 7]));
    await tick(150);
    expect(gotB.map((x) => x.bytes)).toEqual([[7, 7, 7, 7]]);

    // Unfriended: silence again straight away.
    await ub.call('DELETE', `/friends/${ua.publicId}`);
    a.emit('voice:frame', Buffer.from([5, 5, 5, 5]));
    await tick(150);
    expect(gotB).toHaveLength(1);
  });
});
