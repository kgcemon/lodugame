import type { Db } from './db';
import type { ClientSocket, IO, RoomManager } from './rooms';

/**
 * Voice chat between friends in a room. Phones send small packets of compressed audio
 * (AAC, a few frames per packet); the server only forwards them, and only to the other
 * players in the same room who are the sender's friends (strangers never hear you).
 * Muting is up to each phone: it stops sending (mic off) or stops playing someone.
 */

/** Largest packet accepted (a tenth of a second of 24 kbps AAC is ~300 bytes). */
export const MAX_VOICE_PACKET = 2000;
/** Packets per second one socket may send (phones send about ten). */
const PACKETS_PER_SECOND = 25;
/** How long a player's friend list is trusted before it is read again. */
const FRIENDS_TTL_MS = 20_000;

export class VoiceRelay {
  private friends = new Map<string, { at: number; ids: Set<string> }>();
  private buckets = new WeakMap<object, { at: number; n: number }>();

  constructor(
    private db: Db,
    private io: IO,
    private rooms: RoomManager,
  ) {}

  private async friendsOf(userId: string): Promise<Set<string>> {
    const hit = this.friends.get(userId);
    if (hit && Date.now() - hit.at < FRIENDS_TTL_MS) return hit.ids;
    const r = await this.db.query<{ other: string }>(
      'select case when user_a = $1 then user_b else user_a end as other from friendships where user_a = $1 or user_b = $1',
      [userId],
    );
    const ids = new Set(r.rows.map((x) => x.other));
    this.friends.set(userId, { at: Date.now(), ids });
    if (this.friends.size > 5000) this.friends.clear();
    return ids;
  }

  /** Forget cached friend lists (after friends change). */
  forget(...userIds: string[]): void {
    for (const id of userIds) this.friends.delete(id);
  }

  /** A voice packet from this socket: forward it to its friends in the room. */
  async packet(socket: ClientSocket, data: unknown): Promise<void> {
    const buf = Buffer.isBuffer(data) ? data : data instanceof ArrayBuffer ? Buffer.from(data) : null;
    if (!buf || buf.length < 4 || buf.length > MAX_VOICE_PACKET) return;
    const now = Date.now();
    let b = this.buckets.get(socket);
    if (!b || now - b.at > 1000) this.buckets.set(socket, (b = { at: now, n: 0 }));
    if (++b.n > PACKETS_PER_SECOND) return;
    const t = this.rooms.voicePeers(socket);
    if (!t || !t.peers.length) return;
    const friends = await this.friendsOf(t.playerId);
    for (const p of t.peers) {
      if (friends.has(p.playerId)) this.io.to(p.socketId).emit('voice:frame', t.seatId, buf);
    }
  }
}
