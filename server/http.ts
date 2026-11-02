import type { NextFunction, Request, RequestHandler, Response } from 'express';
import type { ServerConfig } from './config';
import type { Db } from './db';
import type { Mailer } from './mailer';
import type { Presence } from './presence';
import type { GoogleVerifier } from './google';
import type { PlayVerifier } from './googleplay';
import { HttpError, WindowLimiter, hashToken } from './security';
import type { Settings } from './settings';

/** Shared context and middleware for the HTTP API. */

export interface AppCtx {
  db: Db;
  cfg: ServerConfig;
  mailer: Mailer;
  settings: Settings;
  presence: Presence;
  /** Google Play purchase verification; null when not configured. */
  play: PlayVerifier | null;
  /** Google Sign-In token checks; null when not configured. */
  google: GoogleVerifier | null;
}

export interface AuthUser {
  id: string;
  email: string;
  role: 'player' | 'admin';
  emailVerified: boolean;
  fullName: string;
  username: string;
  publicId: string;
  avatarHue: number;
  /** Profile picture (from Google); null shows the initial on the hue. */
  avatarUrl: string | null;
  sessionId: string;
}

declare module 'express-serve-static-core' {
  interface Request {
    user?: AuthUser;
  }
}

export const SESSION_COOKIE = 'ludo_sid';

export function parseCookies(header: string | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  for (const part of (header ?? '').split(';')) {
    const i = part.indexOf('=');
    if (i < 0) continue;
    const k = part.slice(0, i).trim();
    if (!k) continue;
    try {
      out[k] = decodeURIComponent(part.slice(i + 1).trim());
    } catch {
      /* ignore malformed cookie */
    }
  }
  return out;
}

/** Resolve a raw session token to the signed-in user (null if missing, expired, revoked or suspended). */
export async function userForToken(db: Db, token: string | undefined): Promise<AuthUser | null> {
  if (!token || token.length > 200) return null;
  const r = await db.query<{
    session_id: string; last_seen_at: string; id: string; email: string; role: 'player' | 'admin';
    email_verified_at: string | null; full_name: string; username: string; public_id: string; avatar_hue: number; avatar_url: string | null;
  }>(
    `select s.id as session_id, s.last_seen_at, u.id, u.email, u.role, u.email_verified_at,
            p.full_name, p.username, p.public_id, p.avatar_hue, p.avatar_url
     from sessions s join users u on u.id = s.user_id join profiles p on p.user_id = u.id
     where s.token_hash = $1 and s.revoked_at is null and s.expires_at > now() and u.suspended_at is null`,
    [hashToken(token)],
  );
  const row = r.rows[0];
  if (!row) return null;
  if (Date.now() - new Date(row.last_seen_at).getTime() > 5 * 60_000) {
    await db.query('update sessions set last_seen_at = now() where id = $1', [row.session_id]);
  }
  return {
    id: row.id,
    email: row.email,
    role: row.role,
    emailVerified: !!row.email_verified_at,
    fullName: row.full_name,
    username: row.username,
    publicId: row.public_id,
    avatarHue: row.avatar_hue,
    avatarUrl: row.avatar_url ?? null,
    sessionId: row.session_id,
  };
}

export function setSessionCookie(res: Response, cfg: ServerConfig, token: string, maxAgeMs: number | null): void {
  res.cookie(SESSION_COOKIE, token, {
    httpOnly: true,
    secure: cfg.secureCookies,
    sameSite: 'lax',
    path: '/',
    ...(maxAgeMs ? { maxAge: maxAgeMs } : {}),
  });
}

export function clearSessionCookie(res: Response, cfg: ServerConfig): void {
  res.clearCookie(SESSION_COOKIE, { httpOnly: true, secure: cfg.secureCookies, sameSite: 'lax', path: '/' });
}

/** Wrap async handlers so thrown errors reach the error middleware. */
export function h(fn: (req: Request, res: Response) => Promise<unknown>): RequestHandler {
  return (req, res, next) => {
    fn(req, res).then(
      (body) => {
        if (!res.headersSent) res.json(body ?? { ok: true });
      },
      next,
    );
  };
}

export function securityHeaders(cfg: ServerConfig): RequestHandler {
  return (_req, res, next) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
    res.setHeader('X-Frame-Options', 'DENY');
    res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
    res.setHeader(
      'Content-Security-Policy',
      "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https:; " +
        "connect-src 'self' ws: wss:; font-src 'self' data:; worker-src 'self' blob:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
    );
    if (cfg.secureCookies) res.setHeader('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
    next();
  };
}

/**
 * CSRF defence for cookie-authenticated requests: state-changing calls must be
 * JSON, carry a custom header (which cross-site forms cannot send without a
 * CORS preflight we never grant) and, when the browser sends one, an allowed Origin.
 */
export function csrfGuard(cfg: ServerConfig): RequestHandler {
  return (req, _res, next) => {
    if (req.method === 'GET' || req.method === 'HEAD' || req.method === 'OPTIONS') return next();
    if (req.get('x-requested-with') !== 'ludo3d') return next(new HttpError(403, 'Request blocked'));
    const origin = req.get('origin');
    if (origin && !cfg.allowedOrigins.includes(origin)) return next(new HttpError(403, 'Request blocked'));
    if (!req.is('application/json') && req.headers['content-length'] && req.headers['content-length'] !== '0') {
      return next(new HttpError(415, 'JSON required'));
    }
    next();
  };
}

export function rateLimit(max: number, windowMs: number, keyFn: (req: Request) => string = (r) => r.ip ?? 'ip'): RequestHandler {
  const l = new WindowLimiter(max, windowMs);
  return (req, _res, next) => {
    if (!l.take(`${req.path}|${keyFn(req)}`)) return next(new HttpError(429, 'Too many requests — please wait a moment'));
    next();
  };
}

export function sessionMiddleware(ctx: AppCtx): RequestHandler {
  return (req, _res, next) => {
    userForToken(ctx.db, parseCookies(req.headers.cookie)[SESSION_COOKIE]).then((u) => {
      if (u) req.user = u;
      next();
    }, next);
  };
}

export const requireUser: RequestHandler = (req, _res, next) => {
  if (!req.user) return next(new HttpError(401, 'Please sign in'));
  next();
};

export const requireAdmin: RequestHandler = (req, _res, next) => {
  if (!req.user) return next(new HttpError(401, 'Please sign in'));
  if (req.user.role !== 'admin') return next(new HttpError(403, 'Administrators only'));
  next();
};

export function errorHandler(err: unknown, _req: Request, res: Response, _next: NextFunction): void {
  if (err instanceof HttpError) {
    res.status(err.status).json({ ok: false, error: err.message, code: err.code });
    return;
  }
  const e = err as { type?: string; status?: number };
  if (e?.type === 'entity.parse.failed' || e?.type === 'entity.too.large') {
    res.status(e.status ?? 400).json({ ok: false, error: 'Invalid request body' });
    return;
  }
  console.error('[api] unexpected error', err);
  // Never leak internals (SQL, stack traces) to the client.
  res.status(500).json({ ok: false, error: 'Something went wrong. Please try again.' });
}
