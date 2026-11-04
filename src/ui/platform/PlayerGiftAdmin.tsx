import { useState } from 'react';
import { fmt, post, get } from '../../lib/api';
import { toast } from '../../store/toast';
import { Button, Modal, Segmented, Toggle } from '../common';
import { Card, ErrorNote, Field, Spinner, useFetch } from './kit';

/**
 * The gifts players send each other in a match. The panel only proposes: the server checks the
 * role and every field, and charges players from this catalogue. Looks are chosen from what the app
 * can draw; gifts already sent keep the name and price they were sent with.
 */

type Currency = 'coins' | 'diamonds';

interface Product {
  id: string;
  name: string;
  currency: Currency;
  price: number;
  category: string;
  rarity: string;
  model: string;
  sendAnim: string;
  receiveAnim: string;
  particle: string;
  enabled: boolean;
  featured: boolean;
  eventName: string | null;
  availableUntil: string | null;
  sortOrder: number;
  sent: number;
  spent: number;
}

interface Options {
  models: string[];
  sendAnims: string[];
  receiveAnims: string[];
  particles: string[];
  rarities: string[];
  categories: string[];
}

const label = (s: string) => s.replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());

function Pick({ title, value, list, onChange }: { title: string; value: string; list: string[]; onChange: (v: string) => void }) {
  return (
    <div className="field form-field">
      <label className="label">{title}</label>
      <select className="input" aria-label={title} value={value} onChange={(e) => onChange(e.target.value)}>
        {list.map((o) => (
          <option key={o} value={o}>
            {label(o)}
          </option>
        ))}
      </select>
    </div>
  );
}

function Editor({ item, currency, options, onClose, onSaved }: { item: Product | null; currency: Currency; options: Options; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState({
    name: item?.name ?? '',
    price: String(item?.price ?? ''),
    category: item?.category ?? options.categories[0],
    rarity: item?.rarity ?? options.rarities[0],
    model: item?.model ?? options.models[0],
    sendAnim: item?.sendAnim ?? options.sendAnims[0],
    receiveAnim: item?.receiveAnim ?? options.receiveAnims[0],
    particle: item?.particle ?? options.particles[0],
    enabled: item?.enabled ?? true,
    featured: item?.featured ?? false,
    eventName: item?.eventName ?? '',
    availableUntil: item?.availableUntil ? item.availableUntil.slice(0, 16) : '',
    sortOrder: String(item?.sortOrder ?? 1000),
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const set = <K extends keyof typeof f>(k: K, val: (typeof f)[K]) => setF((o) => ({ ...o, [k]: val }));

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const body = {
        ...f,
        price: Number(f.price),
        sortOrder: Number(f.sortOrder),
        eventName: f.eventName.trim() || null,
        availableUntil: f.availableUntil ? new Date(f.availableUntil).toISOString() : null,
      };
      await post(item ? `/admin/player-gifts/${item.id}` : '/admin/player-gifts', item ? body : { ...body, currency });
      toast('Gift saved');
      onSaved();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title={`${item ? 'Edit' : 'New'} ${currency === 'coins' ? 'gold' : 'diamond'} gift`} onClose={onClose}>
      <ErrorNote error={error} />
      <Field label="Name" value={f.name} maxLength={40} onChange={(e) => set('name', e.target.value)} />
      <Field label={`Price in ${currency === 'coins' ? 'coins' : 'diamonds'}`} type="number" min={1} value={f.price} onChange={(e) => set('price', e.target.value)} />
      <Pick title="Category" value={f.category} list={options.categories} onChange={(v) => set('category', v)} />
      <Pick title="Rarity (sets how long the journey lasts)" value={f.rarity} list={options.rarities} onChange={(v) => set('rarity', v)} />
      <Pick title="3D model" value={f.model} list={options.models} onChange={(v) => set('model', v)} />
      <Pick title="Sending animation" value={f.sendAnim} list={options.sendAnims} onChange={(v) => set('sendAnim', v)} />
      <Pick title="Receiving animation" value={f.receiveAnim} list={options.receiveAnims} onChange={(v) => set('receiveAnim', v)} />
      <Pick title="Particle effect" value={f.particle} list={options.particles} onChange={(v) => set('particle', v)} />
      <Field label="Event name (optional, for event-exclusive gifts)" value={f.eventName} maxLength={40} onChange={(e) => set('eventName', e.target.value)} />
      <Field label="Available until (optional)" type="datetime-local" value={f.availableUntil} onChange={(e) => set('availableUntil', e.target.value)} />
      <Field label="Order in the shop" type="number" min={0} value={f.sortOrder} onChange={(e) => set('sortOrder', e.target.value)} />
      <Toggle label="Available to send" checked={f.enabled} onChange={(v) => set('enabled', v)} />
      <Toggle label="Featured" checked={f.featured} onChange={(v) => set('featured', v)} />
      <Button variant="primary" onClick={() => void save()} disabled={busy}>
        {busy ? 'Saving…' : 'Save gift'}
      </Button>
    </Modal>
  );
}

export function PlayerGiftAdmin() {
  const [currency, setCurrency] = useState<Currency>('coins');
  const [editing, setEditing] = useState<Product | 'new' | null>(null);
  const data = useFetch(() => get<{ products: Product[]; options: Options }>('/admin/player-gifts'), []);
  if (data.loading && !data.data) return <Spinner />;
  if (!data.data) return <ErrorNote error={data.error} />;
  const { products, options } = data.data;
  const shown = products.filter((p) => p.currency === currency);
  const unit = currency === 'coins' ? '🪙' : '💎';
  const sent = shown.reduce((a, p) => a + p.sent, 0);
  const spent = shown.reduce((a, p) => a + p.spent, 0);

  const toggle = async (p: Product, change: Partial<Product>) => {
    try {
      await post(`/admin/player-gifts/${p.id}`, change);
      data.reload();
    } catch (e) {
      toast((e as Error).message);
    }
  };

  return (
    <div>
      <Card
        title={
          <Segmented
            value={currency}
            options={[
              { value: 'coins' as Currency, label: '🪙 Gold gifts' },
              { value: 'diamonds' as Currency, label: '💎 Diamond gifts' },
            ]}
            onChange={setCurrency}
          />
        }
        action={
          <Button variant="primary" onClick={() => setEditing('new')}>
            New gift
          </Button>
        }
      >
        <p>
          {fmt(sent)} sent · {unit} {fmt(spent)} spent. Gifts are cosmetic: recipients get a collectible, never currency.
        </p>
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Gift</th>
                <th>Price</th>
                <th>Rarity</th>
                <th>Look</th>
                <th>Sent</th>
                <th>Spent</th>
                <th>On sale</th>
                <th>Featured</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {shown.map((p) => (
                <tr key={p.id}>
                  <td>
                    <b>{p.name}</b>
                    {p.eventName ? <div>Event: {p.eventName}</div> : null}
                    {p.availableUntil ? <div>Until {new Date(p.availableUntil).toLocaleString()}</div> : null}
                  </td>
                  <td>
                    {unit} {fmt(p.price)}
                  </td>
                  <td>{label(p.rarity)}</td>
                  <td>
                    {label(p.model)} · {p.sendAnim} · {p.receiveAnim} · {p.particle}
                  </td>
                  <td>{fmt(p.sent)}</td>
                  <td>{fmt(p.spent)}</td>
                  <td>
                    <Toggle label="On sale" checked={p.enabled} onChange={(v) => void toggle(p, { enabled: v })} />
                  </td>
                  <td>
                    <Toggle label="Featured" checked={p.featured} onChange={(v) => void toggle(p, { featured: v })} />
                  </td>
                  <td>
                    <Button size="sm" onClick={() => setEditing(p)}>
                      Edit
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
      {editing ? (
        <Editor
          item={editing === 'new' ? null : editing}
          currency={currency}
          options={options}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            data.reload();
          }}
        />
      ) : null}
    </div>
  );
}
