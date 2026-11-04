import { randomUUID } from 'node:crypto';
import type { Server } from 'socket.io';
import type { Db } from './db';

/**
 * Who is online (has at least one authenticated socket) and which room they
 * are in, plus helpers to push events to all of a user's tabs.
 */
export class Presence {
  private io: Server | null = null;
  private sockets = new Map<string, Set<string>>();
  private rooms = new Map<string, string>();
  /** When each player last went fully offline (to skip "online" alerts for quick reconnects). */
  private offlineAt = new Map<string, number>();

  attach(io: Server): void {
    this.io = io;
  }

  connect(userId: string, socketId: string): void {
    let s = this.sockets.get(userId);
    if (!s) this.sockets.set(userId, (s = new Set()));
    s.add(socketId);
  }

  disconnect(userId: string, socketId: string): void {
    const s = this.sockets.get(userId);
    if (!s) return;
    s.delete(socketId);
    if (!s.size) {
      this.sockets.delete(userId);
      this.offlineAt.set(userId, Date.now());
      if (this.offlineAt.size > 50_000) this.offlineAt.clear();
    }
  }

  /**
   * Call before connect(): true when this socket brings the player online after being away
   * for at least `awayMs` (a dropped connection that comes straight back doesn't count).
   */
  comingOnline(userId: string, awayMs: number): boolean {
    if (this.sockets.has(userId)) return false;
    const off = this.offlineAt.get(userId);
    return off === undefined || Date.now() - off >= awayMs;
  }

  isOnline(userId: string): boolean {
    return this.sockets.has(userId);
  }

  setRoom(userId: string, code: string | null): void {
    if (code) this.rooms.set(userId, code);
    else this.rooms.delete(userId);
  }

  roomOf(userId: string): string | null {
    return this.rooms.get(userId) ?? null;
  }

  emit(userId: string, event: string, payload: unknown): void {
    this.io?.to(`user:${userId}`).emit(event, payload);
  }

  socketCount(userId: string): number {
    return this.sockets.get(userId)?.size ?? 0;
  }

  /** Force-disconnect every socket of a user (suspension, sessions revoked). */
  kick(userId: string): void {
    this.io?.in(`user:${userId}`).disconnectSockets(true);
  }

  /** Force-disconnect the sockets opened with one session (sign-out). */
  kickSession(sessionId: string): void {
    this.io?.in(`session:${sessionId}`).disconnectSockets(true);
  }
}

export type NotificationType = 'friend_request' | 'friend_accepted' | 'room_invite' | 'match_result' | 'wallet' | 'system';

/** Store a notification and push it live to the user if they're online. */
export async function notify(db: Db, presence: Presence, userId: string, type: NotificationType, payload: Record<string, unknown>): Promise<void> {
  const id = randomUUID();
  await db.query(`insert into notifications (id, user_id, type, payload) values ($1, $2, $3, $4::jsonb)`, [id, userId, type, JSON.stringify(payload)]);
  const r = await db.query<{ created_at: string }>('select created_at from notifications where id = $1', [id]);
  presence.emit(userId, 'notify', { id, type, payload, createdAt: r.rows[0].created_at, readAt: null });
}
