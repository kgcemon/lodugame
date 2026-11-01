import { createSign } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';

/**
 * Google Play purchase verification (Android Publisher API v3), with a service account.
 * The app sends the purchase token; the server asks Google whether it is a real, paid
 * purchase of that product before granting anything, then consumes it so it can be
 * bought again. No SDK: a signed JWT is exchanged for an access token.
 */

export interface PlayPurchase {
  /** 0 = purchased, 1 = cancelled, 2 = pending. */
  purchaseState: number;
  /** 0 = not consumed yet, 1 = consumed. */
  consumptionState: number;
  orderId?: string;
  /** Set by the app to the buyer's user id, so a token can't be redeemed by someone else. */
  obfuscatedExternalAccountId?: string;
}

export interface PlayVerifier {
  verify(productId: string, token: string): Promise<PlayPurchase>;
  consume(productId: string, token: string): Promise<void>;
}

interface ServiceAccount {
  client_email: string;
  private_key: string;
  token_uri?: string;
}

const SCOPE = 'https://www.googleapis.com/auth/androidpublisher';

function b64url(v: string | Buffer): string {
  return Buffer.from(v).toString('base64url');
}

export class GooglePlayVerifier implements PlayVerifier {
  private token: { value: string; exp: number } | null = null;

  constructor(private account: ServiceAccount, private packageName: string) {}

  /** From GOOGLE_PLAY_SERVICE_ACCOUNT (a JSON file path or the JSON itself); null when not configured. */
  static fromEnv(raw: string | undefined, packageName: string): GooglePlayVerifier | null {
    if (!raw) return null;
    const text = raw.trim().startsWith('{') ? raw : existsSync(raw) ? readFileSync(raw, 'utf8') : null;
    if (!text) {
      console.error('[play] GOOGLE_PLAY_SERVICE_ACCOUNT is set but the file was not found');
      return null;
    }
    const acc = JSON.parse(text) as ServiceAccount;
    if (!acc.client_email || !acc.private_key) throw new Error('GOOGLE_PLAY_SERVICE_ACCOUNT is not a service account key');
    return new GooglePlayVerifier(acc, packageName);
  }

  private async accessToken(): Promise<string> {
    const now = Math.floor(Date.now() / 1000);
    if (this.token && this.token.exp - 60 > now) return this.token.value;
    const aud = this.account.token_uri ?? 'https://oauth2.googleapis.com/token';
    const head = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
    const claims = b64url(JSON.stringify({ iss: this.account.client_email, scope: SCOPE, aud, iat: now, exp: now + 3600 }));
    const sig = createSign('RSA-SHA256').update(`${head}.${claims}`).sign(this.account.private_key);
    const res = await fetch(aud, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${head}.${claims}.${b64url(sig)}` }),
    });
    if (!res.ok) throw new Error(`Google token request failed (${res.status})`);
    const data = (await res.json()) as { access_token: string; expires_in: number };
    this.token = { value: data.access_token, exp: now + data.expires_in };
    return data.access_token;
  }

  private url(productId: string, token: string): string {
    return `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(this.packageName)}/purchases/products/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(token)}`;
  }

  async verify(productId: string, token: string): Promise<PlayPurchase> {
    const res = await fetch(this.url(productId, token), { headers: { authorization: `Bearer ${await this.accessToken()}` } });
    if (res.status === 400 || res.status === 404 || res.status === 410) throw new Error('INVALID_PURCHASE');
    if (!res.ok) throw new Error(`Google Play verification failed (${res.status})`);
    return (await res.json()) as PlayPurchase;
  }

  async consume(productId: string, token: string): Promise<void> {
    const res = await fetch(`${this.url(productId, token)}:consume`, {
      method: 'POST',
      headers: { authorization: `Bearer ${await this.accessToken()}` },
    });
    if (!res.ok) throw new Error(`Google Play consume failed (${res.status})`);
  }
}
