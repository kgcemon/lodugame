import { io, type Socket } from 'socket.io-client';
import type { Ack, ChatMessage, ClientToServerEvents, GameUpdate, RoomView, ServerToClientEvents } from '../../shared/protocol';
import { isEmoteId } from '../../shared/social';
import { sfx } from '../audio/audio';
import { useAuth } from '../store/auth';
import { useGame } from '../store/game';
import { beginMatch, enqueue, resetDirector, setView } from './director';
import { clearReactions, showEmote } from './emotes';
import { dice } from './runtime';

/**
 * Online client. The socket is authenticated by the session cookie; the server
 * owns dice, turns, rules, entry fees and payouts. The client sends intents
 * and animates authoritative updates through the same director as local play.
 */

type ClientSocket = Socket<ServerToClientEvents, ClientToServerEvents>;
let socket: ClientSocket | null = null;
let connecting: Promise<boolean> | null = null;

export type RoomResult = { ok: true; room: RoomView } | { ok: false; error: string; code?: string };

function call<T extends object = object>(ev: keyof ClientToServerEvents, payload: object = {}): Promise<Ack<T>> {
  return new Promise((resolve) => {
    if (!socket?.connected) {
      resolve({ ok: false, error: 'Not connected to the game server' });
      return;
    }
    const timer = setTimeout(() => resolve({ ok: false, error: 'The server did not respond' }), 10_000);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (socket.emit as any)(ev, payload, (res: Ack<T>) => {
      clearTimeout(timer);
      resolve(res);
    });
  });
}

async function hello(): Promise<void> {
  const res = await call<{ room: RoomView | null }>('session:hello');
  if (res.ok && res.room) handleRoom(res.room);
}

function handleRoom(room: RoomView): void {
  const s = useGame.getState();
  s.set({ room });
  const me = room.seats[room.you];
  if (s.matchKind === 'online' && me) s.set({ localColors: [me.color] });
  // Back in the waiting room (e.g. a rematch, or reconnecting to a lobby): show the room page.
  if (room.status === 'lobby' && (s.screen === 'game' || s.screen === 'menu')) {
    if (s.screen === 'game') {
      resetDirector();
      s.set({ screen: 'menu', matchKind: null, game: null, settlement: null });
    }
    s.set({ pendingNav: '/room' });
  }
}

function handleUpdate(u: GameUpdate): void {
  const s = useGame.getState();
  const deadline = u.deadline ? u.deadline - u.serverTime + Date.now() : null;
  const me = s.room?.seats[s.room.you];
  if (s.screen !== 'game' || s.matchKind !== 'online' || !s.game) {
    resetDirector();
    s.set({
      screen: 'game',
      matchKind: 'online',
      game: u.state,
      localColors: me ? [me.color] : [],
      viewColor: me?.color ?? 'red',
      deadline,
      paused: false,
      lastRoll: null,
      modal: null,
      settlement: null,
    });
    setView(u.state);
    dice.api?.place(u.state.players[u.state.current].color, null);
    sfx('turn');
    return;
  }
  const prev = s.game;
  if (u.state.seq < prev.seq) beginMatch(); // rematch started
  s.set({ game: u.state, deadline });
  // Contiguous updates animate; anything else (rejoin, rematch, name/bot change) snaps.
  if (u.events.length && u.state.seq === prev.seq + 1) enqueue(u.state, u.events);
  else enqueue(u.state, []);
}

const CHAT_KEEP = 100;

function mySeatId(): string | null {
  const r = useGame.getState().room;
  return r?.seats[r.you]?.id ?? null;
}

function handleChat(m: ChatMessage): void {
  const s = useGame.getState();
  const chat = [...s.chat, m].slice(-CHAT_KEEP);
  const fromOther = m.seatId !== mySeatId() && !s.muted.includes(m.seatId);
  const unread = !s.chatOpen && fromOther ? s.unread + 1 : s.unread;
  s.set({ chat, unread });
  if (fromOther) sfx('message');
}

function resetSocial(): void {
  useGame.getState().set({ chat: [], unread: 0, chatOpen: false, emoteOpen: false, muted: [], hidden: [] });
  clearReactions();
}

/** Open the authenticated game socket (after sign-in). */
export function connect(): Promise<boolean> {
  if (socket?.connected) return Promise.resolve(true);
  if (connecting) return connecting;
  if (!socket) {
    const url = (import.meta.env.VITE_SERVER_URL as string | undefined) || undefined;
    useGame.getState().set({ connection: 'connecting' });
    const opts = { transports: ['websocket', 'polling'], withCredentials: true };
    socket = url ? io(url, opts) : io(opts);
    socket.on('connect', () => {
      useGame.getState().set({ connection: 'online' });
      void hello();
    });
    socket.on('disconnect', (reason) => {
      useGame.getState().set({ connection: reason === 'io client disconnect' ? 'offline' : 'reconnecting' });
    });
    socket.on('room:state', handleRoom);
    socket.on('game:update', handleUpdate);
    socket.on('chat:history', (msgs) => {
      if (Array.isArray(msgs)) useGame.getState().set({ chat: msgs.slice(-CHAT_KEEP) });
    });
    socket.on('chat:message', handleChat);
    socket.on('emote', (e) => {
      const s = useGame.getState();
      if (!isEmoteId(e?.id) || s.screen !== 'game' || s.matchKind !== 'online' || s.muted.includes(e.seatId)) return;
      showEmote(e.id, e.color);
    });
    socket.on('room:closed', (reason) => {
      resetSocial();
      const s = useGame.getState();
      s.toast(reason);
      if (s.screen === 'game') resetDirector();
      s.set({ room: null, screen: 'menu', matchKind: null, game: null, pendingNav: '/' });
    });
    socket.on('match:settled', (m) => useGame.getState().set({ settlement: m }));
    socket.on('wallet', (w) => useAuth.setState((a) => ({ wallet: { ...a.wallet, ...w } })));
    socket.on('notify', (n) => {
      useAuth.setState((a) => ({ notifications: [n as never, ...a.notifications].slice(0, 30), unread: a.unread + 1 }));
      const p = n.payload as Record<string, string | number>;
      const text =
        n.type === 'friend_request' ? `${p.from} sent you a friend request`
        : n.type === 'friend_accepted' ? `${p.by} accepted your friend request`
        : n.type === 'room_invite' ? `${p.from} invited you to a ${p.modeName} room (${p.code})`
        : n.type === 'wallet' ? `Your balance was adjusted by ${p.amount} coins`
        : null;
      if (text) {
        useGame.getState().toast(text);
        sfx('message');
      }
    });
    socket.on('friends:changed', () => useAuth.setState((a) => ({ friendsVersion: a.friendsVersion + 1 })));
    socket.on('connect_error', (e) => {
      useGame.getState().set({ connection: 'offline' });
      // The session expired or was revoked: stop retrying with a dead cookie.
      if (e.message === 'unauthorized') disconnect();
    });
  } else {
    socket.connect();
  }
  connecting = new Promise((resolve) => {
    const done = (ok: boolean) => {
      connecting = null;
      resolve(ok);
    };
    socket!.once('connect', () => done(true));
    socket!.once('connect_error', () => done(false));
  });
  return connecting;
}

/** Close the socket (sign-out). */
export function disconnect(): void {
  socket?.removeAllListeners();
  socket?.disconnect();
  socket = null;
  connecting = null;
  resetSocial();
  useGame.getState().set({ connection: 'offline', room: null });
}

async function act(ev: keyof ClientToServerEvents, payload: object = {}): Promise<boolean> {
  const res = await call(ev, payload);
  if (!res.ok) {
    useGame.getState().toast(res.error);
    sfx('error');
  }
  return res.ok;
}

async function roomCall(ev: 'room:quick' | 'room:create' | 'room:join', payload: object): Promise<RoomResult> {
  if (!(await connect())) return { ok: false, error: 'Cannot reach the game server' };
  const res = await call<{ room: RoomView }>(ev, payload);
  if (res.ok) handleRoom(res.room);
  else sfx('error');
  return res as RoomResult;
}

export const net = {
  connect,
  disconnect,
  isConnected: () => !!socket?.connected,
  quick: (modeId: string) => roomCall('room:quick', { modeId }),
  create: (modeId: string, maxPlayers: number, teams = false) => roomCall('room:create', { modeId, maxPlayers, teams }),
  join: (code: string) => roomCall('room:join', { code }),
  ready: (ready: boolean) => act('room:ready', { ready }),
  addBot: () => act('room:addBot'),
  kick: (seat: number) => act('room:kick', { seat }),
  start: () => act('room:start'),
  rematch: () => act('game:rematch'),
  roll: () => act('game:roll'),
  move: (token: number) => act('game:move', { token }),
  /** `team` sends to my 2 vs 2 partner only. */
  chat: async (text: string, quick = false, team = false) => act('chat:send', { text, quick, team }),
  emote: async (id: string) => act('emote:send', { id }),
  leave: async () => {
    resetSocial();
    await call('room:leave');
    useGame.getState().set({ room: null });
  },
};
