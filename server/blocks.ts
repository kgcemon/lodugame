import type { Queryable } from './db';

/**
 * Who has blocked whom, in memory, so chat and emotes can be filtered per recipient without a
 * database read per message and without making them wait. player_blocks is the record; the API
 * updates it and this together, and the server is one process (matches live in its memory anyway).
 */
export class BlockList {
  private byBlocker = new Map<string, Set<string>>();

  async load(db: Queryable): Promise<void> {
    const rows = (await db.query<{ blocker_id: string; blocked_id: string }>('select blocker_id, blocked_id from player_blocks')).rows;
    this.byBlocker.clear();
    for (const r of rows) this.add(r.blocker_id, r.blocked_id);
  }

  /** Has `blocker` blocked `blocked`? */
  has(blocker: string, blocked: string): boolean {
    return this.byBlocker.get(blocker)?.has(blocked) ?? false;
  }

  /** Has either of the two blocked the other? */
  between(a: string, b: string): boolean {
    return this.has(a, b) || this.has(b, a);
  }

  add(blocker: string, blocked: string): void {
    let set = this.byBlocker.get(blocker);
    if (!set) this.byBlocker.set(blocker, (set = new Set()));
    set.add(blocked);
  }

  remove(blocker: string, blocked: string): void {
    const set = this.byBlocker.get(blocker);
    if (set?.delete(blocked) && !set.size) this.byBlocker.delete(blocker);
  }

  /** The account is gone: no blocks by or of this player remain. */
  forget(userId: string): void {
    this.byBlocker.delete(userId);
    for (const blocker of [...this.byBlocker.keys()]) this.remove(blocker, userId);
  }
}
