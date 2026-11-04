import { existsSync, mkdirSync } from 'node:fs';
import { createServer, type Server as HttpServer } from 'node:http';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import express from 'express';
import { Server } from 'socket.io';
import { RuleError } from '../shared/engine';
import type { AckFn, ClientToServerEvents, ServerToClientEvents } from '../shared/protocol';
import { apiRouter } from './api';
import { authRouter } from './auth';
import { loadConfig, type ServerConfig } from './config';
import { connectDb, type Db } from './db';
import {
  csrfGuard,
  errorHandler,
  floodGuard,
  parseCookies,
  requireAdmin,
  securityHeaders,
  SESSION_COOKIE,
  sessionMiddleware,
  userForToken,
  type AppCtx,
} from './http';
import { MatchLedger } from './ledger';
import { createMailer, type Mailer } from './mailer';
import { legalPages } from './legal';
import { GoogleIdTokenVerifier, type GoogleVerifier } from './google';
import { GooglePlayVerifier, type PlayVerifier } from './googleplay';
import { Presence } from './presence';
import { VoiceRelay } from './voice';
import { BlockList } from './blocks';
import { announceOnline } from './online';

/** Away at least this long before friends are told you came online. */
const ONLINE_ALERT_AWAY_MS = 2 * 60_000;
/** Connections one account may hold at once. */
const MAX_SOCKETS_PER_USER = 8;
import { dbPingMs, ProcessMetrics } from './metrics';
import { cleanExpired } from './stats';
import { DEFAULT_TIMINGS, RoomManager, type ClientSocket, type Timings } from './rooms';
import { HttpError, WindowLimiter } from './security';
import { Settings } from './settings';

export interface StartOptions {
  port?: number;
  /** Overrides for environment configuration (tests). */
  config?: Partial<ServerConfig>;
  /** Use this database instead of connecting one (tests pass an in-memory PGlite). */
  db?: Db;
  timings?: Partial<Timings>;
  roll?: () => number;
  /** Skip refunding matches left open by a previous run. */
  skipRecovery?: boolean;
  /** Google Play purchase checks (tests pass a fake); default comes from the environment. */
  playVerifier?: PlayVerifier | null;
  /** Google Sign-In token checks (tests pass a fake); default comes from GOOGLE_CLIENT_IDS. */
  googleVerifier?: GoogleVerifier | null;
}

export interface RunningServer {
  http: HttpServer;
  io: Server<ClientToServerEvents, ServerToClientEvents>;
  rooms: RoomManager;
  db: Db;
  mailer: Mailer;
  settings: Settings;
  port: number;
  close: () => Promise<void>;
}

/** Wrap a socket handler: validate the payload shape, rate-limit, and turn known errors into acks. */
function handler<P>(socket: ClientSocket, fn: (p: P) => object | void | Promise<object | void>) {
  return (payload: P, ack?: AckFn<object>) => {
    const reply: AckFn<object> = typeof ack === 'function' ? ack : () => {};
    const now = Date.now();
    const bucket = (socket.data.bucket ??= { at: now, n: 0 });
    if (now - bucket.at > 1000) {
      bucket.at = now;
      bucket.n = 0;
    }
    if (++bucket.n > 20) return reply({ ok: false, error: 'Slow down' });
    if (payload === null || typeof payload !== 'object') return reply({ ok: false, error: 'Bad request' });
    Promise.resolve()
      .then(() => fn(payload))
      .then(
        (out) => reply({ ok: true, ...(out ?? {}) }),
        (err) => {
          if (err instanceof RuleError) reply({ ok: false, error: err.message });
          else if (err instanceof HttpError) reply({ ok: false, error: err.message, code: err.code });
          else {
            console.error('[server] handler error', err);
            reply({ ok: false, error: 'Server error' });
          }
        },
      );
  };
}

const ASSET_TYPES: Record<string, string> = {
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
};

/**
 * Serve the build's precompressed copies (.br, else .gz, see vite.config.ts) to browsers
 * that accept them; anything else falls through to express.static.
 */
function precompressed(dir: string): express.RequestHandler {
  return (req, res, next) => {
    if (req.method !== 'GET' && req.method !== 'HEAD') return next();
    const type = ASSET_TYPES[extname(req.path)];
    if (!type || req.path.includes('..')) return next();
    const accept = String(req.headers['accept-encoding'] ?? '');
    for (const [enc, ext] of [['br', '.br'], ['gzip', '.gz']] as const) {
      if (!new RegExp(`\\b${enc}\\b`).test(accept)) continue;
      const file = join(dir, req.path + ext);
      if (!existsSync(file)) continue;
      res.setHeader('Content-Encoding', enc);
      res.setHeader('Content-Type', type);
      res.setHeader('Vary', 'Accept-Encoding');
      res.setHeader('Cache-Control', 'public, max-age=31536000, immutable');
      return res.sendFile(file, { maxAge: '1y', immutable: true }, (err) => err && next(err));
    }
    res.setHeader('Vary', 'Accept-Encoding');
    next();
  };
}

export async function startServer(opts: StartOptions = {}): Promise<RunningServer> {
  const cfg: ServerConfig = { ...loadConfig(), ...opts.config };
  if (opts.port !== undefined) cfg.port = opts.port;

  let db = opts.db;
  if (!db) {
    if (!cfg.databaseUrl && cfg.pgliteDir !== 'memory') mkdirSync(dirname(resolve(cfg.pgliteDir)), { recursive: true });
    db = await connectDb(cfg.databaseUrl ? { url: cfg.databaseUrl } : { dataDir: cfg.pgliteDir === 'memory' ? undefined : resolve(cfg.pgliteDir) });
  }
  const settings = new Settings(db);
  await settings.load();
  const mailer = createMailer(cfg.mail);
  const presence = new Presence();
  const play = opts.playVerifier !== undefined ? opts.playVerifier : GooglePlayVerifier.fromEnv(cfg.googlePlayServiceAccount, cfg.googlePlayPackage);
  const google = opts.googleVerifier !== undefined ? opts.googleVerifier : GoogleIdTokenVerifier.fromEnv(cfg.googleClientIds);
  const blocks = new BlockList();
  await blocks.load(db);
  const ctx: AppCtx = { db, cfg, mailer, settings, presence, blocks, play, google };
  const ledger = new MatchLedger({ db, settings, presence, requireEmailVerification: cfg.requireEmailVerification });
  if (!opts.skipRecovery) {
    const rec = await ledger.recover();
    if (rec.cancelled || rec.abandoned) console.log(`[ludo] refunded ${rec.cancelled} waiting and ${rec.abandoned} interrupted matches from the previous run`);
  }

  // ---------------------------------------------------------------- HTTP
  const app = express();
  app.disable('x-powered-by');
  const trustProxy = process.env.TRUST_PROXY === 'true';
  app.set('trust proxy', trustProxy ? 1 : false);
  app.use(securityHeaders(cfg));
  legalPages(app);
  app.get('/healthz', (_req, res) => res.json({ ok: true, rooms: rooms.roomCount }));
  // For an uptime monitor: is the database answering too?
  app.get('/healthz/db', (_req, res) => {
    void dbPingMs(db!).then((ms) => res.status(ms === null ? 503 : 200).json({ ok: ms !== null, ms }));
  });
  app.use('/api', floodGuard(cfg.limits.apiPerMin, 60_000));
  // Announcements may carry an uploaded picture: the big body is only read for a signed-in
  // administrator (the parser below then sees it already parsed).
  app.use('/api/admin/announcements', csrfGuard(cfg), sessionMiddleware(ctx), requireAdmin, express.json({ limit: '3mb' }));
  app.use('/api', express.json({ limit: '16kb' }), csrfGuard(cfg), sessionMiddleware(ctx));
  app.use('/api/auth', authRouter(ctx));
  app.use('/api', apiRouter(ctx, () => rooms));
  app.use('/api', (_req, _res, next) => next(new HttpError(404, 'Not found')));
  app.use('/api', errorHandler);

  const staticDir = cfg.staticDir === null ? null : resolve(fileURLToPath(new URL('../dist', import.meta.url)));
  if (staticDir && existsSync(staticDir)) {
    app.use('/assets', precompressed(join(staticDir, 'assets')));
    app.use('/assets', express.static(join(staticDir, 'assets'), { immutable: true, maxAge: '1y', index: false }));
    app.use(express.static(staticDir, { index: false, maxAge: 0 }));
    // Single-page app: any other GET renders index.html.
    app.get('*', (_req, res) => res.sendFile(join(staticDir, 'index.html')));
  }
  app.use(errorHandler);

  const http = createServer(app);

  // ---------------------------------------------------------------- sockets
  const io = new Server<ClientToServerEvents, ServerToClientEvents>(http, {
    maxHttpBufferSize: 10_000,
    // A silently dead connection is noticed within 11 s; its reconnection countdown starts then.
    pingInterval: 5_000,
    pingTimeout: 6_000,
    // Block cross-site WebSocket hijacking: only our own pages may connect.
    allowRequest: (req, cb) => {
      const origin = req.headers.origin;
      cb(null, !origin || cfg.allowedOrigins.includes(origin));
    },
  });
  presence.attach(io as unknown as Server);
  const metrics = new ProcessMetrics();
  metrics.start();
  ctx.metrics = { sample: () => metrics.sample(), sockets: () => io.engine.clientsCount };
  const rooms = new RoomManager(io, ledger, settings, presence, { ...DEFAULT_TIMINGS, ...opts.timings }, opts.roll, db, blocks);
  const voice = new VoiceRelay(db, io, rooms);
  ctx.voice = voice;

  const socketFlood = new WindowLimiter(cfg.limits.socketsPerMin, 60_000);
  // Every socket belongs to a signed-in, non-suspended user.
  io.use((socket, next) => {
    // Behind the proxy the last address is the one the proxy itself saw (the rest can be forged).
    const forwarded = String(socket.request.headers['x-forwarded-for'] ?? '').split(',').pop()?.trim();
    if (!socketFlood.take((trustProxy && forwarded) || socket.request.socket.remoteAddress || 'ip')) return next(new Error('busy'));
    userForToken(db!, parseCookies(socket.request.headers.cookie)[SESSION_COOKIE]).then(
      (user) => {
        if (!user) return next(new Error('unauthorized'));
        if (presence.socketCount(user.id) >= MAX_SOCKETS_PER_USER) return next(new Error('busy'));
        socket.data.user = user;
        next();
      },
      () => next(new Error('unavailable')),
    );
  });

  io.on('connection', (socket: ClientSocket) => {
    const user = socket.data.user!;
    socket.join(`user:${user.id}`);
    socket.join(`session:${user.sessionId}`);
    const cameOnline = presence.comingOnline(user.id, ONLINE_ALERT_AWAY_MS);
    presence.connect(user.id, socket.id);
    // Tell online friends (a live alert in their app).
    if (cameOnline) announceOnline(db!, presence, user.id).catch((e: unknown) => console.error('[online] alert failed', e));
    // How the connection feels, for the app's indicator: an empty round trip.
    socket.on('net:ping', handler(socket, () => {}));
    socket.on('session:hello', handler(socket, (p: { results?: unknown }) => rooms.resume(socket, p.results === true)));
    socket.on('match:resultSeen', handler(socket, (p: { matchId: unknown }) => rooms.resultSeen(socket, p.matchId)));
    socket.on('room:create', handler(socket, async (p: { modeId: unknown; maxPlayers: unknown; teams?: unknown }) => ({ room: await rooms.create(socket, p.modeId, p.maxPlayers, p.teams) })));
    socket.on('room:join', handler(socket, async (p: { code: unknown }) => ({ room: await rooms.join(socket, p.code) })));
    socket.on('room:quick', handler(socket, async (p: { modeId: unknown }) => ({ room: await rooms.quick(socket, p.modeId) })));
    socket.on('room:ready', handler(socket, (p: { ready: unknown }) => rooms.setReady(socket, p.ready === true)));
    socket.on('room:addBot', handler(socket, () => rooms.addBot(socket)));
    socket.on('room:kick', handler(socket, (p: { seat: unknown }) => rooms.kick(socket, Number(p.seat))));
    socket.on('room:start', handler(socket, () => rooms.start(socket)));
    socket.on('room:leave', handler(socket, (p: { results?: unknown }) => rooms.leave(socket, p.results === true)));
    socket.on('game:roll', handler(socket, (p?: { seq?: unknown }) => rooms.rollDice(socket, p?.seq)));
    socket.on('game:move', handler(socket, (p: { token: unknown; seq?: unknown }) => rooms.move(socket, p.token, p.seq)));
    socket.on('game:rematch', handler(socket, () => rooms.rematch(socket)));
    socket.on('game:reroll', handler(socket, () => rooms.reroll(socket)));
    socket.on('playerGift:send', handler(socket, (p: { productId: unknown; toSeatId: unknown; key: unknown }) => rooms.sendGift(socket, p.productId, p.toSeatId, p.key)));
    socket.on('playerGift:seen', handler(socket, (p: { ids: unknown }) => rooms.giftsSeen(socket, p.ids)));
    socket.on('playerGift:react', handler(socket, (p: { giftId: unknown; reaction: unknown }) => rooms.reactGift(socket, p.giftId, p.reaction)));
    socket.on('chat:send', handler(socket, (p: { text: unknown; quick?: unknown; team?: unknown }) => rooms.chat(socket, p.text, p.quick, p.team)));
    socket.on('emote:send', handler(socket, (p: { id: unknown }) => rooms.emote(socket, p.id)));
    // Voice packets are fire-and-forget binary (no ack): forwarded to friends in the room.
    socket.on('voice:frame', (data: unknown) => {
      voice.packet(socket, data).catch((e: unknown) => console.error('[voice] relay failed', e));
    });
    socket.on('disconnect', () => {
      presence.disconnect(user.id, socket.id);
      rooms.disconnect(socket);
    });
  });

  return new Promise((resolveStart) => {
    http.listen(cfg.port, cfg.host, () => {
      const addr = http.address();
      const port = typeof addr === 'object' && addr ? addr.port : cfg.port;
      resolveStart({
        http,
        io,
        rooms,
        db: db!,
        mailer,
        settings,
        port,
        close: async () => {
          metrics.stop();
          await rooms.drain();
          await new Promise<void>((done) => {
            rooms.shutdown();
            io.close(() => {
              if (opts.db) done();
              else void db!.close().then(done, done);
            });
          });
        },
      });
    });
  });
}

const isMain = process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isMain) {
  // Read .env if present (Node 20.12+); real environment variables still take precedence.
  try {
    if (existsSync('.env')) process.loadEnvFile('.env');
  } catch (e) {
    console.warn('[ludo] could not read .env:', (e as Error).message);
  }
  // One bad promise must not take every running match down with the process.
  process.on('unhandledRejection', (e) => console.error('[ludo] unhandled rejection', e));
  startServer()
    .then(({ port, db, close }) => {
      // A restart: finish the match writes under way, close cleanly. Open matches are refunded on
      // the next start (MatchLedger.recover), as after any restart.
      let closing = false;
      const stop = (signal: string) => {
        if (closing) return;
        closing = true;
        console.log(`[ludo] ${signal}: shutting down`);
        const force = setTimeout(() => process.exit(1), 7000);
        force.unref();
        close().then(() => process.exit(0), () => process.exit(1));
      };
      process.on('SIGTERM', () => stop('SIGTERM'));
      process.on('SIGINT', () => stop('SIGINT'));
      // Daily: drop sessions and tokens that expired long ago.
      const clean = () => cleanExpired(db).catch((e: unknown) => console.error('[ludo] cleanup failed', e));
      setTimeout(clean, 5 * 60_000).unref();
      setInterval(clean, 24 * 3600_000).unref();

      console.log(`[ludo] server listening on http://localhost:${port} (database: ${{ postgres: 'PostgreSQL', mysql: 'MySQL', pglite: 'PGlite (local file)' }[db.kind]})`);
    })
    .catch((e) => {
      console.error('[ludo] failed to start:', e instanceof Error ? e.message : e);
      process.exit(1);
    });
}
