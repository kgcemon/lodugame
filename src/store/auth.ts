import { create } from 'zustand';
import { ApiError, get, post, type Notification, type PublicConfig, type User, type Wallet } from '../lib/api';

/**
 * Signed-in user, wallet and notifications. The server is the source of truth:
 * this store only mirrors it (refetched on load and pushed over the socket).
 */

interface AuthStore {
  status: 'loading' | 'guest' | 'ready';
  user: User | null;
  wallet: Wallet | null;
  config: PublicConfig | null;
  notifications: Notification[];
  unread: number;
  /** Bumped when the friends list changes, so pages can refetch. */
  friendsVersion: number;
  set: (patch: Partial<AuthStore>) => void;
}

export const useAuth = create<AuthStore>()((set) => ({
  status: 'loading',
  user: null,
  wallet: null,
  config: null,
  notifications: [],
  unread: 0,
  friendsVersion: 0,
  set: (patch) => set(patch),
}));

export async function loadConfig(): Promise<void> {
  try {
    const c = await get<PublicConfig & { ok: boolean }>('/config');
    useAuth.setState({ config: c });
  } catch {
    /* shown as an error by pages that need it */
  }
}

export async function refreshMe(): Promise<User | null> {
  try {
    const r = await get<{ user: User | null; wallet?: Wallet }>('/auth/me');
    useAuth.setState({ user: r.user, wallet: r.wallet ?? null, status: r.user ? 'ready' : 'guest' });
    if (r.user) void refreshNotifications();
    return r.user;
  } catch (e) {
    // A network error must not look like a logout.
    if (e instanceof ApiError && e.status === 401) useAuth.setState({ user: null, wallet: null, status: 'guest' });
    else useAuth.setState((s) => ({ status: s.user ? 'ready' : 'guest' }));
    return null;
  }
}

export async function refreshWallet(): Promise<void> {
  try {
    const r = await get<{ wallet: Wallet }>('/wallet');
    useAuth.setState({ wallet: r.wallet });
  } catch {
    /* keep the last known value */
  }
}

export async function refreshNotifications(): Promise<void> {
  try {
    const r = await get<{ items: Notification[]; unread: number }>('/notifications');
    useAuth.setState({ notifications: r.items, unread: r.unread });
  } catch {
    /* ignore */
  }
}

export async function markNotificationsRead(): Promise<void> {
  useAuth.setState((s) => ({ unread: 0, notifications: s.notifications.map((n) => ({ ...n, readAt: n.readAt ?? new Date().toISOString() })) }));
  await post('/notifications/read').catch(() => {});
}

export async function logout(): Promise<void> {
  await post('/auth/logout').catch(() => {});
  useAuth.setState({ user: null, wallet: null, notifications: [], unread: 0, status: 'guest' });
}
