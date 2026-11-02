import { randomUUID } from 'node:crypto';
import { Router, type Request, type Response } from 'express';
import type { Queryable } from './db';
import {
  clearSessionCookie,
  h,
  rateLimit,
  requireUser,
  setSessionCookie,
  type AppCtx,
  type AuthUser,
} from './http';
import * as v from './security';
import { HttpError, hashToken, newPublicId, newToken } from './security';
import { InvalidGoogleToken } from './google';
import { countryForIp } from './country';
import { applyWalletTx, walletSummary } from './wallet';

/**
 * Accounts and sessions. Players sign in with Google (the app) or an email and password
 * (the website, admins). Passwords are scrypt-hashed; sessions are random tokens kept only
 * as SHA-256 hashes, sent in an HttpOnly SameSite cookie.
 */

/** password_hash of an account made with Google: matches no password. */
const NO_PASSWORD = 'google';
/** password_hash of a guest account: matches no password either. */
const GUEST_PASSWORD = 'guest';

const REMEMBER_MS = 30 * 24 * 3600_000;
const SHORT_MS = 24 * 3600_000;
const VERIFY_MS = 48 * 3600_000;
const RESET_MS = 3600_000;
/** A guest has no other way back in, so their session lasts (about) forever. */
const GUEST_MS = 10 * 365 * 24 * 3600_000;

export function publicUser(u: AuthUser) {
  return {
    id: u.id,
    email: u.email,
    role: u.role,
    emailVerified: u.emailVerified,
    fullName: u.fullName,
    username: u.username,
    publicId: u.publicId,
    avatarHue: u.avatarHue,
    avatarUrl: u.avatarUrl,
    country: u.country,
    guest: u.isGuest,
  };
}

function usernameBase(name: string): string {
  const ascii = name.normalize('NFKD').replace(/[^A-Za-z0-9]/g, '');
  return (ascii || 'player').slice(0, 14);
}

/**
 * A new account with its profile (unique username and public id), wallet and welcome
 * bonus, all in the caller's transaction. Returns the user id.
 */
async function createAccount(
  q: Queryable,
  a: { email: string; fullName: string; passwordHash: string; role: string; welcomeBonus: number; googleSub?: string; guest?: boolean },
): Promise<string> {
  const id = randomUUID();
  await q.query(
    `insert into users (id, email, password_hash, role, google_sub, email_verified_at, is_guest) values ($1, $2, $3, $4, $5, $6, $7)`,
    [id, a.email, a.passwordHash, a.role, a.googleSub ?? null, a.googleSub ? new Date() : null, !!a.guest],
  );
  // Unique username/public id: retry on the rare collision.
  for (let i = 0; ; i++) {
    const uname = `${usernameBase(a.fullName)}${Math.floor(1000 + Math.random() * 9000)}`;
    const ok = await q.query(
      `insert into profiles (user_id, full_name, username, public_id) values ($1, $2, $3, $4)
       on conflict do nothing`,
      [id, a.fullName, uname, newPublicId()],
    );
    if (ok.rowCount) break;
    if (i > 8) throw new Error('Could not allocate a username');
  }
  await q.query('insert into wallets (user_id) values ($1)', [id]);
  // Welcome bonus: same transaction as the account, keyed so it can only ever be granted once.
  if (a.welcomeBonus > 0) {
    await applyWalletTx(q, { userId: id, type: 'welcome_bonus', amount: a.welcomeBonus, key: 'welcome_bonus', note: 'Welcome bonus' });
  }
  return id;
}

/** Keep the player's country in step with the IP they play from (unknown IPs change nothing). */
export async function refreshCountry(q: Queryable, userId: string, ip: string | undefined, current?: string | null): Promise<void> {
  const country = countryForIp(ip);
  if (!country || country === current) return;
  await q.query('update profiles set country = $2 where user_id = $1', [userId, country]);
}

async function createSession(ctx: AppCtx, req: Request, res: Response, userId: string, remember: boolean, ms = remember ? REMEMBER_MS : SHORT_MS): Promise<void> {
  const token = newToken();
  await ctx.db.query(
    `insert into sessions (user_id, token_hash, expires_at, ip, user_agent) values ($1, $2, $3, $4, $5)`,
    [userId, hashToken(token), new Date(Date.now() + ms), req.ip ?? null, (req.get('user-agent') ?? '').slice(0, 300)],
  );
  setSessionCookie(res, ctx.cfg, token, remember ? ms : null);
  await refreshCountry(ctx.db, userId, req.ip).catch((e) => console.error('[country] lookup failed', e));
}

async function issueToken(q: Queryable, userId: string, kind: 'verify_email' | 'reset_password', ms: number): Promise<string> {
  const token = newToken();
  // Older unused tokens of the same kind stop working.
  await q.query('update auth_tokens set used_at = now() where user_id = $1 and kind = $2 and used_at is null', [userId, kind]);
  await q.query(
    `insert into auth_tokens (user_id, kind, token_hash, expires_at) values ($1, $2, $3, $4)`,
    [userId, kind, hashToken(token), new Date(Date.now() + ms)],
  );
  return token;
}

async function sendVerification(ctx: AppCtx, userId: string, to: string, name: string): Promise<void> {
  const token = await issueToken(ctx.db, userId, 'verify_email', VERIFY_MS);
  await ctx.mailer.send({
    to,
    subject: 'Verify your Ludo 3D email',
    text: `Hi ${name},\n\nConfirm your email address to secure your account:\n${ctx.cfg.appBaseUrl}/verify-email?token=${token}\n\nThis link expires in 48 hours.`,
  });
}

async function consumeToken(q: Queryable, token: unknown, kind: 'verify_email' | 'reset_password'): Promise<string> {
  if (typeof token !== 'string' || token.length < 20 || token.length > 100) throw new HttpError(400, 'This link is invalid or has expired');
  // Callers run this in a transaction; the row lock makes each token usable exactly once.
  const r = await q.query<{ id: string; user_id: string }>(
    `select id, user_id from auth_tokens
     where token_hash = $1 and kind = $2 and used_at is null and expires_at > now() for update`,
    [hashToken(token), kind],
  );
  if (!r.rows.length) throw new HttpError(400, 'This link is invalid or has expired');
  await q.query('update auth_tokens set used_at = now() where id = $1', [r.rows[0].id]);
  return r.rows[0].user_id;
}

export function authRouter(ctx: AppCtx): Router {
  const r = Router();
  const strict = rateLimit(ctx.cfg.authLimits.loginsPerMin, 60_000);
  const emailKey = (req: Request) => `${req.ip}|${String(req.body?.email ?? '').toLowerCase().slice(0, 254)}`;

  r.post(
    '/register',
    rateLimit(ctx.cfg.authLimits.registrationsPer10Min, 10 * 60_000),
    h(async (req, res) => {
      const b = req.body ?? {};
      const fullName = v.fullName(b.fullName);
      const email = v.email(b.email);
      const password = v.password(b.password, email);
      if (b.confirmPassword !== b.password) throw new HttpError(400, 'Passwords do not match');
      const passwordHash = await v.hashPassword(password);
      const settings = ctx.settings.get();
      const role = ctx.cfg.adminEmails.includes(email) ? 'admin' : 'player';

      let userId: string;
      try {
        userId = await ctx.db.tx((q) => createAccount(q, { email, fullName, passwordHash, role, welcomeBonus: settings.welcomeBonus }));
      } catch (e) {
        if ((e as { code?: string }).code === '23505') throw new HttpError(409, 'An account with this email already exists');
        throw e;
      }

      await createSession(ctx, req, res, userId, true);
      sendVerification(ctx, userId, email, fullName).catch((err) => console.error('[mail] verification failed', err));
      res.status(201);
      return { ok: true, welcomeBonus: settings.welcomeBonus };
    }),
  );

  r.post(
    '/login',
    strict,
    rateLimit(8, 15 * 60_000, emailKey),
    h(async (req, res) => {
      const b = req.body ?? {};
      const email = v.email(b.email);
      if (typeof b.password !== 'string' || !b.password) throw new HttpError(400, 'Password is required');
      const u = await ctx.db.query<{ id: string; password_hash: string; suspended_at: string | null; suspended_reason: string | null; role: string }>(
        'select id, password_hash, suspended_at, suspended_reason, role from users where lower(email) = $1',
        [email],
      );
      const row = u.rows[0];
      const ok = await v.verifyPassword(b.password, row?.password_hash ?? (await v.dummyPasswordHash()));
      if (!row || !ok) throw new HttpError(401, 'Incorrect email or password');
      if (row.suspended_at) throw new HttpError(403, `This account is suspended${row.suspended_reason ? `: ${row.suspended_reason}` : ''}`);
      if (ctx.cfg.adminEmails.includes(email) && row.role !== 'admin') {
        await ctx.db.query(`update users set role = 'admin', updated_at = now() where id = $1`, [row.id]);
      }
      await createSession(ctx, req, res, row.id, b.remember !== false);
      return { ok: true };
    }),
  );

  /**
   * Sign in with Google (the app's only way in). The ID token is checked with Google's keys;
   * the account is the one already linked to this Google account, else the one with the
   * same (Google-verified) email, so existing players keep their coins, else a new one.
   */
  r.post(
    '/google',
    strict,
    h(async (req, res) => {
      if (!ctx.google) throw new HttpError(503, 'Google sign-in is not set up on this server yet');
      const token = req.body?.idToken;
      if (typeof token !== 'string' || !token) throw new HttpError(400, 'Missing Google token');
      let g;
      try {
        g = await ctx.google.verify(token);
      } catch (e) {
        if (e instanceof InvalidGoogleToken) throw new HttpError(401, 'Google sign-in failed, please try again');
        throw e;
      }
      const settings = ctx.settings.get();
      const role = ctx.cfg.adminEmails.includes(g.email) ? 'admin' : 'player';
      type Row = { id: string; suspended_at: string | null; suspended_reason: string | null; role: string; google_sub: string | null };
      const cols = 'select id, suspended_at, suspended_reason, role, google_sub from users';
      const find = async () => {
        const bySub = await ctx.db.query<Row>(`${cols} where google_sub = $1`, [g.sub]);
        if (bySub.rows.length) return bySub.rows[0];
        return (await ctx.db.query<Row>(`${cols} where lower(email) = $1`, [g.email])).rows[0];
      };
      let row = await find();
      let created = false;
      if (row?.google_sub && row.google_sub !== g.sub) throw new HttpError(409, 'This email is linked to a different Google account');
      const guest = req.user?.isGuest ? req.user : null;
      let linked = false;
      if (!row && guest) {
        // A guest saving their account: the guest becomes this Google account (coins, diamonds,
        // friends and history stay). If the Google account already exists, it is used instead.
        const fullName = (g.name || g.email.split('@')[0]).slice(0, 60).trim() || guest.fullName;
        try {
          await ctx.db.tx(async (q) => {
            await q.query(
              `update users set email = $2, google_sub = $3, is_guest = false, email_verified_at = now(), role = $4, updated_at = now()
               where id = $1 and is_guest = true`,
              [guest.id, g.email, g.sub, role],
            );
            await q.query('update profiles set full_name = $2, updated_at = now() where user_id = $1', [guest.id, fullName]);
          });
        } catch (e) {
          if ((e as { code?: string }).code !== '23505') throw e;
        }
        row = await find();
        if (!row) throw new HttpError(500, 'Could not save the account');
        linked = row.id === guest.id;
      }
      if (!row) {
        const fullName = (g.name || g.email.split('@')[0]).slice(0, 60).trim() || 'Player';
        try {
          await ctx.db.tx((q) =>
            createAccount(q, { email: g.email, fullName, passwordHash: NO_PASSWORD, role, welcomeBonus: settings.welcomeBonus, googleSub: g.sub }),
          );
          created = true;
        } catch (e) {
          // Two taps at once: the other one made the account.
          if ((e as { code?: string }).code !== '23505') throw e;
        }
        row = await find();
        if (!row) throw new HttpError(500, 'Could not create the account');
      } else if (!row.google_sub) {
        // An email account signing in with Google for the first time: link it.
        await ctx.db.query(
          'update users set google_sub = $2, email_verified_at = coalesce(email_verified_at, now()), updated_at = now() where id = $1',
          [row.id, g.sub],
        );
      }
      if (row.suspended_at) throw new HttpError(403, `This account is suspended${row.suspended_reason ? `: ${row.suspended_reason}` : ''}`);
      // The Google profile picture, kept fresh on every sign-in.
      if (g.picture) await ctx.db.query('update profiles set avatar_url = $2 where user_id = $1', [row.id, g.picture]);
      if (role === 'admin' && row.role !== 'admin') await ctx.db.query(`update users set role = 'admin', updated_at = now() where id = $1`, [row.id]);
      await createSession(ctx, req, res, row.id, true);
      if (created) res.status(201);
      return { ok: true, created, linked, welcomeBonus: created ? settings.welcomeBonus : 0 };
    }),
  );

  /**
   * Play as a guest: a new account with no sign-in of its own, kept on this device by its
   * long-lived session. Signing in with Google later keeps everything (see /google).
   */
  r.post(
    '/guest',
    rateLimit(10, 60 * 60_000),
    h(async (req, res) => {
      const settings = ctx.settings.get();
      const userId = await ctx.db.tx((q) =>
        createAccount(q, {
          email: `guest-${randomUUID()}@guest.invalid`,
          fullName: 'Guest',
          passwordHash: GUEST_PASSWORD,
          role: 'player',
          welcomeBonus: settings.welcomeBonus,
          guest: true,
        }),
      );
      await createSession(ctx, req, res, userId, true, GUEST_MS);
      res.status(201);
      return { ok: true, created: true, welcomeBonus: settings.welcomeBonus };
    }),
  );

  r.post(
    '/logout',
    h(async (req, res) => {
      if (req.user) await ctx.db.query('update sessions set revoked_at = now() where id = $1', [req.user.sessionId]);
      clearSessionCookie(res, ctx.cfg);
      return { ok: true };
    }),
  );

  r.get(
    '/me',
    h(async (req) => {
      if (!req.user) return { ok: true, user: null };
      // A player who travels (or was signed in before countries existed) gets theirs here.
      await refreshCountry(ctx.db, req.user.id, req.ip, req.user.country).catch(() => {});
      const wallet = await walletSummary(ctx.db, req.user.id, ctx.settings.get().dailyBonus);
      return { ok: true, user: publicUser(req.user), wallet, requireEmailVerification: ctx.cfg.requireEmailVerification };
    }),
  );

  r.post(
    '/verify-email',
    strict,
    h(async (req) => {
      await ctx.db.tx(async (q) => {
        const userId = await consumeToken(q, req.body?.token, 'verify_email');
        await q.query('update users set email_verified_at = coalesce(email_verified_at, now()), updated_at = now() where id = $1', [userId]);
      });
      return { ok: true };
    }),
  );

  r.post(
    '/resend-verification',
    requireUser,
    rateLimit(3, 15 * 60_000, (req) => req.user?.id ?? req.ip ?? ''),
    h(async (req) => {
      const u = req.user!;
      if (u.emailVerified) return { ok: true };
      await sendVerification(ctx, u.id, u.email, u.fullName);
      return { ok: true };
    }),
  );

  r.post(
    '/forgot-password',
    rateLimit(5, 15 * 60_000),
    h(async (req) => {
      const email = v.email(req.body?.email);
      const u = await ctx.db.query<{ id: string; full_name: string }>(
        `select u.id, p.full_name from users u join profiles p on p.user_id = u.id where lower(u.email) = $1 and u.suspended_at is null`,
        [email],
      );
      if (u.rows.length) {
        const token = await issueToken(ctx.db, u.rows[0].id, 'reset_password', RESET_MS);
        await ctx.mailer.send({
          to: email,
          subject: 'Reset your Ludo 3D password',
          text: `Hi ${u.rows[0].full_name},\n\nReset your password here (valid for 1 hour):\n${ctx.cfg.appBaseUrl}/reset-password?token=${token}\n\nIf you didn't ask for this, you can ignore this email.`,
        });
      }
      // Same answer whether or not the account exists, so emails can't be enumerated.
      return { ok: true, message: 'If an account exists for that email, a reset link is on its way.' };
    }),
  );

  r.post(
    '/reset-password',
    strict,
    h(async (req, res) => {
      const b = req.body ?? {};
      if (b.confirmPassword !== b.password) throw new HttpError(400, 'Passwords do not match');
      const pw = v.password(b.password);
      const hash = await v.hashPassword(pw);
      await ctx.db.tx(async (q) => {
        const userId = await consumeToken(q, b.token, 'reset_password');
        await q.query('update users set password_hash = $2, updated_at = now() where id = $1', [userId, hash]);
        // A reset signs the account out everywhere.
        await q.query('update sessions set revoked_at = now() where user_id = $1 and revoked_at is null', [userId]);
      });
      clearSessionCookie(res, ctx.cfg);
      return { ok: true };
    }),
  );

  r.post(
    '/change-password',
    requireUser,
    strict,
    h(async (req) => {
      const b = req.body ?? {};
      const u = req.user!;
      const cur = await ctx.db.query<{ password_hash: string }>('select password_hash from users where id = $1', [u.id]);
      if (typeof b.currentPassword !== 'string' || !(await v.verifyPassword(b.currentPassword, cur.rows[0].password_hash))) {
        throw new HttpError(400, 'Current password is incorrect');
      }
      if (b.confirmPassword !== b.password) throw new HttpError(400, 'Passwords do not match');
      const hash = await v.hashPassword(v.password(b.password, u.email));
      await ctx.db.tx(async (q) => {
        await q.query('update users set password_hash = $2, updated_at = now() where id = $1', [u.id, hash]);
        await q.query('update sessions set revoked_at = now() where user_id = $1 and id <> $2 and revoked_at is null', [u.id, u.sessionId]);
      });
      return { ok: true };
    }),
  );

  return r;
}
