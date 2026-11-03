import { useEffect, useState } from 'react';
import { api, fmt, get, post, type MatchMode } from '../../lib/api';
import { useGame } from '../../store/game';
import { Avatar, Button, Segmented, Toggle } from '../common';
import { DICE_SKINS } from '../../../shared/skins';
import { PROFILE_FRAMES } from '../../../shared/frames';
import { BOARDS, DEFAULT_BOARD } from '../../../shared/boards';
import { Card, Confirm, Empty, ErrorNote, PageTitle, Spinner, useFetch } from './kit';

/** Administrator tools. Every endpoint re-checks the admin role on the server. */

type Tab = 'overview' | 'users' | 'transactions' | 'matches' | 'disputes' | 'popups' | 'settings' | 'audit';

type Row = Record<string, string | number | boolean | null>;

const toast = (t: string) => useGame.getState().toast(t);
const d = (v: unknown) => (v ? new Date(String(v)).toLocaleString() : '—');

function Overview() {
  const o = useFetch(() => get<Record<string, number | Record<string, number>>>('/admin/overview'), []);
  if (o.loading) return <Spinner />;
  if (!o.data) return <ErrorNote error={o.error} />;
  const x = o.data as Record<string, number> & { matchesByStatus: Record<string, number> };
  const tiles: [string, string | number][] = [
    ['Users', x.users],
    ['Active today', x.activeToday],
    ['Suspended', x.suspended],
    ['Live rooms', x.liveRooms],
    ['Coins in circulation', fmt(x.coinsInCirculation)],
    ['Total payouts', fmt(x.totalPayouts)],
    ['House total', fmt(x.houseTotal)],
    ['Open disputes', x.openDisputes],
  ];
  return (
    <>
      <div className="stat-row">
        {tiles.map(([k, v]) => (
          <div className="stat-tile glass" key={k}>
            <span className="muted">{k}</span>
            <b>{v}</b>
          </div>
        ))}
      </div>
      <Card title="Matches by status">
        <div className="stat-row">
          {['waiting', 'in_progress', 'completed', 'cancelled', 'abandoned'].map((s) => (
            <div className="stat-tile glass" key={s}>
              <span className="muted">{s.replace('_', ' ')}</span>
              <b>{x.matchesByStatus[s] ?? 0}</b>
            </div>
          ))}
        </div>
      </Card>
    </>
  );
}

function Users() {
  const [q, setQ] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
  const list = useFetch(() => get<{ items: Row[] }>(`/admin/users?page=${page}&q=${encodeURIComponent(query)}`), [query, page]);
  const [adjust, setAdjust] = useState<Row | null>(null);
  /** Which balance the adjust dialog changes: coins or diamonds. */
  const [currency, setCurrency] = useState<'coins' | 'diamonds'>('coins');
  const [suspend, setSuspend] = useState<Row | null>(null);
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const [requestId, setRequestId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const doAdjust = async () => {
    setBusy(true);
    setError(null);
    try {
      await post(`/admin/users/${adjust!.id}/${currency === 'diamonds' ? 'gems' : 'adjust'}`, { amount: Number(amount), reason, requestId });
      toast(`${currency === 'diamonds' ? 'Diamonds' : 'Balance'} adjusted and recorded in the audit log`);
      setAdjust(null);
      list.reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const doSuspend = async () => {
    setBusy(true);
    setError(null);
    try {
      await post(`/admin/users/${suspend!.id}/suspend`, { reason });
      toast('Account suspended and signed out');
      setSuspend(null);
      list.reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card>
      <form
        className="join-row"
        onSubmit={(e) => {
          e.preventDefault();
          setPage(1);
          setQuery(q);
        }}
      >
        <input className="input" placeholder="Email, username, name or player ID" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search users" />
        <Button type="submit">Search</Button>
      </form>
      {list.loading && !list.data ? (
        <Spinner />
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>User</th>
                <th>Email</th>
                <th className="num">Balance</th>
                <th className="num">Diamonds</th>
                <th className="num">Matches</th>
                <th>Status</th>
                <th>Joined</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {list.data?.items.map((u) => (
                <tr key={String(u.id)}>
                  <td>
                    <div className="row gap-s">
                      <Avatar name={String(u.username)} hue={Number(u.avatar_hue ?? 210)} src={u.avatar_url as string | null} size={36} />
                      <div>
                        <b>{u.username}</b>
                        <small className="muted block">
                          {u.full_name} · {u.public_id}
                          {u.frame ? ` · ${PROFILE_FRAMES.find((f) => f.id === u.frame)?.name ?? u.frame} frame` : ''}
                        </small>
                      </div>
                    </div>
                  </td>
                  <td>
                    {u.email}
                    {!u.email_verified_at && <small className="muted block">unverified</small>}
                  </td>
                  <td className="num">{fmt(Number(u.balance))}</td>
                  <td className="num">💎 {fmt(Number(u.gems ?? 0))}</td>
                  <td className="num">{u.matches}</td>
                  <td>{u.role === 'admin' ? <span className="tag tag-gold">admin</span> : u.suspended_at ? <span className="tag tag-warn">suspended</span> : 'active'}</td>
                  <td>{d(u.created_at)}</td>
                  <td className="actions">
                    <Button
                      size="sm"
                      onClick={() => {
                        setAdjust(u);
                        setCurrency('coins');
                        setAmount('');
                        setReason('');
                        setRequestId(crypto.randomUUID());
                        setError(null);
                      }}
                    >
                      Adjust coins
                    </Button>
                    <Button
                      size="sm"
                      onClick={() => {
                        setAdjust(u);
                        setCurrency('diamonds');
                        setAmount('');
                        setReason('');
                        setRequestId(crypto.randomUUID());
                        setError(null);
                      }}
                    >
                      Give diamonds
                    </Button>
                    {u.role !== 'admin' &&
                      (u.suspended_at ? (
                        <Button size="sm" onClick={() => post(`/admin/users/${u.id}/unsuspend`).then(() => (toast('Unsuspended'), list.reload()), (e: Error) => toast(e.message))}>
                          Unsuspend
                        </Button>
                      ) : (
                        <Button size="sm" variant="danger" onClick={() => (setSuspend(u), setReason(''), setError(null))}>
                          Suspend
                        </Button>
                      ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="pager">
        <Button size="sm" disabled={page <= 1} onClick={() => setPage(page - 1)}>
          Previous
        </Button>
        <span className="muted">Page {page}</span>
        <Button size="sm" disabled={(list.data?.items.length ?? 0) < 20} onClick={() => setPage(page + 1)}>
          Next
        </Button>
      </div>
      {adjust && (
        <Confirm title={`Adjust ${adjust.username}'s ${currency}`} confirmLabel="Apply adjustment" busy={busy} onConfirm={doAdjust} onClose={() => setAdjust(null)}>
          <p className="muted">
            Current {currency}: {fmt(Number((currency === 'diamonds' ? adjust.gems : adjust.balance) ?? 0))}. Use a negative number to remove {currency}. Balances cannot go below zero.
          </p>
          <input className="input" type="number" inputMode="numeric" placeholder="Amount, e.g. 500 or -200" value={amount} onChange={(e) => setAmount(e.target.value)} />
          <input className="input" placeholder="Reason (required, saved in the audit log)" maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} />
          <ErrorNote error={error} />
        </Confirm>
      )}
      {suspend && (
        <Confirm title={`Suspend ${suspend.username}?`} confirmLabel="Suspend account" busy={busy} onConfirm={doSuspend} onClose={() => setSuspend(null)}>
          <p className="muted">They are signed out everywhere and removed from any room (a bot finishes their seat).</p>
          <input className="input" placeholder="Reason (shown to the user)" maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} />
          <ErrorNote error={error} />
        </Confirm>
      )}
    </Card>
  );
}

function Transactions() {
  const [suspicious, setSuspicious] = useState(true);
  const [page, setPage] = useState(1);
  const list = useFetch(() => get<{ items: Row[] }>(`/admin/transactions?page=${page}${suspicious ? '&suspicious=1' : ''}`), [suspicious, page]);
  return (
    <Card>
      <Toggle label="Only suspicious (large amounts and admin adjustments)" checked={suspicious} onChange={(v) => (setSuspicious(v), setPage(1))} />
      {list.loading && !list.data ? (
        <Spinner />
      ) : list.data?.items.length ? (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>When</th>
                <th>User</th>
                <th>Type</th>
                <th className="num">Amount</th>
                <th className="num">After</th>
                <th>Note / by</th>
                <th>ID</th>
              </tr>
            </thead>
            <tbody>
              {list.data.items.map((t) => (
                <tr key={String(t.id)}>
                  <td>{d(t.created_at)}</td>
                  <td>{t.username}</td>
                  <td>{String(t.type).replace(/_/g, ' ')}</td>
                  <td className={`num ${Number(t.amount) > 0 ? 'pos' : 'neg'}`}>{fmt(Number(t.amount))}</td>
                  <td className="num">{fmt(Number(t.balance_after))}</td>
                  <td>
                    {t.note}
                    {t.created_by_name && <small className="muted block">by {t.created_by_name}</small>}
                  </td>
                  <td className="mono" title={String(t.id)}>
                    {String(t.id).slice(0, 8)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Empty title="No transactions" />
      )}
      <div className="pager">
        <Button size="sm" disabled={page <= 1} onClick={() => setPage(page - 1)}>
          Previous
        </Button>
        <span className="muted">Page {page}</span>
        <Button size="sm" disabled={(list.data?.items.length ?? 0) < 20} onClick={() => setPage(page + 1)}>
          Next
        </Button>
      </div>
    </Card>
  );
}

function Matches() {
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const list = useFetch(() => get<{ items: Row[] }>(`/admin/matches?page=${page}${status ? `&status=${status}` : ''}`), [status, page]);
  return (
    <Card>
      <select className="input select" value={status} onChange={(e) => (setStatus(e.target.value), setPage(1))} aria-label="Filter by status">
        <option value="">All statuses</option>
        {['waiting', 'in_progress', 'completed', 'cancelled', 'abandoned'].map((s) => (
          <option key={s} value={s}>
            {s.replace('_', ' ')}
          </option>
        ))}
      </select>
      {list.loading && !list.data ? (
        <Spinner />
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Match</th>
                <th>Mode</th>
                <th className="num">Fee</th>
                <th>Status</th>
                <th className="num">Players</th>
                <th>Winner</th>
                <th className="num">Pot / payout / house</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody>
              {list.data?.items.map((m) => (
                <tr key={String(m.id)}>
                  <td className="mono" title={String(m.id)}>
                    {String(m.id).slice(0, 8)} <small className="muted">{m.room_code}</small>
                  </td>
                  <td>{m.mode}</td>
                  <td className="num">{m.entry_fee}</td>
                  <td>
                    {String(m.status).replace('_', ' ')}
                    {m.end_reason && <small className="muted block">{m.end_reason}</small>}
                  </td>
                  <td className="num">{m.players}</td>
                  <td>{m.winner ?? '—'}</td>
                  <td className="num">{m.total_pot == null ? '—' : `${m.total_pot} / ${m.payout} / ${m.house_amount}`}</td>
                  <td>{d(m.created_at)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="pager">
        <Button size="sm" disabled={page <= 1} onClick={() => setPage(page - 1)}>
          Previous
        </Button>
        <span className="muted">Page {page}</span>
        <Button size="sm" disabled={(list.data?.items.length ?? 0) < 20} onClick={() => setPage(page + 1)}>
          Next
        </Button>
      </div>
    </Card>
  );
}

function Disputes() {
  const [status, setStatus] = useState<'open' | 'resolved' | 'rejected'>('open');
  const list = useFetch(() => get<{ items: Row[] }>(`/admin/disputes?status=${status}`), [status]);
  const [notes, setNotes] = useState<Record<string, string>>({});
  const decide = async (id: string, s: 'resolved' | 'rejected') => {
    try {
      await post(`/admin/disputes/${id}`, { status: s, note: notes[id] ?? '' });
      toast(`Dispute ${s}`);
      list.reload();
    } catch (e) {
      toast((e as Error).message);
    }
  };
  return (
    <Card>
      <Segmented value={status} options={[{ value: 'open', label: 'Open' }, { value: 'resolved', label: 'Resolved' }, { value: 'rejected', label: 'Rejected' }]} onChange={setStatus} />
      {list.loading && !list.data ? (
        <Spinner />
      ) : list.data?.items.length ? (
        <ul className="dispute-list">
          {list.data.items.map((x) => (
            <li key={String(x.id)} className="glass">
              <div>
                <b>{x.username}</b> reported match <span className="mono">{String(x.match_id).slice(0, 8)}</span> ({x.mode}, fee {x.entry_fee}, {x.match_status}, winner {x.winner ?? '—'})
                <p>{x.reason}</p>
                {x.admin_note && <p className="muted">Admin note: {x.admin_note}</p>}
                <small className="muted">{d(x.created_at)}</small>
              </div>
              {status === 'open' && (
                <div className="stack">
                  <input className="input" placeholder="Decision note (required)" value={notes[String(x.id)] ?? ''} onChange={(e) => setNotes({ ...notes, [String(x.id)]: e.target.value })} />
                  <div className="row gap-s">
                    <Button size="sm" variant="primary" onClick={() => decide(String(x.id), 'resolved')}>
                      Resolve
                    </Button>
                    <Button size="sm" onClick={() => decide(String(x.id), 'rejected')}>
                      Reject
                    </Button>
                  </div>
                  <p className="hint">If coins are owed, use Users → Adjust coins with this match id in the reason.</p>
                </div>
              )}
            </li>
          ))}
        </ul>
      ) : (
        <Empty title={`No ${status} disputes`} />
      )}
    </Card>
  );
}

interface AnnouncementItem {
  id: string;
  title: string;
  body: string;
  imageUrl: string | null;
  buttonText: string;
  active: boolean;
  createdAt: string;
}

const MAX_IMAGE_BYTES = 2 * 1024 * 1024;

/** Read a picture as a data URL, shrinking big photos (wider than 1200 px or near 2 MB) to a JPEG. */
async function pictureDataUrl(file: File): Promise<string> {
  const raw = await new Promise<string>((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result));
    r.onerror = () => reject(new Error('Could not read the picture'));
    r.readAsDataURL(file);
  });
  if (file.type === 'image/gif') {
    if (file.size > MAX_IMAGE_BYTES) throw new Error('GIFs must be 2 MB or smaller');
    return raw;
  }
  const img = await new Promise<HTMLImageElement>((resolve, reject) => {
    const i = new Image();
    i.onload = () => resolve(i);
    i.onerror = () => reject(new Error('That file is not a picture'));
    i.src = raw;
  });
  if (img.naturalWidth <= 1200 && file.size <= MAX_IMAGE_BYTES * 0.9) return raw;
  const scale = Math.min(1, 1200 / img.naturalWidth);
  const c = document.createElement('canvas');
  c.width = Math.round(img.naturalWidth * scale);
  c.height = Math.round(img.naturalHeight * scale);
  c.getContext('2d')!.drawImage(img, 0, 0, c.width, c.height);
  return c.toDataURL('image/jpeg', 0.86);
}

/** How the popup looks in the app (purple card, gold title, green button). */
function PopupPreview({ title, body, image, buttonText }: { title: string; body: string; image: string | null; buttonText: string }) {
  return (
    <div className="ann-preview" aria-label="Preview">
      <div className="ann-card">
        {image && <img src={image} alt="" />}
        <div className="ann-text">
          <h3>{title || 'Title'}</h3>
          {body && <p>{body}</p>}
          <span className="ann-btn">{(buttonText || 'OK').toUpperCase()}</span>
        </div>
      </div>
    </div>
  );
}

/** Popups shown on the app's home screen: text, a picture, or both. Each shows once per phone. */
function Popups() {
  const list = useFetch(() => get<{ items: AnnouncementItem[] }>('/admin/announcements'), []);
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [buttonText, setButtonText] = useState('OK');
  const [image, setImage] = useState<string | null>(null);
  const [imageUrl, setImageUrl] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [remove, setRemove] = useState<AnnouncementItem | null>(null);
  /** Bumped to clear the file picker after publishing or removing the picture. */
  const [fileKey, setFileKey] = useState(0);

  const pick = async (file: File | undefined) => {
    setError(null);
    if (!file) return;
    try {
      setImage(await pictureDataUrl(file));
      setImageUrl('');
    } catch (e) {
      setError((e as Error).message);
    }
  };
  const publish = async () => {
    setBusy(true);
    setError(null);
    try {
      await post('/admin/announcements', { title, body, buttonText, image: image ?? undefined, imageUrl: image ? undefined : imageUrl || undefined });
      toast('Popup published: players see it the next time they open the app');
      setTitle('');
      setBody('');
      setButtonText('OK');
      setImage(null);
      setImageUrl('');
      setFileKey((k) => k + 1);
      list.reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const setActive = async (a: AnnouncementItem, active: boolean) => {
    try {
      await post(`/admin/announcements/${a.id}/active`, { active });
      toast(active ? 'Popup is live again' : 'Popup hidden');
      list.reload();
    } catch (e) {
      toast((e as Error).message);
    }
  };
  const doRemove = async () => {
    try {
      await api('DELETE', `/admin/announcements/${remove!.id}`);
      toast('Popup deleted');
      setRemove(null);
      list.reload();
    } catch (e) {
      toast((e as Error).message);
    }
  };

  return (
    <>
      <Card title="New popup">
        <div className="ann-editor">
          <div className="stack">
            <label className="label">
              Title
              <input className="input" maxLength={80} value={title} onChange={(e) => setTitle(e.target.value)} placeholder="e.g. Weekend event!" />
            </label>
            <label className="label">
              Text
              <textarea className="input" rows={4} maxLength={1000} value={body} onChange={(e) => setBody(e.target.value)} placeholder="What players should know (optional with a picture)" />
            </label>
            <label className="label">
              Picture (PNG, JPEG, WebP or GIF)
              <input key={fileKey} className="input" type="file" accept="image/png,image/jpeg,image/webp,image/gif" onChange={(e) => pick(e.target.files?.[0])} />
            </label>
            {!image && (
              <label className="label">
                …or a picture link
                <input className="input" value={imageUrl} onChange={(e) => setImageUrl(e.target.value)} placeholder="https://…" />
              </label>
            )}
            {image && (
              <Button size="sm" variant="ghost" onClick={() => {
                  setImage(null);
                  setFileKey((k) => k + 1);
                }}>
                Remove picture
              </Button>
            )}
            <label className="label">
              Button text
              <input className="input" maxLength={30} value={buttonText} onChange={(e) => setButtonText(e.target.value)} />
            </label>
            <ErrorNote error={error} />
            <Button variant="primary" onClick={publish} disabled={busy || !title.trim() || (!body.trim() && !image && !imageUrl.trim())}>
              {busy ? 'Publishing…' : 'Publish popup'}
            </Button>
            <p className="hint">The newest live popup opens once on every player's home screen, after the daily spin.</p>
          </div>
          <PopupPreview title={title} body={body} image={image ?? (imageUrl.startsWith('https://') ? imageUrl : null)} buttonText={buttonText} />
        </div>
      </Card>
      <Card title="Published popups">
        {list.loading && !list.data ? (
          <Spinner />
        ) : list.data?.items.length ? (
          <ul className="dispute-list">
            {list.data.items.map((a) => (
              <li key={a.id} className="glass ann-row">
                {a.imageUrl && <img src={a.imageUrl} alt="" className="ann-thumb" />}
                <div>
                  <b>{a.title}</b> <span className={`ann-status ${a.active ? 'live' : ''}`}>{a.active ? 'Live' : 'Hidden'}</span>
                  {a.body && <p>{a.body}</p>}
                  <small className="muted">{d(a.createdAt)}</small>
                </div>
                <div className="row gap-s">
                  <Button size="sm" onClick={() => setActive(a, !a.active)}>
                    {a.active ? 'Hide' : 'Show again'}
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => setRemove(a)}>
                    Delete
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        ) : (
          <Empty title="No popups yet" />
        )}
      </Card>
      {remove && (
        <Confirm title="Delete this popup?" confirmLabel="Delete" onConfirm={doRemove} onClose={() => setRemove(null)}>
          <p>"{remove.title}" will be removed for everyone.</p>
        </Confirm>
      )}
    </>
  );
}

interface AdminSettings {
  welcomeBonus: number;
  dailyBonus: number;
  payoutMultiplier: number;
  matchmakingWaitSeconds: number;
  suspiciousAmount: number;
  modes: MatchMode[];
  shopPrices: Record<string, number>;
}

/** Diamond prices of everything in the app's shop (the classic dice and board are always free). */
function ShopPrices({ prices, onChange }: { prices: Record<string, number>; onChange: (p: Record<string, number>) => void }) {
  const group = (title: string, kind: 'dice' | 'frame' | 'board', items: { id: string; name: string }[]) => (
    <>
      <h3 className="sub-title">{title}</h3>
      <div className="mode-edit glass shop-prices">
        {items.map((x) => (
          <label key={x.id}>
            {x.name}
            <input
              className="input"
              type="number"
              min={0}
              value={prices[`${kind}:${x.id}`] ?? 0}
              onChange={(e) => onChange({ ...prices, [`${kind}:${x.id}`]: Number(e.target.value) })}
            />
          </label>
        ))}
      </div>
    </>
  );
  return (
    <>
      {group('Shop · dice prices (💎 diamonds)', 'dice', DICE_SKINS.filter((d) => d.id !== 'classic'))}
      {group('Shop · profile frame prices (💎 diamonds)', 'frame', PROFILE_FRAMES)}
      {group('Shop · royal board prices (💎 diamonds)', 'board', BOARDS.filter((b) => b.id !== DEFAULT_BOARD))}
      <p className="hint">0 makes an item free. Players who already own an item keep it when its price changes.</p>
    </>
  );
}

function SettingsTab() {
  const [s, setS] = useState<AdminSettings | null>(null);
  const [policy, setPolicy] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    get<{ settings: AdminSettings; policy: string }>('/admin/settings').then((r) => (setS(r.settings), setPolicy(r.policy)), (e: Error) => setError(e.message));
  }, []);
  if (!s) return error ? <ErrorNote error={error} /> : <Spinner />;
  const num = (k: keyof Omit<AdminSettings, 'modes' | 'shopPrices'>) => (
    <label className="setting-row">
      <span>{({ welcomeBonus: 'Welcome bonus (new accounts)', dailyBonus: 'Daily bonus', payoutMultiplier: 'Winner payout multiplier (× entry fee)', matchmakingWaitSeconds: 'Matchmaking wait before starting with 2+ players (s)', suspiciousAmount: 'Flag transactions at or above' } as const)[k]}</span>
      <input className="input" type="number" step={k === 'payoutMultiplier' ? 0.25 : 1} value={s[k]} onChange={(e) => setS({ ...s, [k]: Number(e.target.value) })} />
    </label>
  );
  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await api<{ settings: AdminSettings; policy: string }>('PUT', '/admin/settings', s);
      setS(r.settings);
      setPolicy(r.policy);
      toast('Settings saved (new rooms use them; running rooms keep their fee)');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Card>
      <div className="stack">
        {num('welcomeBonus')}
        {num('dailyBonus')}
        {num('payoutMultiplier')}
        {num('matchmakingWaitSeconds')}
        {num('suspiciousAmount')}
        <p className="hint policy">{policy}</p>
        <h3 className="sub-title">Match modes</h3>
        {s.modes.map((m, i) => {
          const upd = (patch: Partial<MatchMode>) => setS({ ...s, modes: s.modes.map((x, j) => (j === i ? { ...x, ...patch } : x)) });
          return (
            <div key={m.id} className="mode-edit glass">
              <input className="input" value={m.name} aria-label="Mode name" onChange={(e) => upd({ name: e.target.value })} />
              <label>
                Fee <input className="input" type="number" min={m.privateOnly ? 0 : 1} value={m.entryFee} onChange={(e) => upd({ entryFee: Number(e.target.value) })} />
              </label>
              <label>
                Max players <input className="input" type="number" min={2} max={4} value={m.maxPlayers} onChange={(e) => upd({ maxPlayers: Number(e.target.value) })} />
              </label>
              <Toggle label="Enabled" checked={m.enabled} onChange={(v) => upd({ enabled: v })} />
              <input className="input wide" value={m.description} aria-label="Description" onChange={(e) => upd({ description: e.target.value })} />
            </div>
          );
        })}
        <ShopPrices prices={s.shopPrices} onChange={(shopPrices) => setS({ ...s, shopPrices })} />
        <ErrorNote error={error} />
        <Button variant="primary" onClick={save} disabled={busy}>
          {busy ? 'Saving…' : 'Save settings'}
        </Button>
      </div>
    </Card>
  );
}

function Audit() {
  const [page, setPage] = useState(1);
  const list = useFetch(() => get<{ items: Row[] }>(`/admin/audit?page=${page}`), [page]);
  return (
    <Card>
      {list.loading && !list.data ? (
        <Spinner />
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>When</th>
                <th>Admin</th>
                <th>Action</th>
                <th>Target</th>
                <th>Details</th>
              </tr>
            </thead>
            <tbody>
              {list.data?.items.map((l) => (
                <tr key={String(l.id)}>
                  <td>{d(l.created_at)}</td>
                  <td>{l.actor ?? '—'}</td>
                  <td>{l.action}</td>
                  <td>{l.target ?? '—'}</td>
                  <td className="mono small">{JSON.stringify(l.details).slice(0, 160)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="pager">
        <Button size="sm" disabled={page <= 1} onClick={() => setPage(page - 1)}>
          Previous
        </Button>
        <span className="muted">Page {page}</span>
        <Button size="sm" disabled={(list.data?.items.length ?? 0) < 20} onClick={() => setPage(page + 1)}>
          Next
        </Button>
      </div>
    </Card>
  );
}

export function AdminPage() {
  const [tab, setTab] = useState<Tab>('overview');
  return (
    <div className="page">
      <PageTitle title="Administration" subtitle="Every change here is recorded in the audit log." />
      <div className="admin-tabs">
        <Segmented<Tab>
          value={tab}
          options={[
            { value: 'overview', label: 'Overview' },
            { value: 'users', label: 'Users' },
            { value: 'transactions', label: 'Transactions' },
            { value: 'matches', label: 'Matches' },
            { value: 'disputes', label: 'Disputes' },
            { value: 'popups', label: 'Popups' },
            { value: 'settings', label: 'Settings' },
            { value: 'audit', label: 'Audit log' },
          ]}
          onChange={setTab}
        />
      </div>
      {tab === 'overview' && <Overview />}
      {tab === 'users' && <Users />}
      {tab === 'transactions' && <Transactions />}
      {tab === 'matches' && <Matches />}
      {tab === 'disputes' && <Disputes />}
      {tab === 'popups' && <Popups />}
      {tab === 'settings' && <SettingsTab />}
      {tab === 'audit' && <Audit />}
    </div>
  );
}
