import { io as connect, type Socket } from 'socket.io-client';
import { startServer, type RunningServer, type StartOptions } from '../server/index';
import type { Ack, ClientToServerEvents, GameUpdate, ServerToClientEvents } from '../shared/protocol';

/** Boots the real server on an in-memory PGlite database and drives it like a browser would. */

export type C = Socket<ServerToClientEvents, ClientToServerEvents>;

export interface TestServer {
  server: RunningServer;
  url: string;
  sockets: C[];
  stop: () => Promise<void>;
}

/**
 * With TEST_MYSQL_URL (e.g. mysql://root@127.0.0.1:3306/) each server gets its own fresh
 * MySQL database, dropped on stop, instead of an in-memory PGlite one.
 */
const MYSQL_URL = process.env.TEST_MYSQL_URL;
let mysqlDbs = 0;

async function mysqlAdmin(sql: string): Promise<void> {
  const mysql = await import('mysql2/promise');
  const c = await mysql.createConnection(MYSQL_URL!);
  try {
    await c.query(sql);
  } finally {
    await c.end();
  }
}

export async function bootServer(opts: StartOptions = {}): Promise<TestServer> {
  let databaseUrl: string | undefined;
  let dbName: string | undefined;
  if (MYSQL_URL) {
    dbName = `ludo_test_${process.pid}_${++mysqlDbs}`;
    await mysqlAdmin(`create database ${dbName} character set utf8mb4`);
    const u = new URL(MYSQL_URL);
    u.pathname = `/${dbName}`;
    databaseUrl = u.toString();
  }
  const server = await startServer({
    port: 0,
    skipRecovery: true,
    ...opts,
    config: {
      databaseUrl,
      pgliteDir: 'memory',
      staticDir: null,
      secureCookies: false,
      mail: { mode: 'memory', from: 'test@example.com' },
      appBaseUrl: 'http://localhost:5173',
      allowedOrigins: ['http://localhost:5173'],
      adminEmails: ['admin@example.com'],
      authLimits: { registrationsPer10Min: 10_000, loginsPerMin: 10_000 },
      limits: { apiPerMin: 1_000_000, socketsPerMin: 1_000_000 },
      ...opts.config,
    },
    timings: { turnMs: 60_000, dropMs: 400, botThinkMs: 5, animScale: 0, lobbyGraceMs: 200, emptyRoomMs: 500, autoMoveMs: 60_000, autoMoveRerollMs: 60_000, ...opts.timings },
  });
  const t: TestServer = {
    server,
    url: `http://localhost:${server.port}`,
    sockets: [],
    stop: async () => {
      t.sockets.forEach((s) => s.close());
      // Let refunds/settlements triggered by those disconnects finish before the database closes.
      await new Promise((r) => setTimeout(r, 400));
      await server.close();
      if (dbName) await mysqlAdmin(`drop database if exists ${dbName}`);
    },
  };
  return t;
}

export interface Api {
  cookie: string;
  call: <T = Record<string, unknown>>(method: string, path: string, body?: unknown) => Promise<{ status: number; body: T & { ok: boolean; error?: string; code?: string } }>;
}

/** A cookie-jar HTTP client that behaves like the browser app (sends the CSRF header). */
export function api(t: TestServer, cookie = ''): Api {
  const a: Api = {
    cookie,
    async call(method, path, body) {
      const res = await fetch(`${t.url}/api${path}`, {
        method,
        headers: {
          'content-type': 'application/json',
          'x-requested-with': 'ludo3d',
          ...(a.cookie ? { cookie: a.cookie } : {}),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const set = res.headers.get('set-cookie');
      if (set) {
        const m = /ludo_sid=([^;]*)/.exec(set);
        if (m) a.cookie = m[1] ? `ludo_sid=${m[1]}` : '';
      }
      return { status: res.status, body: (await res.json()) as never };
    },
  };
  return a;
}

let seq = 0;
export async function registerUser(t: TestServer, name = 'Player', email?: string): Promise<Api & { email: string; username: string; publicId: string; id: string }> {
  const a = api(t);
  const addr = email ?? `user${++seq}_${Date.now()}@example.com`;
  const r = await a.call('POST', '/auth/register', { fullName: name, email: addr, password: 'goodPass123', confirmPassword: 'goodPass123' });
  if (r.status !== 201) throw new Error(`register failed: ${JSON.stringify(r.body)}`);
  const me = await a.call<{ user: { username: string; publicId: string; id: string } }>('GET', '/auth/me');
  return Object.assign(a, { email: addr, username: me.body.user.username, publicId: me.body.user.publicId, id: me.body.user.id });
}

export function socketFor(t: TestServer, cookie: string): Promise<C> {
  const c: C = connect(t.url, { transports: ['websocket'], forceNew: true, reconnection: false, extraHeaders: { cookie } });
  t.sockets.push(c);
  return new Promise((resolve, reject) => {
    c.on('connect', () => resolve(c));
    c.on('connect_error', (e) => reject(e));
  });
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function emit<T extends object = object>(c: C, ev: keyof ClientToServerEvents, payload: any = {}): Promise<Ack<T>> {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return new Promise((r) => (c.emit as any)(ev, payload, r));
}

export function next<T>(c: C, ev: keyof ServerToClientEvents, pred: (x: T) => boolean = () => true, ms = 5000): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`timeout waiting for ${ev}`)), ms);
    const h = (x: T) => {
      if (pred(x)) {
        clearTimeout(timer);
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        c.off(ev as any, h as any);
        resolve(x);
      }
    };
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    c.on(ev as any, h as any);
  });
}

export const nextUpdate = (c: C, pred: (u: GameUpdate) => boolean = () => true) => next<GameUpdate>(c, 'game:update', pred);
export const tick = (ms = 60) => new Promise((r) => setTimeout(r, ms));
