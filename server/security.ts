import { createHash, randomBytes, scrypt as scryptCb, timingSafeEqual, type ScryptOptions } from 'node:crypto';

/** Password hashing, tokens, validation and rate limiting shared by the HTTP API and sockets. */

export class HttpError extends Error {
  constructor(public status: number, message: string, public code?: string) {
    super(message);
  }
}

// ---------------------------------------------------------------- passwords (scrypt)

const SCRYPT = { N: 32768, r: 8, p: 1, maxmem: 64 * 1024 * 1024 } satisfies ScryptOptions;
const KEYLEN = 64;

function scrypt(password: string, salt: Buffer, opts: ScryptOptions): Promise<Buffer> {
  return new Promise((resolve, reject) =>
    scryptCb(password.normalize('NFKC'), salt, KEYLEN, opts, (err, key) => (err ? reject(err) : resolve(key))),
  );
}

/** Format: scrypt$N$r$p$saltB64$hashB64 (parameters stored so they can be raised later). */
export async function hashPassword(password: string): Promise<string> {
  const salt = randomBytes(16);
  const key = await scrypt(password, salt, SCRYPT);
  return `scrypt$${SCRYPT.N}$${SCRYPT.r}$${SCRYPT.p}$${salt.toString('base64')}$${key.toString('base64')}`;
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const parts = stored.split('$');
  if (parts.length !== 6 || parts[0] !== 'scrypt') return false;
  const [N, r, p] = parts.slice(1, 4).map(Number);
  const salt = Buffer.from(parts[4], 'base64');
  const expected = Buffer.from(parts[5], 'base64');
  const key = await scrypt(password, salt, { N, r, p, maxmem: SCRYPT.maxmem });
  return key.length === expected.length && timingSafeEqual(key, expected);
}

/** A dummy hash so failed logins for unknown emails take as long as real ones. */
let dummyHash: Promise<string> | null = null;
export function dummyPasswordHash(): Promise<string> {
  return (dummyHash ??= hashPassword(randomBytes(12).toString('hex')));
}

// ---------------------------------------------------------------- tokens

export function newToken(bytes = 32): string {
  return randomBytes(bytes).toString('base64url');
}

/** Tokens are stored only as SHA-256 hashes, so a database leak can't be replayed. */
export function hashToken(token: string): string {
  return createHash('sha256').update(token).digest('hex');
}

const PUBLIC_ID_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
export function newPublicId(): string {
  let out = '';
  for (const b of randomBytes(8)) out += PUBLIC_ID_ALPHABET[b % PUBLIC_ID_ALPHABET.length];
  return out;
}

// ---------------------------------------------------------------- validation

const EMAIL_RE = /^[^\s@<>()[\]\\,;:"]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$/;
const COMMON_PASSWORDS = new Set([
  'password', 'password1', 'password123', '12345678', '123456789', '1234567890', 'qwerty123', 'qwertyuiop',
  'iloveyou1', 'admin123', 'welcome1', 'abc12345', 'football1', 'letmein1', 'monkey123', 'ludo1234',
]);

export function str(v: unknown, field: string, min: number, max: number): string {
  if (typeof v !== 'string') throw new HttpError(400, `${field} is required`);
  const s = v.normalize('NFC').trim();
  if (s.length < min) throw new HttpError(400, min <= 1 ? `${field} is required` : `${field} must be at least ${min} characters`);
  if (s.length > max) throw new HttpError(400, `${field} must be at most ${max} characters`);
  return s;
}

export function email(v: unknown): string {
  const e = str(v, 'Email', 3, 254).toLowerCase();
  if (!EMAIL_RE.test(e)) throw new HttpError(400, 'Enter a valid email address');
  return e;
}

export function passwordProblem(pw: string, emailAddr?: string): string | null {
  if (pw.length < 8) return 'Password must be at least 8 characters';
  if (pw.length > 128) return 'Password must be at most 128 characters';
  if (!/[A-Za-z]/.test(pw) || !/[0-9]/.test(pw)) return 'Password must contain letters and numbers';
  if (COMMON_PASSWORDS.has(pw.toLowerCase())) return 'That password is too common';
  if (emailAddr && pw.toLowerCase().includes(emailAddr.split('@')[0].toLowerCase()) && emailAddr.split('@')[0].length >= 4) {
    return 'Password must not contain your email name';
  }
  return null;
}

export function password(v: unknown, emailAddr?: string): string {
  if (typeof v !== 'string') throw new HttpError(400, 'Password is required');
  const p = passwordProblem(v, emailAddr);
  if (p) throw new HttpError(400, p);
  return v;
}

export function fullName(v: unknown): string {
  const n = str(v, 'Full name', 2, 60).replace(/\s+/g, ' ');
  if (/[\u0000-\u001f<>]/.test(n)) throw new HttpError(400, 'Full name contains invalid characters');
  return n;
}

export function username(v: unknown): string {
  const u = str(v, 'Username', 3, 20);
  if (!/^[A-Za-z0-9_]{3,20}$/.test(u)) throw new HttpError(400, 'Username may use letters, numbers and _ (3–20)');
  return u;
}

export function int(v: unknown, field: string, min: number, max: number): number {
  const n = typeof v === 'string' && v.trim() !== '' ? Number(v) : v;
  if (typeof n !== 'number' || !Number.isInteger(n) || n < min || n > max) {
    throw new HttpError(400, `${field} must be a whole number between ${min} and ${max}`);
  }
  return n;
}

export function uuid(v: unknown, field = 'id'): string {
  if (typeof v !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v)) {
    throw new HttpError(400, `Invalid ${field}`);
  }
  return v.toLowerCase();
}

/** Only allow same-site relative redirects ("/x", never "//host" or "/\host"). */
export function safeNext(v: unknown): string {
  return typeof v === 'string' && /^\/(?![/\\])[^\s]*$/.test(v) ? v : '/';
}

// ---------------------------------------------------------------- rate limiting

/** Fixed-window counters per key, pruned periodically. */
export class WindowLimiter {
  private hits = new Map<string, { n: number; reset: number }>();
  constructor(private max: number, private windowMs: number) {}
  take(key: string, now = Date.now()): boolean {
    const h = this.hits.get(key);
    if (!h || now >= h.reset) {
      this.hits.set(key, { n: 1, reset: now + this.windowMs });
      if (this.hits.size > 50_000) for (const [k, v] of this.hits) if (now >= v.reset) this.hits.delete(k);
      return true;
    }
    h.n += 1;
    return h.n <= this.max;
  }
}
