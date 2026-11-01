/**
 * Browser API client. Cookies carry the session (HttpOnly — invisible to JS);
 * every request sends the header the server requires to block cross-site forgery.
 */

export class ApiError extends Error {
  constructor(public status: number, message: string, public code?: string) {
    super(message);
  }
}

export async function api<T = Record<string, unknown>>(method: string, path: string, body?: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`/api${path}`, {
      method,
      credentials: 'same-origin',
      headers: { 'content-type': 'application/json', 'x-requested-with': 'ludo3d' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'Cannot reach the server. Check your connection.');
  }
  let data: { ok?: boolean; error?: string; code?: string } & Record<string, unknown> = {};
  try {
    data = await res.json();
  } catch {
    /* non-JSON error page */
  }
  if (!res.ok || data.ok === false) throw new ApiError(res.status, data.error ?? `Request failed (${res.status})`, data.code);
  return data as T;
}

export const get = <T>(path: string) => api<T>('GET', path);
export const post = <T>(path: string, body: unknown = {}) => api<T>('POST', path, body);

// ---------------------------------------------------------------- shared response types

export interface User {
  id: string;
  email: string;
  role: 'player' | 'admin';
  emailVerified: boolean;
  fullName: string;
  username: string;
  publicId: string;
  avatarHue: number;
}

export interface Wallet {
  balance: number;
  totalEarned: number;
  totalSpent: number;
  dailyBonus?: { amount: number; claimedToday: boolean; nextAt: string };
}

export interface MatchMode {
  id: string;
  name: string;
  description: string;
  entryFee: number;
  maxPlayers: number;
  matchType: string;
  enabled: boolean;
  privateOnly?: boolean;
}

export interface PublicConfig {
  appBaseUrl: string;
  welcomeBonus: number;
  dailyBonus: number;
  payoutMultiplier: number;
  payoutPolicy: string;
  modes: MatchMode[];
}

export interface Notification {
  id: string;
  type: 'friend_request' | 'friend_accepted' | 'room_invite' | 'match_result' | 'wallet' | 'system';
  payload: Record<string, unknown>;
  readAt: string | null;
  createdAt: string;
}

export const fmt = (n: number) => n.toLocaleString('en-US');
