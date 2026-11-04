/**
 * Realistic load against a running server: players queue, match, roll, move, chat, emote, send
 * gifts, browse their wallet and history, lose their connection and come back, and some talk.
 * Never run this against production: it registers accounts and plays matches.
 *
 *   npx tsx scripts/loadtest.ts --url http://127.0.0.1:3411 --players 10,25,50,100 --seconds 180 --out report.json
 *
 * The server needs an administrator (ADMIN_EMAILS=loadtest-admin@example.com) so the health figures
 * can be read, and limits lifted for one address: REGISTER_LIMIT, LOGIN_LIMIT, API_LIMIT, SOCKET_LIMIT.
 * --mysql <url> lets some pairs be friends, so their voice packets are really relayed.
 */
import { randomUUID } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { io, type Socket } from 'socket.io-client';
import { distinctMoves } from '../shared/engine';

const arg = (name: string, dflt: string) => {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? process.argv[i + 1] : dflt;
};
const BASE = arg('url', 'http://127.0.0.1:3411');
const LEVELS = arg('players', '10,25,50,100').split(',').map(Number);
const SECONDS = Number(arg('seconds', '180'));
const OUT = arg('out', '');
const MYSQL = arg('mysql', '');

const rand = (a: number, b: number) => a + Math.random() * (b - a);
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Round trips by kind. */
class Stats {
  rt = new Map<string, number[]>();
  errors = new Map<string, number>();
  timeouts = 0;
  bytesIn = 0;
  eventsIn = new Map<string, { n: number; bytes: number }>();
  add(kind: string, ms: number) {
    let a = this.rt.get(kind);
    if (!a) this.rt.set(kind, (a = []));
    a.push(ms);
  }
  err(what: string) {
    this.errors.set(what, (this.errors.get(what) ?? 0) + 1);
  }
}
let stats = new Stats();
let running = false;

const pct = (a: number[], p: number) => {
  if (!a.length) return 0;
  const s = [...a].sort((x, y) => x - y);
  return Math.round(s[Math.min(s.length - 1, Math.floor((p / 100) * s.length))] * 10) / 10;
};

async function http(cookie: string, method: string, path: string, body?: unknown): Promise<{ status: number; json: Record<string, unknown>; setCookie: string | null }> {
  const t = performance.now();
  const res = await fetch(`${BASE}/api${path}`, {
    method,
    headers: { 'content-type': 'application/json', 'x-requested-with': 'ludo3d', ...(cookie ? { cookie } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = (await res.json().catch(() => ({}))) as Record<string, unknown>;
  stats.add(`http ${path.split('?')[0].replace(/\/[0-9a-f-]{36}.*/, '/:id')}`, performance.now() - t);
  if (res.status >= 500) stats.err(`http ${res.status} ${path}`);
  return { status: res.status, json, setCookie: res.headers.get('set-cookie') };
}

let seq = 0;
async function register(name: string, email?: string): Promise<{ cookie: string; id: string }> {
  const addr = email ?? `lt${Date.now()}_${++seq}@example.com`;
  const r = await http('', 'POST', '/auth/register', { fullName: name, email: addr, password: 'goodPass123', confirmPassword: 'goodPass123' });
  let set = r.setCookie;
  if (r.status !== 201) {
    const l = await http('', 'POST', '/auth/login', { email: addr, password: 'goodPass123' });
    if (l.status !== 200) throw new Error(`cannot sign in ${addr}: ${JSON.stringify(l.json)}`);
    set = l.setCookie;
  }
  const cookie = `ludo_sid=${/ludo_sid=([^;]*)/.exec(set ?? '')?.[1]}`;
  const me = await http(cookie, 'GET', '/auth/me');
  return { cookie, id: (me.json.user as { id: string }).id };
}

type Ack = { ok: boolean; error?: string; [k: string]: unknown };

class Player {
  socket: Socket | null = null;
  stopped = false;
  room: { seats: { id: string; kind: string; left?: boolean }[]; you: number; matchId: string; code: string } | null = null;
  state: Parameters<typeof distinctMoves>[0] | null = null;
  timers = new Set<NodeJS.Timeout>();
  voiceTimer: NodeJS.Timeout | null = null;
  friendRoom: string | null = null;
  lobbyHost = false;
  partner: Player | null = null;

  constructor(public cookie: string, public id: string, public name: string, public mode: string, public talks: boolean) {}

  emit(ev: string, payload: unknown): Promise<Ack> {
    return new Promise((resolve) => {
      const s = this.socket;
      if (!s?.connected) return resolve({ ok: false, error: 'offline' });
      const t = performance.now();
      const timer = setTimeout(() => {
        if (running) stats.timeouts++;
        resolve({ ok: false, error: 'timeout' });
      }, 10_000);
      s.emit(ev, payload, (a: Ack) => {
        clearTimeout(timer);
        stats.add(ev, performance.now() - t);
        if (!a.ok && running && !/turn|moved on|Wait a moment|cooling|too fast|Not enough|Insufficient|offline|is not your|not in a room|during a match|already|No match|No such|cannot/i.test(a.error ?? '')) stats.err(`${ev}: ${a.error}`);
        resolve(a);
      });
    });
  }

  later(ms: number, fn: () => void): void {
    const t = setTimeout(() => {
      this.timers.delete(t);
      if (!this.stopped) fn();
    }, ms);
    this.timers.add(t);
  }

  connect(): Promise<void> {
    return new Promise((resolve) => {
      const s = io(BASE, { transports: ['websocket'], forceNew: true, reconnection: false, extraHeaders: { cookie: this.cookie } });
      this.socket = s;
      s.onAny((ev: string, ...args: unknown[]) => {
        if (!running) return;
        const b = args[0] instanceof Buffer || args[1] instanceof Buffer ? 300 : JSON.stringify(args).length;
        stats.bytesIn += b;
        const e = stats.eventsIn.get(ev) ?? { n: 0, bytes: 0 };
        e.n++;
        e.bytes += b;
        stats.eventsIn.set(ev, e);
      });
      s.on('room:state', (r) => {
        this.room = r;
        if (r.status === 'over') this.later(rand(1500, 4000), () => void this.next());
      });
      s.on('game:update', (u: { state: Parameters<typeof distinctMoves>[0]; deadline: number | null }) => {
        this.state = u.state;
        this.act(u.deadline !== null);
      });
      s.on('room:closed', () => {
        this.room = null;
        this.later(1000, () => void this.next());
      });
      s.on('connect', () => resolve());
      s.on('connect_error', (e) => {
        if (running) stats.err(`connect: ${e.message}`);
        resolve();
      });
    });
  }

  myColor(): string | null {
    const r = this.room as unknown as { seats: { color: string }[]; you: number } | null;
    return r && r.you >= 0 ? r.seats[r.you]?.color ?? null : null;
  }

  /** On my turn, take a human moment to tap. */
  act(live: boolean): void {
    const g = this.state;
    if (!g || !live || g.phase === 'over') return;
    if (g.players[g.current].color !== this.myColor()) return;
    const seq = g.seq;
    this.later(rand(900, 2400), () => {
      const now = this.state;
      if (!now || now.seq !== seq) return;
      if (now.phase === 'roll') void this.emit('game:roll', { seq });
      else if (now.phase === 'move') {
        const moves = distinctMoves(now);
        if (moves.length) void this.emit('game:move', { token: moves[Math.floor(Math.random() * moves.length)], seq });
      }
    });
  }

  /** Into the next match. */
  async next(): Promise<void> {
    if (this.stopped) return;
    this.state = null;
    this.room = null;
    await this.emit('room:leave', { results: true });
    if (this.partner) {
      // Friends play private free rooms together, and talk.
      if (this.lobbyHost) {
        const r = await this.emit('room:create', { modeId: 'friendly', maxPlayers: 2 });
        const code = (r.room as { code: string } | undefined)?.code ?? null;
        this.friendRoom = code;
        this.partner.friendRoom = code;
        if (code) this.partner.later(500, () => void this.partner?.joinFriend());
      }
      return;
    }
    await this.emit('room:quick', { modeId: this.mode });
  }

  async joinFriend(): Promise<void> {
    if (!this.friendRoom) return;
    await this.emit('room:join', { code: this.friendRoom });
    await this.emit('room:ready', { ready: true });
    const host = this.partner;
    host?.later(800, () => void host.emit('room:start', {}));
  }

  background(): void {
    // Chat, emotes, gifts, the odd look at the wallet and history; dropped connections.
    const every = (min: number, max: number, fn: () => void) => {
      const go = () => this.later(rand(min, max), () => {
        fn();
        go();
      });
      go();
    };
    every(20_000, 45_000, () => {
      if (this.room) void this.emit('chat:send', { text: 'Good game!', quick: true });
    });
    every(15_000, 40_000, () => {
      if (this.room?.seats) void this.emit('emote:send', { id: 'joy' });
    });
    every(60_000, 150_000, () => {
      const other = this.room?.seats?.find((s, i) => i !== this.room!.you && s.kind === 'human');
      if (other && this.state) void this.emit('playerGift:send', { productId: 'golden_rose', toSeatId: other.id, key: randomUUID() });
    });
    every(30_000, 75_000, () => {
      const pick = ['/wallet', '/friends', '/leaderboard', '/notifications', '/wallet/transactions', '/player-gifts?dir=received', '/player-gifts/catalog'];
      void http(this.cookie, 'GET', pick[Math.floor(Math.random() * pick.length)]).catch(() => stats.err('http failed'));
    });
    every(70_000, 160_000, () => void this.blip());
    // Some matches end early: someone leaves, the match settles and everyone queues again.
    every(50_000, 140_000, () => {
      if (Math.random() < 0.25 && this.state) void this.next();
    });
    if (this.talks) {
      const pkt = Buffer.alloc(300, 7);
      this.voiceTimer = setInterval(() => {
        if (this.socket?.connected && this.room) this.socket.emit('voice:frame', pkt);
      }, 100);
    }
  }

  /** A short loss of connection: back within a few seconds, inside the reconnection window. */
  async blip(): Promise<void> {
    this.socket?.close();
    await sleep(rand(800, 4000));
    if (this.stopped) return;
    await this.connect();
    const r = await this.emit('session:hello', { results: true });
    if (r.room) {
      this.room = r.room as Player['room'];
    } else if (!this.partner) {
      void this.next();
    }
  }

  stop(): void {
    this.stopped = true;
    for (const t of this.timers) clearTimeout(t);
    if (this.voiceTimer) clearInterval(this.voiceTimer);
    this.socket?.close();
  }
}

async function befriend(pairs: [Player, Player][]): Promise<void> {
  if (!MYSQL || !pairs.length) return;
  const mysql = await import('mysql2/promise');
  const c = await mysql.createConnection(MYSQL);
  for (const [a, b] of pairs) {
    const [x, y] = a.id < b.id ? [a.id, b.id] : [b.id, a.id];
    await c.query('insert ignore into friendships (user_a, user_b) values (?, ?)', [x, y]);
  }
  await c.end();
}

async function run(n: number, admin: string): Promise<Record<string, unknown>> {
  stats = new Stats();
  const players: Player[] = [];
  for (let i = 0; i < n; i++) {
    const { cookie, id } = await register(`Load ${i}`);
    // A fifth are friends in pairs who talk; of the rest most play 1 vs 1, some 4-player matches.
    const friend = i < Math.floor(n / 5 / 2) * 2;
    const mode = friend ? 'friendly' : Math.random() < 0.7 ? 'quick' : 'classic';
    players.push(new Player(cookie, id, `Load ${i}`, mode, friend));
  }
  const pairs: [Player, Player][] = [];
  for (let i = 0; i + 1 < n && players[i + 1].talks; i += 2) {
    players[i].partner = players[i + 1];
    players[i + 1].partner = players[i];
    players[i].lobbyHost = true;
    pairs.push([players[i], players[i + 1]]);
  }
  await befriend(pairs);
  running = true;
  // Arrive over a minute, not all in one instant.
  const arrive = Math.min(60_000, n * 500);
  await Promise.all(players.map(async (p) => {
    await sleep(Math.random() * arrive);
    await p.connect();
    await p.emit('session:hello', { results: true });
    p.background();
    if (p.partner && !p.lobbyHost) return;
    await p.next();
  }));
  // Sample once everyone is in.
  const samples: Record<string, unknown>[] = [];
  await http(admin, 'GET', '/admin/health');
  const t0 = Date.now();
  const before = new Map(stats.rt);
  stats.rt = new Map();
  stats.bytesIn = 0;
  stats.eventsIn = new Map();
  void before;
  while (Date.now() - t0 < SECONDS * 1000) {
    await sleep(5000);
    const h = await http(admin, 'GET', '/admin/health');
    samples.push(h.json);
  }
  const secs = (Date.now() - t0) / 1000;
  const procs = samples.map((s) => s.process as { cpuPercent: number; rssMb: number; heapUsedMb: number; lagMs: { p50: number; p99: number; max: number } });
  const pings = samples.map((s) => Number(s.dbPingMs ?? 0));
  const rt: Record<string, { n: number; p50: number; p95: number; p99: number }> = {};
  for (const [k, v] of stats.rt) rt[k] = { n: v.length, p50: pct(v, 50), p95: pct(v, 95), p99: pct(v, 99) };
  const result = {
    players: n,
    seconds: Math.round(secs),
    sockets: Math.max(...samples.map((s) => Number(s.sockets ?? 0))),
    rooms: Math.max(...samples.map((s) => Number(s.rooms ?? 0))),
    cpuPercentAvg: Math.round(procs.reduce((a, p) => a + p.cpuPercent, 0) / Math.max(1, procs.length)),
    cpuPercentMax: Math.max(...procs.map((p) => p.cpuPercent)),
    rssMbMax: Math.max(...procs.map((p) => p.rssMb)),
    heapFirstLast: [procs[0]?.heapUsedMb, procs[procs.length - 1]?.heapUsedMb],
    lagP99Max: Math.max(...procs.map((p) => p.lagMs.p99)),
    lagMaxMax: Math.max(...procs.map((p) => p.lagMs.max)),
    dbPingP95: pct(pings, 95),
    downstreamKBpsTotal: Math.round(stats.bytesIn / 1024 / secs),
    timeouts: stats.timeouts,
    errors: Object.fromEntries(stats.errors),
    roundTrips: rt,
    eventsIn: Object.fromEntries([...stats.eventsIn].map(([k, v]) => [k, { perSec: Math.round((v.n / secs) * 10) / 10, avgBytes: Math.round(v.bytes / v.n) }])),
  };
  running = false;
  for (const p of players) p.stop();
  await sleep(6000);
  return result;
}

async function main(): Promise<void> {
  const { cookie: admin } = await register('Load Admin', 'loadtest-admin@example.com');
  const all: Record<string, unknown>[] = [];
  for (const n of LEVELS) {
    console.log(`--- ${n} players, ${SECONDS}s`);
    const r = await run(n, admin);
    all.push(r);
    const rt = r.roundTrips as Record<string, { p50: number; p95: number; p99: number }>;
    console.log(JSON.stringify({ players: n, cpuAvg: r.cpuPercentAvg, cpuMax: r.cpuPercentMax, rssMb: r.rssMbMax, lagP99: r.lagP99Max, lagMax: r.lagMaxMax, dbPing95: r.dbPingP95, kBps: r.downstreamKBpsTotal, timeouts: r.timeouts, roll: rt['game:roll'], quick: rt['room:quick'], gift: rt['playerGift:send'], errors: r.errors }));
    if (OUT) writeFileSync(OUT, JSON.stringify(all, null, 1));
  }
  process.exit(0);
}

void main();
