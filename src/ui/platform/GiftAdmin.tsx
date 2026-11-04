import { useMemo, useState } from 'react';
import { fmt, get, post } from '../../lib/api';
import { toast } from '../../store/toast';
import { Avatar, Button, Segmented } from '../common';
import { GIFT_BOXES, type GiftKind } from '../../../shared/gifts';
import { Card, Confirm, Empty, ErrorNote, Spinner, useFetch } from './kit';

/**
 * Sending gifts and their history. The panel only proposes: the server checks the role, the
 * product and the amounts, and credits a gift once when the player claims it.
 */

type Row = Record<string, string | number | boolean | null>;

interface Product {
  id: string;
  name: string;
  rarity: string;
  price: number;
  currency: 'coins' | 'diamonds';
}

interface Options {
  eligible: number;
  duplicateRefundShare: number;
  limits: { coins: number; diamonds: number; message: number; recipients: number; expiryDays: number };
  products: { dice: Product[]; board: Product[]; frame: Product[] };
}

interface Preview {
  recipients: number;
  alreadyOwn: number;
  gift: { kind: GiftKind; name: string; quantity: number; rarity: string };
}

const KINDS: { value: GiftKind; label: string }[] = [
  { value: 'coins', label: '🪙 Coins' },
  { value: 'diamonds', label: '💎 Diamonds' },
  { value: 'dice', label: '🎲 Dice skin' },
  { value: 'board', label: '👑 Board' },
  { value: 'frame', label: '🖼️ Avatar frame' },
];

const when = (v: unknown) => (v ? new Date(String(v)).toLocaleString() : '—');

const contentLabel = (kind: string, name: unknown, quantity: unknown) =>
  kind === 'coins' ? `🪙 ${fmt(Number(quantity))} coins` : kind === 'diamonds' ? `💎 ${fmt(Number(quantity))} diamonds` : `${KINDS.find((k) => k.value === kind)?.label ?? kind}: ${name}`;

export function GiftSend() {
  const options = useFetch(() => get<Options>('/admin/gifts/options'), []);
  const [q, setQ] = useState('');
  const [query, setQuery] = useState('');
  const results = useFetch(() => (query ? get<{ items: Row[] }>(`/admin/users?q=${encodeURIComponent(query)}`) : Promise.resolve({ items: [] as Row[] })), [query]);
  const [chosen, setChosen] = useState<Row[]>([]);
  const [everyone, setEveryone] = useState(false);
  const [kind, setKind] = useState<GiftKind>('coins');
  const [itemId, setItemId] = useState('');
  const [amount, setAmount] = useState('');
  const [box, setBox] = useState('');
  const [message, setMessage] = useState('');
  const [expires, setExpires] = useState('');
  const [preview, setPreview] = useState<Preview | null>(null);
  const [requestId, setRequestId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const currency = kind === 'coins' || kind === 'diamonds';
  const products = useMemo(() => (currency || !options.data ? [] : options.data.products[kind as 'dice' | 'board' | 'frame']), [currency, kind, options.data]);

  const body = () => ({
    kind,
    ...(currency ? { quantity: Number(amount) } : { itemId }),
    box: box || null,
    message,
    expiresAt: expires ? new Date(expires).toISOString() : null,
    ...(everyone ? { all: true } : { userIds: chosen.map((u) => u.id) }),
  });

  const openPreview = async () => {
    setError(null);
    try {
      setPreview(await post<Preview>('/admin/gifts/preview', body()));
      // One id per confirmed send: a double click can't send the gift twice.
      setRequestId(crypto.randomUUID());
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const send = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await post<{ sent: number; skipped: number }>('/admin/gifts', { ...body(), requestId, confirm: true, ...(everyone ? { confirmCount: preview!.recipients } : {}) });
      toast(`Gift sent to ${fmt(r.sent)} player${r.sent === 1 ? '' : 's'}${r.skipped ? ` (${fmt(r.skipped)} already had it)` : ''}`);
      setPreview(null);
      setChosen([]);
      setEveryone(false);
      setMessage('');
      setExpires('');
      setAmount('');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const toggle = (u: Row) => setChosen((list) => (list.some((x) => x.id === u.id) ? list.filter((x) => x.id !== u.id) : [...list, u]));
  const ready = (everyone || chosen.length > 0) && (currency ? Number(amount) > 0 : !!itemId);
  const refund = (p: Product) => Math.round(p.price * (options.data?.duplicateRefundShare ?? 0));
  const product = products.find((p) => p.id === itemId);

  if (options.loading && !options.data) return <Spinner />;
  if (!options.data) return <ErrorNote error={options.error} />;
  const o = options.data;

  return (
    <>
      <Card title="1. Who receives it">
        <form
          className="join-row"
          onSubmit={(e) => {
            e.preventDefault();
            setQuery(q.trim());
          }}
        >
          <input className="input" placeholder="Username, email or player ID" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search players" disabled={everyone} />
          <Button type="submit" disabled={everyone}>
            Search
          </Button>
        </form>
        <label className="row gap-s" style={{ margin: '12px 0' }}>
          <input type="checkbox" checked={everyone} onChange={(e) => setEveryone(e.target.checked)} />
          <span>
            <b>Everyone eligible</b> <span className="muted">({fmt(o.eligible)} player{o.eligible === 1 ? "" : "s"}; suspended accounts and administrators are left out)</span>
          </span>
        </label>
        {!everyone && chosen.length > 0 && (
          <p>
            <b>{chosen.length} selected:</b>{' '}
            {chosen.map((u) => (
              <button key={String(u.id)} className="tag tag-gold" style={{ marginRight: 6, cursor: 'pointer' }} onClick={() => toggle(u)} title="Remove">
                {u.username} ✕
              </button>
            ))}
          </p>
        )}
        {!everyone && query && (results.loading && !results.data ? (
          <Spinner />
        ) : results.data?.items.length ? (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th />
                  <th>Player</th>
                  <th>Email</th>
                  <th className="num">Coins</th>
                  <th className="num">Diamonds</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {results.data.items.map((u) => (
                  <tr key={String(u.id)}>
                    <td>
                      <input
                        type="checkbox"
                        aria-label={`Select ${u.username}`}
                        disabled={!!u.suspended_at || (!chosen.some((x) => x.id === u.id) && chosen.length >= o.limits.recipients)}
                        checked={chosen.some((x) => x.id === u.id)}
                        onChange={() => toggle(u)}
                      />
                    </td>
                    <td>
                      <div className="row gap-s">
                        <Avatar name={String(u.username)} hue={Number(u.avatar_hue ?? 210)} src={u.avatar_url as string | null} size={32} />
                        <div>
                          <b>{u.username}</b>
                          <small className="muted block">{u.public_id}</small>
                        </div>
                      </div>
                    </td>
                    <td>{u.email}</td>
                    <td className="num">{fmt(Number(u.balance))}</td>
                    <td className="num">{fmt(Number(u.gems ?? 0))}</td>
                    <td>{u.suspended_at ? <span className="tag tag-warn">suspended</span> : 'active'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <Empty title="No players found" />
        ))}
      </Card>

      <Card title="2. What they receive">
        <Segmented
          value={kind}
          options={KINDS}
          onChange={(k) => {
            setKind(k);
            setItemId('');
            setAmount('');
          }}
        />
        <div className="stack" style={{ display: 'grid', gap: 12, marginTop: 14, maxWidth: 520 }}>
          {currency ? (
            <label>
              <span className="label">Amount (at most {fmt(kind === 'coins' ? o.limits.coins : o.limits.diamonds)})</span>
              <input className="input" type="number" min={1} inputMode="numeric" value={amount} onChange={(e) => setAmount(e.target.value)} />
            </label>
          ) : (
            <label>
              <span className="label">Product</span>
              <select className="input select" value={itemId} onChange={(e) => setItemId(e.target.value)}>
                <option value="">Choose…</option>
                {products.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name} ({p.rarity}, {fmt(p.price)} {p.currency})
                  </option>
                ))}
              </select>
              {product && (
                <small className="muted block">
                  A player who already owns it gets {refund(product) ? `${fmt(refund(product))} ${product.currency}` : 'nothing'} instead ({Math.round(o.duplicateRefundShare * 100)}% of its shop price).
                </small>
              )}
            </label>
          )}
          <label>
            <span className="label">Gift box (how it looks; hides the content until opened)</span>
            <select className="input select" value={box} onChange={(e) => setBox(e.target.value)}>
              <option value="">No box</option>
              {GIFT_BOXES.map((b) => (
                <option key={b.id} value={b.id}>
                  {b.label}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span className="label">Message (optional)</span>
            <input className="input" maxLength={o.limits.message} value={message} onChange={(e) => setMessage(e.target.value)} placeholder="Thanks for playing LudoX!" />
          </label>
          <label>
            <span className="label">Expires (optional; unclaimed after this it can't be claimed)</span>
            <input className="input" type="datetime-local" value={expires} onChange={(e) => setExpires(e.target.value)} />
          </label>
        </div>
        <ErrorNote error={preview ? null : error} />
        <div style={{ marginTop: 16 }}>
          <Button variant="primary" disabled={!ready} onClick={openPreview}>
            Preview gift
          </Button>
        </div>
      </Card>

      {preview && (
        <Confirm
          title={preview.recipients > 1 ? `Send to ${fmt(preview.recipients)} players?` : 'Send this gift?'}
          confirmLabel={preview.recipients > 1 ? `Send to all ${fmt(preview.recipients)}` : 'Send gift'}
          busy={busy}
          onConfirm={send}
          onClose={() => setPreview(null)}
        >
          <p>
            <b>{contentLabel(preview.gift.kind, preview.gift.name, preview.gift.quantity)}</b> <span className="tag">{preview.gift.rarity}</span>
            {box && <span className="muted"> in a {GIFT_BOXES.find((b) => b.id === box)?.label}</span>}
          </p>
          <p className="muted">
            {everyone ? 'Every eligible player' : chosen.map((u) => u.username).slice(0, 8).join(', ') + (chosen.length > 8 ? ` and ${chosen.length - 8} more` : '')} will find this in their Gift Inbox. Nothing is credited until each player claims it.
          </p>
          {message && <p>“{message}”</p>}
          {expires && <p className="muted">Expires {when(new Date(expires))}</p>}
          {preview.alreadyOwn > 0 && <p className="muted">{preview.recipients === 1 ? "They already own it" : `${fmt(preview.alreadyOwn)} of them already own it`} and will get the refund instead.</p>}
          {preview.recipients > 1 && <p className="muted">This is a bulk send and can't be undone in one step (each gift can be cancelled from the history until it is claimed).</p>}
          <ErrorNote error={error} />
        </Confirm>
      )}
    </>
  );
}

const STATUS_TAG: Record<string, string> = { pending: 'tag-gold', opened: 'tag-gold', claimed: '', expired: 'tag-warn', cancelled: 'tag-warn' };
const FILTERS: { value: string; label: string; qs: string }[] = [
  { value: 'all', label: 'All', qs: '' },
  { value: 'pending', label: 'Pending', qs: 'status=pending' },
  { value: 'opened', label: 'Opened', qs: 'status=opened' },
  { value: 'claimed', label: 'Claimed', qs: 'status=claimed' },
  { value: 'expired', label: 'Expired', qs: 'status=expired' },
  { value: 'cancelled', label: 'Cancelled', qs: 'status=cancelled' },
  { value: 'coins', label: '🪙 Coins', qs: 'type=coins' },
  { value: 'diamonds', label: '💎 Diamonds', qs: 'type=diamonds' },
  { value: 'cosmetics', label: '🎨 Cosmetics', qs: 'type=cosmetics' },
];

export function GiftHistory() {
  const [filter, setFilter] = useState('all');
  const [page, setPage] = useState(1);
  const [q, setQ] = useState('');
  const [query, setQuery] = useState('');
  const [cancel, setCancel] = useState<Row | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const qs = FILTERS.find((f) => f.value === filter)!.qs;
  const list = useFetch(() => get<{ items: Row[] }>(`/admin/gifts?page=${page}${qs ? `&${qs}` : ''}${query ? `&q=${encodeURIComponent(query)}` : ''}`), [qs, page, query]);

  const doCancel = async () => {
    setBusy(true);
    setError(null);
    try {
      await post(`/admin/gifts/${cancel!.id}/cancel`);
      toast('Gift cancelled and recorded in the audit log');
      setCancel(null);
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
          setQuery(q.trim());
        }}
      >
        <input className="input" placeholder="Recipient username or player ID" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search recipient" />
        <Button type="submit">Search</Button>
      </form>
      <div style={{ margin: '12px 0', overflowX: 'auto' }}>
        <Segmented
          value={filter}
          options={FILTERS}
          onChange={(f) => {
            setFilter(f);
            setPage(1);
          }}
        />
      </div>
      {list.loading && !list.data ? (
        <Spinner />
      ) : list.data?.items.length ? (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Gift ID</th>
                <th>Recipient</th>
                <th>Type / value</th>
                <th>Sender</th>
                <th>Created</th>
                <th>Status</th>
                <th>Claimed</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {list.data.items.map((g) => (
                <tr key={String(g.id)}>
                  <td className="mono" title={String(g.id)}>
                    {String(g.id).slice(0, 8)}
                  </td>
                  <td>
                    <b>{g.recipient}</b>
                    <small className="muted block">{g.recipient_public_id}</small>
                  </td>
                  <td>
                    {contentLabel(String(g.kind), g.item_name, g.quantity)}
                    {g.box_style && <small className="muted block">{GIFT_BOXES.find((b) => b.id === g.box_style)?.label}</small>}
                    {!!g.duplicate && (
                      <small className="muted block">
                        already owned: got {fmt(Number(g.granted_amount))} {g.granted_kind}
                      </small>
                    )}
                    {g.message && <small className="muted block">“{g.message}”</small>}
                  </td>
                  <td>{g.sender ?? '—'}</td>
                  <td>
                    {when(g.created_at)}
                    {g.expires_at && <small className="muted block">expires {when(g.expires_at)}</small>}
                  </td>
                  <td>
                    <span className={`tag ${STATUS_TAG[String(g.status)] ?? ''}`}>{g.status}</span>
                  </td>
                  <td>{when(g.claimed_at)}</td>
                  <td className="actions">
                    {(g.status === 'pending' || g.status === 'opened') && (
                      <Button size="sm" variant="danger" onClick={() => (setCancel(g), setError(null))}>
                        Cancel
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Empty title="No gifts here" />
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
      {cancel && (
        <Confirm title={`Cancel this gift to ${cancel.recipient}?`} confirmLabel="Cancel gift" busy={busy} onConfirm={doCancel} onClose={() => setCancel(null)}>
          <p className="muted">{contentLabel(String(cancel.kind), cancel.item_name, cancel.quantity)}. They will no longer be able to claim it.</p>
          <ErrorNote error={error} />
        </Confirm>
      )}
    </Card>
  );
}
