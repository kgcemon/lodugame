import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError, api, fmt, get, post } from '../../lib/api';
import { refreshMe, refreshWallet, useAuth } from '../../store/auth';
import { useGame } from '../../store/game';
import { useSettings, type CameraPreset, type Quality } from '../../store/settings';
import { Avatar, Button, Segmented, Slider, Toggle } from '../common';
import { IconCheck, IconClose, IconFlag, IconGift, IconHistory, IconSearch, IconTrophy, IconUsers } from '../icons';
import { Card, CoinIcon, Coins, Confirm, Empty, ErrorNote, Field, PageTitle, Pager, Spinner, timeAgo, useFetch } from './kit';
import { ModeCard, PlayNowButton, useEntryFlow, useModes } from './PlayPages';
import { passwordIssue } from './AuthPages';

/** Player pages backed by the REST API. All numbers come from the database. */

interface Stats {
  matches: number;
  wins: number;
  losses: number;
  winRate: number;
  rank: number;
  seasonPoints: number;
  coinsEarned: number;
  recent: ('W' | 'L')[];
}

interface HistoryItem {
  id: string;
  modeName: string;
  entryFee: number;
  date: string;
  result: 'won' | 'lost' | 'forfeited' | 'refunded' | 'abandoned' | 'in_progress';
  rank: number | null;
  players: number;
  coinsPaid: number;
  coinsReceived: number;
  net: number;
  disputed: boolean;
}

interface Friend {
  username: string;
  publicId: string;
  avatarHue: number;
  online: boolean;
  since: string;
  room: { code: string; modeName: string; entryFee: number; players: number; maxPlayers: number; joinable: boolean; status: string } | null;
}

const RESULT_LABEL: Record<HistoryItem['result'], string> = {
  won: 'Won',
  lost: 'Lost',
  forfeited: 'Forfeited',
  refunded: 'Refunded',
  abandoned: 'Abandoned',
  in_progress: 'In progress',
};

function StatTile({ label, value, accent }: { label: string; value: React.ReactNode; accent?: boolean }) {
  return (
    <div className={`stat-tile glass ${accent ? 'accent' : ''}`}>
      <span className="muted">{label}</span>
      <b>{value}</b>
    </div>
  );
}

// ---------------------------------------------------------------- dashboard

export function Dashboard() {
  const user = useAuth((s) => s.user)!;
  const wallet = useAuth((s) => s.wallet);
  const version = useAuth((s) => s.friendsVersion);
  const modes = useModes();
  const flow = useEntryFlow();
  const nav = useNavigate();
  const stats = useFetch(() => get<{ stats: Stats }>('/me/stats').then((r) => r.stats), []);
  const history = useFetch(() => get<{ items: HistoryItem[] }>('/matches/history').then((r) => r.items.slice(0, 5)), []);
  const friends = useFetch(() => get<{ friends: Friend[] }>('/friends').then((r) => r.friends.filter((f) => f.online)), [version]);
  const quick = modes.find((m) => m.id === 'quick') ?? modes[0];
  const first = user.fullName.split(' ')[0];

  return (
    <div className="page dashboard">
      <section className="hero">
        <div>
          <p className="muted">Welcome back,</p>
          <h1>{first} 👋</h1>
          {!user.emailVerified && (
            <p className="verify-note">
              Please verify your email.{' '}
              <button className="linkish" onClick={() => post('/auth/resend-verification').then(() => useGame.getState().toast('Verification email sent'), (e: Error) => useGame.getState().toast(e.message))}>
                Resend link
              </button>
            </p>
          )}
        </div>
        <div className="hero-balance glass">
          <span className="muted">Current balance</span>
          <Coins value={wallet?.balance ?? 0} size={30} />
          {wallet?.dailyBonus && !wallet.dailyBonus.claimedToday && wallet.dailyBonus.amount > 0 && (
            <Button size="sm" variant="primary" icon={<IconGift size={16} />} onClick={() => nav('/wallet')}>
              Free {wallet.dailyBonus.amount} coins
            </Button>
          )}
        </div>
      </section>

      {quick && <PlayNowButton onPlay={() => flow.start({ kind: 'quick', mode: quick })} />}

      <div className="stat-row">
        <StatTile label="Win rate" value={stats.data ? `${stats.data.winRate}%` : '—'} accent />
        <StatTile label="Total matches" value={stats.data?.matches ?? '—'} />
        <StatTile label="Total wins" value={stats.data?.wins ?? '—'} />
        <StatTile label="Current rank" value={stats.data ? `#${stats.data.rank}` : '—'} />
      </div>

      <h2 className="section-title">Choose a match</h2>
      <div className="mode-grid">
        {modes.map((m) => (
          <ModeCard key={m.id} mode={m} onJoin={() => flow.start({ kind: 'quick', mode: m })} />
        ))}
      </div>

      <div className="two-col">
        <Card title="Friends online" action={<Link to="/friends">All friends</Link>}>
          {friends.loading ? (
            <Spinner />
          ) : friends.data?.length ? (
            <ul className="friend-mini">
              {friends.data.slice(0, 6).map((f) => (
                <li key={f.publicId}>
                  <Avatar name={f.username} hue={f.avatarHue} size={30} />
                  <span>{f.username}</span>
                  <i className="dot on" aria-label="online" />
                  {f.room?.joinable ? (
                    <Button size="sm" onClick={() => nav(`/join?code=${f.room!.code}`)}>
                      Join room
                    </Button>
                  ) : (
                    <span className="muted small">{f.room ? 'In a match' : 'Online'}</span>
                  )}
                </li>
              ))}
            </ul>
          ) : (
            <Empty icon={<IconUsers size={28} />} title="No friends online">
              <Link to="/friends">Find friends</Link> to play together.
            </Empty>
          )}
        </Card>
        <Card title="Recent matches" action={<Link to="/history">History</Link>}>
          {history.loading ? (
            <Spinner />
          ) : history.data?.length ? (
            <ul className="recent">
              {history.data.map((m) => (
                <li key={m.id}>
                  <span className={`result-pill r-${m.result}`}>{RESULT_LABEL[m.result]}</span>
                  <span>{m.modeName}</span>
                  <span className={`net ${m.net > 0 ? 'pos' : m.net < 0 ? 'neg' : ''}`}>
                    {m.net > 0 ? '+' : ''}
                    {fmt(m.net)}
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <Empty icon={<IconHistory size={28} />} title="No matches yet">
              Press Play Now to start your first match.
            </Empty>
          )}
        </Card>
      </div>
      {flow.ui}
    </div>
  );
}

// ---------------------------------------------------------------- wallet

interface Tx {
  id: string;
  type: string;
  amount: number;
  balance_before: number;
  balance_after: number;
  match_id: string | null;
  note: string | null;
  created_at: string;
}

const TX_LABEL: Record<string, string> = {
  welcome_bonus: 'Welcome Bonus',
  match_entry: 'Match Entry Fee',
  match_reward: 'Match Reward',
  match_refund: 'Match Refund',
  daily_bonus: 'Daily Bonus',
  referral_reward: 'Referral Reward',
  admin_adjustment: 'Administrator Adjustment',
};

export function WalletPage() {
  const wallet = useAuth((s) => s.wallet);
  const [page, setPage] = useState(1);
  const [type, setType] = useState('');
  const [claiming, setClaiming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const tx = useFetch(() => get<{ items: Tx[]; total: number; pageSize: number }>(`/wallet/transactions?page=${page}${type ? `&type=${type}` : ''}`), [page, type, wallet?.balance]);
  useEffect(() => {
    void refreshWallet();
  }, []);

  const claim = async () => {
    setClaiming(true);
    setError(null);
    try {
      const r = await post<{ wallet: typeof wallet }>('/wallet/daily-bonus');
      useAuth.setState({ wallet: r.wallet });
      useGame.getState().toast('Daily bonus collected!');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setClaiming(false);
    }
  };

  const daily = wallet?.dailyBonus;
  return (
    <div className="page">
      <PageTitle title="Wallet" subtitle="Virtual coins for entering matches. Coins have no cash value." />
      <div className="wallet-top">
        <div className="balance-card glass">
          <span className="muted">Current Balance</span>
          <Coins value={wallet?.balance ?? 0} size={40} className="big" />
          <span className="muted">Coins</span>
        </div>
        <StatTile label="Total coins earned" value={<span className="coins"><CoinIcon size={18} /> {fmt(wallet?.totalEarned ?? 0)}</span>} />
        <StatTile label="Total coins spent" value={<span className="coins"><CoinIcon size={18} /> {fmt(wallet?.totalSpent ?? 0)}</span>} />
        <div className="daily glass">
          <IconGift size={28} />
          <div>
            <b>Daily bonus</b>
            <span className="muted">
              {!daily || daily.amount <= 0
                ? 'Not available right now'
                : daily.claimedToday
                  ? `Collected today · next at ${new Date(daily.nextAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`
                  : `${daily.amount} free coins every day`}
            </span>
          </div>
          <Button variant="primary" disabled={!daily || daily.claimedToday || daily.amount <= 0 || claiming} onClick={claim}>
            {claiming ? 'Collecting…' : daily?.claimedToday ? 'Collected' : 'Collect'}
          </Button>
        </div>
      </div>
      <ErrorNote error={error} />
      <Card
        title="Transactions"
        action={
          <select className="input select" value={type} onChange={(e) => (setType(e.target.value), setPage(1))} aria-label="Filter transactions">
            <option value="">All types</option>
            {Object.entries(TX_LABEL).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
        }
      >
        {tx.loading && !tx.data ? (
          <Spinner />
        ) : tx.error ? (
          <ErrorNote error={tx.error} />
        ) : tx.data?.items.length ? (
          <>
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>Type</th>
                    <th className="num">Amount</th>
                    <th className="num">Before</th>
                    <th className="num">After</th>
                    <th>Date</th>
                    <th>Transaction ID</th>
                  </tr>
                </thead>
                <tbody>
                  {tx.data.items.map((t) => (
                    <tr key={t.id}>
                      <td>
                        {TX_LABEL[t.type] ?? t.type}
                        {t.note && t.type === 'admin_adjustment' && <small className="muted block">{t.note}</small>}
                      </td>
                      <td className={`num ${t.amount > 0 ? 'pos' : 'neg'}`}>
                        {t.amount > 0 ? '+' : ''}
                        {fmt(t.amount)}
                      </td>
                      <td className="num muted">{fmt(t.balance_before)}</td>
                      <td className="num">{fmt(t.balance_after)}</td>
                      <td>{new Date(t.created_at).toLocaleString()}</td>
                      <td className="mono" title={t.id}>
                        {t.id.slice(0, 8)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager page={page} total={tx.data.total} pageSize={tx.data.pageSize} onPage={setPage} />
          </>
        ) : (
          <Empty title="No transactions yet" />
        )}
      </Card>
    </div>
  );
}

// ---------------------------------------------------------------- history

export function HistoryPage() {
  const [page, setPage] = useState(1);
  const [reporting, setReporting] = useState<HistoryItem | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const stats = useFetch(() => get<{ stats: Stats }>('/me/stats').then((r) => r.stats), []);
  const list = useFetch(() => get<{ items: HistoryItem[]; total: number; pageSize: number }>(`/matches/history?page=${page}`), [page]);

  const report = async () => {
    if (!reporting) return;
    setBusy(true);
    setErr(null);
    try {
      await post(`/matches/${reporting.id}/dispute`, { reason });
      useGame.getState().toast('Report sent to the administrators');
      setReporting(null);
      setReason('');
      list.reload();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <PageTitle title="Match History" subtitle="Every online match you joined, with the coins it cost and paid." />
      {stats.data && (
        <div className="stat-row">
          <StatTile label="Total matches" value={stats.data.matches} />
          <StatTile label="Wins" value={stats.data.wins} accent />
          <StatTile label="Losses" value={stats.data.losses} />
          <StatTile label="Win %" value={`${stats.data.winRate}%`} />
          <StatTile label="Coins earned" value={fmt(stats.data.coinsEarned)} />
          <StatTile label="Rank" value={`#${stats.data.rank}`} />
        </div>
      )}
      {stats.data?.recent.length ? (
        <div className="recent-form" aria-label="Recent results">
          {stats.data.recent.map((r, i) => (
            <span key={i} className={`form-dot ${r === 'W' ? 'w' : 'l'}`}>
              {r}
            </span>
          ))}
        </div>
      ) : null}
      <Card>
        {list.loading && !list.data ? (
          <Spinner />
        ) : list.data?.items.length ? (
          <>
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>Match</th>
                    <th>Mode</th>
                    <th className="num">Entry fee</th>
                    <th>Date</th>
                    <th>Result</th>
                    <th className="num">Paid</th>
                    <th className="num">Received</th>
                    <th className="num">Net</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {list.data.items.map((m) => (
                    <tr key={m.id}>
                      <td className="mono" title={m.id}>
                        {m.id.slice(0, 8)}
                      </td>
                      <td>{m.modeName}</td>
                      <td className="num">{m.entryFee ? fmt(m.entryFee) : 'Free'}</td>
                      <td>{new Date(m.date).toLocaleString()}</td>
                      <td>
                        <span className={`result-pill r-${m.result}`}>{RESULT_LABEL[m.result]}</span>
                        {m.rank ? <small className="muted"> #{m.rank}</small> : null}
                      </td>
                      <td className="num">{fmt(m.coinsPaid)}</td>
                      <td className="num">{fmt(m.coinsReceived)}</td>
                      <td className={`num ${m.net > 0 ? 'pos' : m.net < 0 ? 'neg' : ''}`}>
                        {m.net > 0 ? '+' : ''}
                        {fmt(m.net)}
                      </td>
                      <td>
                        {!m.disputed && m.result !== 'in_progress' && (
                          <button className="icon-link" aria-label="Report a problem with this match" title="Report a problem" onClick={() => setReporting(m)}>
                            <IconFlag size={16} />
                          </button>
                        )}
                        {m.disputed && <span className="tag">Reported</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager page={page} total={list.data.total} pageSize={list.data.pageSize} onPage={setPage} />
          </>
        ) : (
          <Empty icon={<IconHistory size={28} />} title="No matches yet" />
        )}
      </Card>
      {reporting && (
        <Confirm title="Report a problem" confirmLabel="Send report" busy={busy} onConfirm={report} onClose={() => setReporting(null)}>
          <p className="muted">
            {reporting.modeName} · {new Date(reporting.date).toLocaleString()}. An administrator will review it.
          </p>
          <textarea className="input" rows={4} maxLength={500} value={reason} placeholder="What went wrong?" onChange={(e) => setReason(e.target.value)} />
          <ErrorNote error={err} />
        </Confirm>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- leaderboard

interface LbRow {
  position: number;
  username: string;
  publicId: string;
  avatarHue: number;
  wins: number;
  matches: number;
  winRate: number;
  seasonPoints: number;
  balance: number;
  isYou: boolean;
}

export function LeaderboardPage() {
  const [sort, setSort] = useState<'wins' | 'winrate' | 'season'>('wins');
  const [page, setPage] = useState(1);
  const lb = useFetch(() => get<{ items: LbRow[]; total: number; pageSize: number; minMatches: number }>(`/leaderboard?sort=${sort}&page=${page}`), [sort, page]);
  return (
    <div className="page">
      <PageTitle title="Leaderboard" subtitle="Ranked from completed online matches." />
      <Segmented
        value={sort}
        options={[
          { value: 'wins', label: 'Wins' },
          { value: 'winrate', label: 'Win rate' },
          { value: 'season', label: 'This season' },
        ]}
        onChange={(v) => (setSort(v), setPage(1))}
      />
      {sort === 'winrate' && lb.data && <p className="hint">Players need at least {lb.data.minMatches} matches to appear here.</p>}
      {sort === 'season' && <p className="hint">Season = this calendar month. 3 points per win, 1 per match played.</p>}
      <Card>
        {lb.loading && !lb.data ? (
          <Spinner />
        ) : lb.data?.items.length ? (
          <>
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>Rank</th>
                    <th>Player</th>
                    <th className="num">Wins</th>
                    <th className="num">Win rate</th>
                    <th className="num">{sort === 'season' ? 'Points' : 'Matches'}</th>
                    <th className="num">Coins</th>
                  </tr>
                </thead>
                <tbody>
                  {lb.data.items.map((r) => (
                    <tr key={r.publicId} className={r.isYou ? 'you' : ''}>
                      <td>
                        <span className={`rank-badge r${r.position}`}>{r.position}</span>
                      </td>
                      <td>
                        <Link className="player-link" to={`/player/${r.publicId}`}>
                          <Avatar name={r.username} hue={r.avatarHue} size={28} />
                          {r.username}
                          {r.isYou && <em className="muted"> (you)</em>}
                        </Link>
                      </td>
                      <td className="num">{r.wins}</td>
                      <td className="num">{r.winRate}%</td>
                      <td className="num">{sort === 'season' ? r.seasonPoints : r.matches}</td>
                      <td className="num">{fmt(r.balance)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager page={page} total={lb.data.total} pageSize={lb.data.pageSize} onPage={setPage} />
          </>
        ) : (
          <Empty icon={<IconTrophy size={28} />} title="No ranked players yet">
            Win a match to be the first on the board.
          </Empty>
        )}
      </Card>
    </div>
  );
}

export function PlayerPage({ publicId }: { publicId: string }) {
  const p = useFetch(
    () =>
      get<{ player: { username: string; publicId: string; avatarHue: number; joinedAt: string; online: boolean; stats: Stats } }>(
        `/players/${encodeURIComponent(publicId)}`,
      ).then((r) => r.player),
    [publicId],
  );
  if (p.loading) return <Spinner />;
  if (!p.data) return <Empty title={p.error ?? 'Player not found'} />;
  const s = p.data.stats;
  return (
    <div className="page narrow">
      <Card>
        <div className="profile-hero">
          <Avatar name={p.data.username} hue={p.data.avatarHue} size={72} />
          <div>
            <strong>{p.data.username}</strong>
            <p className="muted">
              ID {p.data.publicId} · joined {new Date(p.data.joinedAt).toLocaleDateString()} · {p.data.online ? 'online' : 'offline'}
            </p>
          </div>
        </div>
        <div className="stat-row">
          <StatTile label="Matches" value={s.matches} />
          <StatTile label="Wins" value={s.wins} accent />
          <StatTile label="Win %" value={`${s.winRate}%`} />
          <StatTile label="Rank" value={`#${s.rank}`} />
        </div>
      </Card>
    </div>
  );
}

// ---------------------------------------------------------------- friends

interface SearchRow {
  username: string;
  publicId: string;
  avatarHue: number;
  isFriend: boolean;
  pending: 'incoming' | 'outgoing' | null;
  online: boolean;
}

interface Req {
  id: string;
  createdAt: string;
  player: { username: string; publicId: string; avatarHue: number };
}

export function FriendsPage() {
  const version = useAuth((s) => s.friendsVersion);
  const me = useAuth((s) => s.user)!;
  const nav = useNavigate();
  const [q, setQ] = useState('');
  const [results, setResults] = useState<SearchRow[] | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [removing, setRemoving] = useState<Friend | null>(null);
  const friends = useFetch(() => get<{ friends: Friend[] }>('/friends').then((r) => r.friends), [version]);
  const reqs = useFetch(() => get<{ incoming: Req[]; outgoing: Req[] }>('/friends/requests'), [version]);
  const room = useGame((s) => s.room);

  const search = async (e?: FormEvent) => {
    e?.preventDefault();
    if (q.trim().length < 2) return setResults([]);
    try {
      const r = await get<{ players: SearchRow[] }>(`/players/search?q=${encodeURIComponent(q.trim())}`);
      setResults(r.players);
    } catch (err) {
      setMsg((err as Error).message);
    }
  };

  const act = async (fn: () => Promise<unknown>, ok?: string) => {
    setMsg(null);
    try {
      await fn();
      if (ok) useGame.getState().toast(ok);
      friends.reload();
      reqs.reload();
      if (results) void search();
    } catch (e) {
      setMsg((e as Error).message);
    }
  };

  return (
    <div className="page">
      <PageTitle title="Friends" subtitle={<>Your player ID: <b className="mono">{me.publicId}</b> · username <b>{me.username}</b></>} />
      <ErrorNote error={msg} />
      <div className="two-col">
        <Card title="Find players">
          <form className="join-row" onSubmit={search}>
            <input className="input" placeholder="Username or player ID" value={q} maxLength={20} onChange={(e) => setQ(e.target.value)} aria-label="Search players" />
            <Button type="submit" icon={<IconSearch size={18} />}>
              Search
            </Button>
          </form>
          {results && (
            <ul className="friend-mini">
              {results.length === 0 && <li className="muted">No players found.</li>}
              {results.map((p) => (
                <li key={p.publicId}>
                  <Avatar name={p.username} hue={p.avatarHue} size={30} />
                  <Link to={`/player/${p.publicId}`}>{p.username}</Link>
                  <i className={`dot ${p.online ? 'on' : ''}`} />
                  {p.isFriend ? (
                    <span className="tag">Friend</span>
                  ) : p.pending === 'outgoing' ? (
                    <span className="tag">Requested</span>
                  ) : (
                    <Button size="sm" variant="primary" onClick={() => act(() => post('/friends/requests', { player: p.publicId }), p.pending === 'incoming' ? 'Friend added' : 'Friend request sent')}>
                      {p.pending === 'incoming' ? 'Accept' : 'Add friend'}
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </Card>
        <Card title={`Requests${reqs.data?.incoming.length ? ` (${reqs.data.incoming.length})` : ''}`}>
          {reqs.data && reqs.data.incoming.length + reqs.data.outgoing.length === 0 && <p className="muted">No pending requests.</p>}
          <ul className="friend-mini">
            {reqs.data?.incoming.map((r) => (
              <li key={r.id}>
                <Avatar name={r.player.username} hue={r.player.avatarHue} size={30} />
                <span>{r.player.username}</span>
                <span className="muted small">{timeAgo(r.createdAt)}</span>
                <Button size="sm" variant="primary" icon={<IconCheck size={14} />} onClick={() => act(() => post(`/friends/requests/${r.id}/accept`), 'Friend added')}>
                  Accept
                </Button>
                <Button size="sm" icon={<IconClose size={14} />} onClick={() => act(() => post(`/friends/requests/${r.id}/reject`))}>
                  Reject
                </Button>
              </li>
            ))}
            {reqs.data?.outgoing.map((r) => (
              <li key={r.id}>
                <Avatar name={r.player.username} hue={r.player.avatarHue} size={30} />
                <span>{r.player.username}</span>
                <span className="muted small">sent {timeAgo(r.createdAt)}</span>
                <Button size="sm" onClick={() => act(() => post(`/friends/requests/${r.id}/cancel`))}>
                  Cancel
                </Button>
              </li>
            ))}
          </ul>
        </Card>
      </div>
      <Card title={`Your friends${friends.data ? ` (${friends.data.length})` : ''}`}>
        {friends.loading && !friends.data ? (
          <Spinner />
        ) : friends.data?.length ? (
          <ul className="friend-list">
            {friends.data.map((f) => (
              <li key={f.publicId} className="glass">
                <Avatar name={f.username} hue={f.avatarHue} size={40} />
                <div className="friend-info">
                  <Link to={`/player/${f.publicId}`}>{f.username}</Link>
                  <span className="muted small">
                    <i className={`dot ${f.online ? 'on' : ''}`} /> {f.online ? (f.room ? `In a ${f.room.modeName} room` : 'Online') : 'Offline'}
                  </span>
                </div>
                <div className="friend-actions">
                  {f.room?.joinable && (
                    <Button size="sm" variant="primary" onClick={() => nav(`/join?code=${f.room!.code}`)}>
                      Join room
                    </Button>
                  )}
                  {room && room.status === 'lobby' && f.online && (
                    <Button size="sm" onClick={() => act(() => post('/friends/invite', { publicId: f.publicId }), `Invite sent to ${f.username}`)}>
                      Invite
                    </Button>
                  )}
                  <Button size="sm" variant="ghost" onClick={() => setRemoving(f)}>
                    Remove
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        ) : (
          <Empty icon={<IconUsers size={28} />} title="No friends yet">
            Search for a player above, or share your ID <b className="mono">{me.publicId}</b>.
          </Empty>
        )}
      </Card>
      {removing && (
        <Confirm
          title="Remove friend"
          confirmLabel="Remove"
          onClose={() => setRemoving(null)}
          onConfirm={() => {
            const f = removing;
            setRemoving(null);
            void act(() => api('DELETE', `/friends/${f.publicId}`), `${f.username} removed`);
          }}
        >
          <p>Remove {removing.username} from your friends?</p>
        </Confirm>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- profile & settings

export function ProfilePage() {
  const user = useAuth((s) => s.user)!;
  const [fullName, setFullName] = useState(user.fullName);
  const [username, setUsername] = useState(user.username);
  const [hue, setHue] = useState(user.avatarHue);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const stats = useFetch(() => get<{ stats: Stats }>('/me/stats').then((r) => r.stats), []);

  const save = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api('PATCH', '/profile', { fullName, username, avatarHue: hue });
      await refreshMe();
      useGame.getState().toast('Profile saved');
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page narrow">
      <PageTitle title="Profile" />
      <Card>
        <div className="profile-hero">
          <Avatar name={username} hue={hue} size={72} />
          <div>
            <strong>{username}</strong>
            <p className="muted">
              Player ID <b className="mono">{user.publicId}</b> · {user.email}
              {user.emailVerified ? ' · verified' : ' · not verified'}
            </p>
          </div>
        </div>
        {stats.data && (
          <div className="stat-row">
            <StatTile label="Matches" value={stats.data.matches} />
            <StatTile label="Wins" value={stats.data.wins} accent />
            <StatTile label="Win %" value={`${stats.data.winRate}%`} />
            <StatTile label="Rank" value={`#${stats.data.rank}`} />
          </div>
        )}
      </Card>
      <Card title="Edit profile">
        <form className="stack" onSubmit={save}>
          <Field label="Full name (private)" value={fullName} maxLength={60} onChange={(e) => setFullName(e.target.value)} />
          <Field label="Public username" value={username} maxLength={20} hint="3–20 letters, numbers or _ · shown to other players" onChange={(e) => setUsername(e.target.value)} />
          <div className="field">
            <span className="label">Avatar colour</span>
            <div className="hues">
              {[0, 30, 140, 190, 210, 260, 300, 330].map((h) => (
                <button type="button" key={h} className={`hue ${hue === h ? 'active' : ''}`} style={{ background: `hsl(${h} 75% 55%)` }} aria-label={`Avatar hue ${h}`} onClick={() => setHue(h)} />
              ))}
            </div>
          </div>
          <ErrorNote error={error} />
          <Button variant="primary" type="submit" disabled={busy}>
            {busy ? 'Saving…' : 'Save profile'}
          </Button>
        </form>
      </Card>
    </div>
  );
}

function ChangePassword() {
  const [f, setF] = useState({ currentPassword: '', password: '', confirmPassword: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const issue = passwordIssue(f.password);
    if (issue) return setError(issue);
    if (f.password !== f.confirmPassword) return setError('Passwords do not match');
    setBusy(true);
    setError(null);
    try {
      await post('/auth/change-password', f);
      setF({ currentPassword: '', password: '', confirmPassword: '' });
      useGame.getState().toast('Password changed. Other devices were signed out.');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not change password');
    } finally {
      setBusy(false);
    }
  };
  return (
    <form className="stack" onSubmit={submit}>
      <Field label="Current password" type="password" autoComplete="current-password" value={f.currentPassword} onChange={(e) => setF({ ...f, currentPassword: e.target.value })} />
      <Field label="New password" type="password" autoComplete="new-password" value={f.password} onChange={(e) => setF({ ...f, password: e.target.value })} />
      <Field label="Confirm new password" type="password" autoComplete="new-password" value={f.confirmPassword} onChange={(e) => setF({ ...f, confirmPassword: e.target.value })} />
      <ErrorNote error={error} />
      <Button type="submit" disabled={busy}>
        {busy ? 'Saving…' : 'Change password'}
      </Button>
    </form>
  );
}

export function SettingsPage() {
  const s = useSettings();
  return (
    <div className="page narrow">
      <PageTitle title="Settings" />
      <Card title="Sound">
        <Toggle label="Sound effects" checked={s.sound} onChange={(v) => s.set({ sound: v })} />
        <Slider label="Effects volume" value={s.soundVolume} onChange={(v) => s.set({ soundVolume: v })} />
        <Toggle label="Background music" checked={s.music} onChange={(v) => s.set({ music: v })} />
        <Slider label="Music volume" value={s.musicVolume} onChange={(v) => s.set({ musicVolume: v })} />
      </Card>
      <Card title="Graphics & gameplay">
        <div className="field">
          <span className="label">Graphics quality</span>
          <Segmented<Quality>
            value={s.quality}
            options={[
              { value: 'auto', label: 'Auto' },
              { value: 'low', label: 'Low' },
              { value: 'medium', label: 'Medium' },
              { value: 'high', label: 'High' },
            ]}
            onChange={(v) => s.set({ quality: v })}
          />
        </div>
        <div className="field">
          <span className="label">Camera</span>
          <Segmented<CameraPreset>
            value={s.camera}
            options={[
              { value: 'tabletop', label: 'Tabletop' },
              { value: 'close', label: 'Close' },
              { value: 'top', label: 'Top-down' },
            ]}
            onChange={(v) => s.set({ camera: v })}
          />
        </div>
        <Toggle label="Auto-move when only one move is possible" checked={s.autoMove} onChange={(v) => s.set({ autoMove: v })} />
        <Toggle label="Pass & play: turn camera to the active player" checked={s.followTurns} onChange={(v) => s.set({ followTurns: v })} />
      </Card>
      <Card title="Account security">
        <ChangePassword />
      </Card>
    </div>
  );
}
