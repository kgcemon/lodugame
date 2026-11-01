import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * One database interface for three engines:
 *  - DATABASE_URL=postgres://...  -> a real PostgreSQL server via `pg` (e.g. Supabase, Neon, RDS, local postgres)
 *  - DATABASE_URL=mysql://...     -> MySQL 8.0.16+ via `mysql2` (e.g. CloudPanel, cPanel, RDS)
 *  - otherwise                    -> PGlite, the PostgreSQL engine compiled to WebAssembly, persisted to a
 *                                    local directory (or in memory for tests).
 * Application SQL is written in PostgreSQL syntax; the MySQL adapter translates the few
 * differences (see toMysql). PostgreSQL engines run migrations/, MySQL runs migrations-mysql/.
 */

export interface QueryResult<T> {
  rows: T[];
  rowCount: number;
}

export interface Queryable {
  query<T = Record<string, unknown>>(sql: string, params?: unknown[]): Promise<QueryResult<T>>;
  /** Run a multi-statement script without parameters. */
  exec(sql: string): Promise<void>;
}

export interface Db extends Queryable {
  kind: 'postgres' | 'pglite' | 'mysql';
  /** Run fn inside a single database transaction; rolls back if it throws. */
  tx<T>(fn: (q: Queryable) => Promise<T>): Promise<T>;
  close(): Promise<void>;
}

export interface DbOptions {
  url?: string;
  /** PGlite data directory; omit for an in-memory database. */
  dataDir?: string;
}

async function connectPostgres(url: string): Promise<Db> {
  const { default: pg } = await import('pg');
  // BIGINT columns (coins) come back as strings by default; balances fit in JS numbers.
  pg.types.setTypeParser(20, (v: string) => Number(v));
  pg.types.setTypeParser(1700, (v: string) => Number(v));
  const needsSsl = !/localhost|127\.0\.0\.1/.test(url) && !/sslmode=disable/.test(url);
  const pool = new pg.Pool({ connectionString: url, max: 10, ssl: needsSsl ? { rejectUnauthorized: false } : undefined });
  await pool.query('select 1');
  const wrap = (c: { query: (s: string, p?: unknown[]) => Promise<{ rows: unknown[]; rowCount: number | null }> }): Queryable => ({
    async query<T>(sql: string, params?: unknown[]) {
      const r = await c.query(sql, params);
      return { rows: r.rows as T[], rowCount: r.rowCount ?? 0 };
    },
    async exec(sql: string) {
      await c.query(sql);
    },
  });
  return {
    kind: 'postgres',
    ...wrap(pool),
    async tx(fn) {
      const client = await pool.connect();
      try {
        await client.query('begin');
        const out = await fn(wrap(client));
        await client.query('commit');
        return out;
      } catch (e) {
        await client.query('rollback').catch(() => {});
        throw e;
      } finally {
        client.release();
      }
    },
    close: (() => {
      let closing: Promise<void> | null = null;
      return () => (closing ??= pool.end());
    })(),
  };
}

async function connectPglite(dataDir?: string): Promise<Db> {
  const { PGlite } = await import('@electric-sql/pglite');
  // Coin amounts (BIGINT, NUMERIC) as plain numbers, matching the pg driver setup above.
  const parsers = { 20: (v: string) => Number(v), 1700: (v: string) => Number(v) };
  const pg = dataDir ? new PGlite(dataDir, { parsers }) : new PGlite({ parsers });
  await pg.waitReady;
  type Conn = {
    query: (s: string, p?: unknown[]) => Promise<{ rows: unknown[]; affectedRows?: number }>;
    exec: (s: string) => Promise<unknown>;
  };
  const wrap = (c: Conn): Queryable => ({
    async query<T>(sql: string, params?: unknown[]) {
      const r = await c.query(sql, params);
      return { rows: r.rows as T[], rowCount: r.affectedRows ?? r.rows.length };
    },
    async exec(sql: string) {
      await c.exec(sql);
    },
  });
  return {
    kind: 'pglite',
    ...wrap(pg),
    // PGlite serialises transactions internally (single connection), which also makes them safe here.
    tx: async (fn) => (await pg.transaction((t) => fn(wrap(t as unknown as Conn)))) as Awaited<ReturnType<typeof fn>>,
    close: (() => {
      let closing: Promise<void> | null = null;
      return () => (closing ??= pg.close());
    })(),
  };
}

/**
 * Rewrite PostgreSQL-flavoured application SQL for MySQL:
 *   $1..$n -> ? (params reordered/duplicated to match), `x::type` casts dropped,
 *   ilike -> like, now() -> now(6), `on conflict ... do nothing` -> insert ignore,
 *   `on conflict (...) do update set c = excluded.c` -> on duplicate key update c = values(c).
 * Double-quoted identifiers ("rank", "key") work because sessions run with ANSI_QUOTES.
 */
export function toMysql(sql: string, params: unknown[] = []): { sql: string; params: unknown[] } {
  const out: unknown[] = [];
  let s = '';
  for (let i = 0; i < sql.length; ) {
    const c = sql[i];
    if (c === "'") {
      // Copy string literals untouched ('' is an escaped quote).
      let j = i + 1;
      while (j < sql.length && !(sql[j] === "'" && sql[j + 1] !== "'")) j += sql[j] === "'" ? 2 : 1;
      s += sql.slice(i, j + 1);
      i = j + 1;
      continue;
    }
    const p = /^\$(\d+)/.exec(sql.slice(i, i + 6));
    if (p) {
      const n = Number(p[1]);
      if (n < 1 || n > params.length) throw new Error(`SQL parameter $${n} has no value`);
      out.push(params[n - 1]);
      s += '?';
      i += p[0].length;
      continue;
    }
    const cast = /^::[a-z]+(\[\])?/i.exec(sql.slice(i, i + 20));
    if (cast) {
      i += cast[0].length;
      continue;
    }
    s += c;
    i++;
  }
  s = s.replace(/\bilike\b/gi, 'like').replace(/\bnow\(\)/gi, 'now(6)');
  if (/\bon conflict\b(\s*\([^)]*\))?\s+do nothing/i.test(s)) {
    s = s.replace(/\bon conflict\b(\s*\([^)]*\))?\s+do nothing/i, '').replace(/^\s*insert into\b/i, 'insert ignore into');
  }
  s = s
    .replace(/\bon conflict\s*\([^)]*\)\s*do\s+update\s+set\b/i, 'on duplicate key update')
    .replace(/\bexcluded\.("?)(\w+)\1/gi, (_m, q: string, col: string) => `values(${q}${col}${q})`);
  return { sql: s, params: out };
}

/** Split a migration script into statements (each must end with `;` at the end of a line). */
function splitStatements(sql: string): string[] {
  return sql
    .split(/;[ \t]*(?:\r?\n|$)/)
    .map((x) => x.replace(/^\s*--.*$/gm, '').trim())
    .filter(Boolean);
}

/**
 * The MySQL migrations, adjusted for MariaDB (e.g. XAMPP): its collations predate
 * utf8mb4_0900_ai_ci and it has no regexp_like (its REGEXP is PCRE, so (?-i) keeps case).
 */
export function forMariadb(sql: string): string {
  return sql
    .replace(/utf8mb4_0900_ai_ci/g, 'utf8mb4_unicode_ci')
    .replace(/regexp_like\((\w+), '([^']*)', 'c'\)/g, `$1 regexp '(?-i)$2'`);
}

/** JSON columns: parsed objects from PostgreSQL and MySQL, but plain text from MariaDB. */
export function jsonValue<T>(v: unknown): T {
  return (typeof v === 'string' ? JSON.parse(v) : v) as T;
}

async function connectMysql(url: string): Promise<Db> {
  const mysql = await import('mysql2/promise');
  const pool = mysql.createPool({
    uri: url,
    connectionLimit: 10,
    charset: 'utf8mb4',
    // Dates in and out as UTC, DECIMAL/SUM results as numbers (like the pg type parsers above).
    timezone: 'Z',
    decimalNumbers: true,
    supportBigNumbers: true,
    bigNumberStrings: false,
  });
  // Every connection: UTC, double-quoted identifiers, strict mode, and READ COMMITTED like PostgreSQL.
  pool.pool.on('connection', (c) => {
    c.query(`set time_zone = '+00:00'`);
    c.query(
      `set sql_mode = 'ANSI_QUOTES,STRICT_TRANS_TABLES,ONLY_FULL_GROUP_BY,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION'`,
    );
    c.query('set session transaction isolation level read committed');
  });
  const [[ver]] = (await pool.query('select version() as v')) as unknown as [{ v: string }[]];
  const m = /^(\d+)\.(\d+)\.(\d+)/.exec(ver.v);
  const num = m ? Number(m[1]) * 10000 + Number(m[2]) * 100 + Number(m[3]) : 0;
  const mariadb = /mariadb/i.test(ver.v);
  if (mariadb ? num < 100402 : num < 80016) {
    await pool.end();
    throw new Error(`MySQL 8.0.16+ or MariaDB 10.4.2+ is required (server reports ${ver.v})`);
  }
  type Conn = { query: (sql: string, values?: unknown[]) => Promise<[unknown, unknown]> };
  const wrap = (c: Conn): Queryable => ({
    async query<T>(sql: string, params?: unknown[]) {
      const t = toMysql(sql, params);
      try {
        const [res] = await c.query(t.sql, t.params);
        if (Array.isArray(res)) return { rows: res as T[], rowCount: res.length };
        return { rows: [] as T[], rowCount: (res as { affectedRows: number }).affectedRows };
      } catch (e) {
        // Same code as PostgreSQL for unique violations, which callers turn into friendly 409s.
        if ((e as { code?: string }).code === 'ER_DUP_ENTRY') Object.assign(e as object, { code: '23505' });
        throw e;
      }
    },
    async exec(sql: string) {
      for (const stmt of splitStatements(mariadb ? forMariadb(sql) : sql)) await c.query(stmt);
    },
  });
  return {
    kind: 'mysql',
    ...wrap(pool),
    async tx(fn) {
      const conn = await pool.getConnection();
      try {
        await conn.beginTransaction();
        const out = await fn(wrap(conn));
        await conn.commit();
        return out;
      } catch (e) {
        await conn.rollback().catch(() => {});
        throw e;
      } finally {
        conn.release();
      }
    },
    close: (() => {
      let closing: Promise<void> | null = null;
      return () => (closing ??= pool.end());
    })(),
  };
}

export async function connectDb(opts: DbOptions = {}): Promise<Db> {
  const db = !opts.url ? await connectPglite(opts.dataDir) : /^mysql:/i.test(opts.url) ? await connectMysql(opts.url) : await connectPostgres(opts.url);
  await migrate(db);
  return db;
}

const MIGRATIONS_DIR = fileURLToPath(new URL('./migrations', import.meta.url));
const MYSQL_MIGRATIONS_DIR = fileURLToPath(new URL('./migrations-mysql', import.meta.url));

/** Apply pending SQL migrations in filename order, each in its own transaction. */
export async function migrate(db: Db, dir = db.kind === 'mysql' ? MYSQL_MIGRATIONS_DIR : MIGRATIONS_DIR): Promise<string[]> {
  await db.exec(
    db.kind === 'mysql'
      ? `create table if not exists schema_migrations (name varchar(200) primary key, applied_at datetime(6) not null default current_timestamp(6))`
      : `create table if not exists schema_migrations (name text primary key, applied_at timestamptz not null default now())`,
  );
  const done = new Set((await db.query<{ name: string }>('select name from schema_migrations')).rows.map((r) => r.name));
  const files = readdirSync(dir).filter((f) => f.endsWith('.sql')).sort();
  const applied: string[] = [];
  for (const f of files) {
    if (done.has(f)) continue;
    const sql = readFileSync(join(dir, f), 'utf8');
    await db.tx(async (q) => {
      // Run the whole file as one script, then record it.
      await q.exec(sql).catch((e) => {
        throw new Error(`Migration ${f} failed: ${(e as Error).message}`);
      });
      await q.query('insert into schema_migrations (name) values ($1)', [f]);
    });
    applied.push(f);
  }
  return applied;
}
