import type { Db, Queryable } from './db';

/**
 * Player statistics without scanning every match on every look at a profile. One player's numbers
 * come from their own rows (indexed); their place comes from a small table of "how many players
 * have how many wins", read once and kept until a match is settled or a minute has passed.
 */

const TABLE_TTL_MS = 60_000;

/**
 * May a result that took `readMs` to read be replaced yet? A quick read always may. A slow one (a
 * large database) waits long enough to keep this to about 3% of the database's time.
 */
export function mayRefresh(readMs: number, ageMs: number): boolean {
  return readMs < 50 || ageMs > readMs * 30;
}

const SETTLED = `m.status = 'completed' and mp.status in ('finished', 'forfeited')`;

export interface PlayerStats {
  matches: number;
  wins: number;
  losses: number;
  winRate: number;
  rank: number;
  seasonPoints: number;
}

class WinTable {
  private rows: { wins: number; players: number }[] | null = null;
  private at = 0;
  private readMs = 0;
  private dirty = false;
  private loading: Promise<{ wins: number; players: number }[]> | null = null;
  /** Bumped when a match settles: anything derived from older results is out of date. */
  version = 0;

  constructor(private db: Queryable) {}

  invalidate(): void {
    this.version++;
    this.dirty = true;
  }

  private async load(): Promise<{ wins: number; players: number }[]> {
    const age = Date.now() - this.at;
    if (this.rows) {
      const due = (this.dirty || age > TABLE_TTL_MS) && mayRefresh(this.readMs, age);
      if (!due) return this.rows;
      // A slow table: answer from the one we have and refresh behind it.
      if (this.readMs >= 50) {
        void this.read().catch((e: unknown) => console.error('[stats] win table failed', e));
        return this.rows;
      }
    }
    return this.read();
  }

  private read(): Promise<{ wins: number; players: number }[]> {
    // Everyone waiting for it shares one read.
    if (this.loading) return this.loading;
    const version = this.version;
    const started = Date.now();
    this.loading = this.db
      .query<{ wins: number | string; players: number | string }>(
        `select s.wins, count(*) as players from (
           select mp.user_id, sum(case when mp.won then 1 else 0 end) as wins
           from match_players mp
           join matches m on m.id = mp.match_id
           join users u on u.id = mp.user_id and u.suspended_at is null
           where ${SETTLED}
           group by mp.user_id
         ) s group by s.wins`,
      )
      .then((r) => {
        const rows = r.rows.map((x) => ({ wins: Number(x.wins), players: Number(x.players) }));
        this.rows = rows;
        this.at = Date.now();
        this.readMs = this.at - started;
        if (version === this.version) this.dirty = false;
        return rows;
      })
      .finally(() => {
        this.loading = null;
      });
    return this.loading;
  }

  /** Place on the wins leaderboard: one more than the players with more wins. */
  async rankFor(wins: number): Promise<number> {
    const rows = await this.load();
    return 1 + rows.reduce((n, r) => (r.wins > wins ? n + r.players : n), 0);
  }
}

const tables = new WeakMap<object, WinTable>();

export function winTable(db: Queryable): WinTable {
  let t = tables.get(db);
  if (!t) tables.set(db, (t = new WinTable(db)));
  return t;
}

export async function playerStats(db: Db | Queryable, userId: string, season: Date): Promise<PlayerStats> {
  const s = (await db.query<{ matches: number | string; wins: number | string | null; season_matches: number | string | null; season_wins: number | string | null }>(
    `select count(*) as matches,
            sum(case when mp.won then 1 else 0 end) as wins,
            sum(case when m.ended_at >= $2 then 1 else 0 end) as season_matches,
            sum(case when mp.won and m.ended_at >= $2 then 1 else 0 end) as season_wins
     from match_players mp join matches m on m.id = mp.match_id
     where mp.user_id = $1 and ${SETTLED}`,
    [userId, season],
  )).rows[0];
  const matches = Number(s?.matches ?? 0);
  const wins = Number(s?.wins ?? 0);
  return {
    matches,
    wins,
    losses: matches - wins,
    winRate: matches ? Math.round((1000 * wins) / matches) / 10 : 0,
    rank: await winTable(db).rankFor(wins),
    seasonPoints: Number(s?.season_wins ?? 0) * 3 + Number(s?.season_matches ?? 0),
  };
}

/** Delete what has expired and can never be used again. Nothing a player can see, nothing in the ledgers. */
export async function cleanExpired(db: Queryable): Promise<void> {
  const monthAgo = new Date(Date.now() - 30 * 24 * 3600_000);
  await db.query('delete from sessions where expires_at < $1 or (revoked_at is not null and revoked_at < $1)', [monthAgo]);
  await db.query('delete from auth_tokens where expires_at < $1', [monthAgo]);
}
