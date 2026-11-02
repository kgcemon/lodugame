import { createPublicKey, createVerify, type JsonWebKey } from 'node:crypto';

/**
 * Google Sign-In: checks the ID token the app gets from Google (Credential Manager) before
 * the server trusts who the player is. The token is a JWT signed by Google (RS256); its
 * signature is checked against Google's published keys, and it must be issued by Google,
 * for one of our OAuth client ids, unexpired, with a verified email.
 */

export interface GoogleIdentity {
  /** Google's stable account id. */
  sub: string;
  email: string;
  name: string;
  /** Profile picture URL (https), or null. */
  picture: string | null;
}

export interface GoogleVerifier {
  verify(idToken: string): Promise<GoogleIdentity>;
}

const CERTS_URL = 'https://www.googleapis.com/oauth2/v3/certs';
const ISSUERS = new Set(['accounts.google.com', 'https://accounts.google.com']);

export class InvalidGoogleToken extends Error {}

function b64json<T>(part: string): T {
  return JSON.parse(Buffer.from(part, 'base64url').toString('utf8')) as T;
}

export class GoogleIdTokenVerifier implements GoogleVerifier {
  private keys: Map<string, JsonWebKey> = new Map();
  private keysUntil = 0;

  constructor(
    /** OAuth client ids the token may be issued for (the Web client id the app asks for). */
    private readonly audiences: string[],
    private readonly fetchCerts: () => Promise<{ keys: JsonWebKey[]; maxAgeMs: number }> = defaultFetchCerts,
  ) {}

  static fromEnv(clientIds: string[]): GoogleIdTokenVerifier | null {
    return clientIds.length ? new GoogleIdTokenVerifier(clientIds) : null;
  }

  private async key(kid: string): Promise<JsonWebKey | undefined> {
    if (Date.now() >= this.keysUntil || !this.keys.has(kid)) {
      const { keys, maxAgeMs } = await this.fetchCerts();
      this.keys = new Map(keys.filter((k) => typeof k.kid === 'string').map((k) => [k.kid as string, k]));
      this.keysUntil = Date.now() + Math.max(60_000, maxAgeMs);
    }
    return this.keys.get(kid);
  }

  async verify(idToken: string): Promise<GoogleIdentity> {
    const parts = typeof idToken === 'string' ? idToken.split('.') : [];
    if (parts.length !== 3 || idToken.length > 4096) throw new InvalidGoogleToken('Malformed token');
    let header: { alg?: string; kid?: string };
    let claims: { iss?: string; aud?: string; exp?: number; iat?: number; sub?: string; email?: string; email_verified?: boolean | string; name?: string; picture?: string };
    try {
      header = b64json(parts[0]);
      claims = b64json(parts[1]);
    } catch {
      throw new InvalidGoogleToken('Malformed token');
    }
    if (header.alg !== 'RS256' || !header.kid) throw new InvalidGoogleToken('Unexpected token type');
    const jwk = await this.key(header.kid);
    if (!jwk) throw new InvalidGoogleToken('Unknown signing key');
    const ok = createVerify('RSA-SHA256').update(`${parts[0]}.${parts[1]}`).verify(createPublicKey({ key: jwk, format: 'jwk' }), Buffer.from(parts[2], 'base64url'));
    if (!ok) throw new InvalidGoogleToken('Bad signature');
    const now = Date.now() / 1000;
    if (!claims.iss || !ISSUERS.has(claims.iss)) throw new InvalidGoogleToken('Wrong issuer');
    if (!claims.aud || !this.audiences.includes(claims.aud)) throw new InvalidGoogleToken('Token is for another app');
    if (typeof claims.exp !== 'number' || claims.exp < now - 30) throw new InvalidGoogleToken('Token expired');
    if (typeof claims.iat === 'number' && claims.iat > now + 300) throw new InvalidGoogleToken('Token from the future');
    if (!claims.sub || !claims.email) throw new InvalidGoogleToken('No account in token');
    if (claims.email_verified !== true && claims.email_verified !== 'true') throw new InvalidGoogleToken('Google email not verified');
    const picture = typeof claims.picture === 'string' && claims.picture.startsWith('https://') && claims.picture.length <= 500 ? claims.picture : null;
    return { sub: claims.sub, email: claims.email.toLowerCase(), name: (claims.name ?? '').trim(), picture };
  }
}

async function defaultFetchCerts(): Promise<{ keys: JsonWebKey[]; maxAgeMs: number }> {
  const res = await fetch(CERTS_URL);
  if (!res.ok) throw new Error(`Google certs: HTTP ${res.status}`);
  const body = (await res.json()) as { keys: JsonWebKey[] };
  const m = /max-age=(\d+)/.exec(res.headers.get('cache-control') ?? '');
  return { keys: body.keys ?? [], maxAgeMs: m ? Number(m[1]) * 1000 : 3600_000 };
}
