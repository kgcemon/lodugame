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
import { applyWalletTx, walletSummary } from './wallet';

/**
 * Accounts and sessions. Passwords are scrypt-hashed; sessions are random
 * tokens kept only as SHA-256 hashes, sent in an HttpOnly SameSite cookie.
 */

const REMEMBER_MS = 30 * 24 * 3600_000;
const SHORT_MS = 24 * 3600_000;
const VERIFY_MS = 48 * 3600_000;
const RESET_MS = 3600_000;

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
  };
}

function usernameBase(name: string): string {
  const ascii = name.normalize('NFKD').replace(/[^A-Za-z0-9]/g, '');
  return (ascii || 'player').slice(0, 14);
}

async function createSession(ctx: AppCtx, req: Request, res: Response, userId: string, remember: boolean): Promise<void> {
  const token = newToken();
  const ms = remember ? REMEMBER_MS : SHORT_MS;
  await ctx.db.query(
    `insert into sessions (user_id, token_hash, expires_at, ip, user_agent) values ($1, $2, $3, $4, $5)`,
    [userId, hashToken(token), new Date(Date.now() + ms), req.ip ?? null, (req.get('user-agent') ?? '').slice(0, 300)],
  );
  setSessionCookie(res, ctx.cfg, token, remember ? ms : null);
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
        userId = await ctx.db.tx(async (q) => {
          const id = randomUUID();
          await q.query(`insert into users (id, email, password_hash, role) values ($1, $2, $3, $4)`, [id, email, passwordHash, role]);
          // Unique username/public id: retry on the rare collision.
          for (let i = 0; ; i++) {
            const uname = `${usernameBase(fullName)}${Math.floor(1000 + Math.random() * 9000)}`;
            const ok = await q.query(
              `insert into profiles (user_id, full_name, username, public_id) values ($1, $2, $3, $4)
               on conflict do nothing`,
              [id, fullName, uname, newPublicId()],
            );
            if (ok.rowCount) break;
            if (i > 8) throw new Error('Could not allocate a username');
          }
          await q.query('insert into wallets (user_id) values ($1)', [id]);
          // Welcome bonus: same transaction as the account, keyed so it can only ever be granted once.
          if (settings.welcomeBonus > 0) {
            await applyWalletTx(q, { userId: id, type: 'welcome_bonus', amount: settings.welcomeBonus, key: 'welcome_bonus', note: 'Welcome bonus' });
          }
          return id;
        });
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
