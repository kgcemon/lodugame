import { AnimatePresence, animate, motion } from 'framer-motion';
import { useEffect, useId, useRef, useState, type InputHTMLAttributes, type ReactNode } from 'react';
import { fmt } from '../../lib/api';
import { Button, Modal } from '../common';
import { IconEye } from '../icons';

/** Small building blocks shared by the platform pages. */

export function CoinIcon({ size = 20 }: { size?: number }) {
  return (
    <svg className="coin-icon" width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <defs>
        <radialGradient id="coinG" cx="35%" cy="30%" r="75%">
          <stop offset="0" stopColor="#fff4b8" />
          <stop offset="0.45" stopColor="#ffd34d" />
          <stop offset="1" stopColor="#d48b10" />
        </radialGradient>
      </defs>
      <circle cx="12" cy="12" r="10.5" fill="url(#coinG)" stroke="#a8650a" strokeWidth="1" />
      <circle cx="12" cy="12" r="7.3" fill="none" stroke="#b9730c" strokeWidth="1.2" opacity="0.8" />
      <path d="M12 7.5l1.3 2.9 3.1.3-2.3 2.1.7 3.1-2.8-1.6-2.8 1.6.7-3.1-2.3-2.1 3.1-.3z" fill="#b9730c" />
    </svg>
  );
}

/** Coin amount that counts up/down smoothly when it changes. */
export function Coins({ value, size = 20, className = '' }: { value: number; size?: number; className?: string }) {
  const [shown, setShown] = useState(value);
  const prev = useRef(value);
  const [flash, setFlash] = useState<'up' | 'down' | null>(null);
  useEffect(() => {
    if (prev.current === value) return;
    setFlash(value > prev.current ? 'up' : 'down');
    const c = animate(prev.current, value, { duration: 0.8, ease: 'easeOut', onUpdate: (v) => setShown(Math.round(v)) });
    prev.current = value;
    const t = setTimeout(() => setFlash(null), 900);
    return () => {
      c.stop();
      clearTimeout(t);
    };
  }, [value]);
  return (
    <span className={`coins ${flash ? `flash-${flash}` : ''} ${className}`} aria-label={`${fmt(value)} coins`}>
      <CoinIcon size={size} />
      <b>{fmt(shown)}</b>
    </span>
  );
}

type FieldProps = InputHTMLAttributes<HTMLInputElement> & { label: string; error?: string | null; hint?: ReactNode };

export function Field({ label, error, hint, type, ...rest }: FieldProps) {
  const id = useId();
  const [show, setShow] = useState(false);
  const isPw = type === 'password';
  return (
    <div className={`field form-field ${error ? 'has-error' : ''}`}>
      <label className="label" htmlFor={id}>
        {label}
      </label>
      <div className="input-wrap">
        <input
          id={id}
          className="input"
          type={isPw && show ? 'text' : type}
          aria-invalid={!!error}
          aria-describedby={error ? `${id}-err` : undefined}
          {...rest}
        />
        {isPw && (
          <button type="button" className="pw-toggle" aria-label={show ? 'Hide password' : 'Show password'} onClick={() => setShow(!show)}>
            <IconEye size={18} />
          </button>
        )}
      </div>
      {error ? (
        <span className="field-error" id={`${id}-err`} role="alert">
          {error}
        </span>
      ) : hint ? (
        <span className="field-hint">{hint}</span>
      ) : null}
    </div>
  );
}

export function Spinner({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="spinner-wrap" role="status">
      <span className="spinner" />
      <span className="muted">{label}</span>
    </div>
  );
}

export function Empty({ icon, title, children }: { icon?: ReactNode; title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      {icon}
      <strong>{title}</strong>
      {children && <p className="muted">{children}</p>}
    </div>
  );
}

export function ErrorNote({ error }: { error: string | null }) {
  return (
    <AnimatePresence>
      {error && (
        <motion.div className="form-error" role="alert" initial={{ opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}>
          {error}
        </motion.div>
      )}
    </AnimatePresence>
  );
}

export function Pager({ page, total, pageSize, onPage }: { page: number; total: number; pageSize: number; onPage: (p: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  if (pages <= 1) return null;
  return (
    <nav className="pager" aria-label="Pages">
      <Button size="sm" disabled={page <= 1} onClick={() => onPage(page - 1)}>
        Previous
      </Button>
      <span className="muted">
        Page {page} of {pages}
      </span>
      <Button size="sm" disabled={page >= pages} onClick={() => onPage(page + 1)}>
        Next
      </Button>
    </nav>
  );
}

export function Card({ title, action, children, className = '' }: { title?: ReactNode; action?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`card glass ${className}`}>
      {(title || action) && (
        <header className="card-head">
          {title && <h2>{title}</h2>}
          {action}
        </header>
      )}
      {children}
    </section>
  );
}

export function PageTitle({ title, subtitle, action }: { title: string; subtitle?: ReactNode; action?: ReactNode }) {
  return (
    <header className="page-title">
      <div>
        <h1>{title}</h1>
        {subtitle && <p className="muted">{subtitle}</p>}
      </div>
      {action}
    </header>
  );
}

export function Confirm({
  title,
  children,
  confirmLabel,
  busy,
  onConfirm,
  onClose,
}: {
  title: string;
  children: ReactNode;
  confirmLabel: string;
  busy?: boolean;
  onConfirm: () => void;
  onClose: () => void;
}) {
  return (
    <Modal title={title} onClose={onClose}>
      <div className="stack">
        {children}
        <div className="row gap-s confirm-actions">
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button variant="primary" onClick={onConfirm} disabled={busy}>
            {busy ? 'Please wait…' : confirmLabel}
          </Button>
        </div>
      </div>
    </Modal>
  );
}

export function timeAgo(iso: string): string {
  const s = Math.max(1, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  if (s < 86400) return `${Math.round(s / 3600)}h ago`;
  return new Date(iso).toLocaleDateString();
}

/** Tiny async-data hook: { data, error, loading, reload }. */
export function useFetch<T>(fn: () => Promise<T>, deps: unknown[]): { data: T | null; error: string | null; loading: boolean; reload: () => void } {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [n, setN] = useState(0);
  useEffect(() => {
    let live = true;
    setLoading(true);
    fn().then(
      (d) => {
        if (!live) return;
        setData(d);
        setError(null);
        setLoading(false);
      },
      (e: Error) => {
        if (!live) return;
        setError(e.message);
        setLoading(false);
      },
    );
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, n]);
  return { data, error, loading, reload: () => setN((x) => x + 1) };
}
