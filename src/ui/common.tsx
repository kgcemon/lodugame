import { motion } from 'framer-motion';
import type { ButtonHTMLAttributes, ReactNode } from 'react';
import type { Color } from '../../shared/types';
import { sfx } from '../audio/audio';
import { PLAYER_HEX } from '../three/layout';
import { IconClose } from './icons';

type BtnProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'glass' | 'ghost' | 'danger';
  size?: 'sm' | 'md' | 'lg';
  icon?: ReactNode;
};

export function Button({ variant = 'glass', size = 'md', icon, children, className = '', onClick, ...rest }: BtnProps) {
  return (
    <button
      className={`btn btn-${variant} btn-${size} ${className}`}
      onClick={(e) => {
        sfx('click');
        onClick?.(e);
      }}
      {...rest}
    >
      {icon}
      {children && <span>{children}</span>}
    </button>
  );
}

export function IconButton({ label, children, onClick, active, className = '' }: { label: string; children: ReactNode; onClick: () => void; active?: boolean; className?: string }) {
  return (
    <button
      className={`icon-btn ${active === false ? 'is-off' : ''} ${className}`}
      aria-label={label}
      title={label}
      onClick={() => {
        sfx('click');
        onClick();
      }}
    >
      {children}
    </button>
  );
}

export function Panel({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <motion.div
      className={`glass panel ${className}`}
      initial={{ opacity: 0, y: 24, scale: 0.97 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      exit={{ opacity: 0, y: 16, scale: 0.98 }}
      transition={{ type: 'spring', stiffness: 260, damping: 26 }}
    >
      {children}
    </motion.div>
  );
}

export function Modal({ title, onClose, children, wide }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  return (
    <motion.div className="modal-backdrop" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={onClose}>
      <motion.div
        className={`glass modal ${wide ? 'modal-wide' : ''}`}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        initial={{ opacity: 0, y: 30, scale: 0.96 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        exit={{ opacity: 0, y: 20, scale: 0.97 }}
        transition={{ type: 'spring', stiffness: 300, damping: 28 }}
        onClick={(e) => e.stopPropagation()}
      >
        <header className="modal-head">
          <h2>{title}</h2>
          <IconButton label="Close" onClick={onClose}>
            <IconClose />
          </IconButton>
        </header>
        <div className="modal-body">{children}</div>
      </motion.div>
    </motion.div>
  );
}

export function Toggle({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <label className="toggle-row">
      <span>{label}</span>
      <button
        role="switch"
        aria-checked={checked}
        className={`toggle ${checked ? 'on' : ''}`}
        onClick={() => {
          sfx('click');
          onChange(!checked);
        }}
      >
        <i />
      </button>
    </label>
  );
}

export function Segmented<T extends string | number>({ value, options, onChange }: { value: T; options: { value: T; label: string }[]; onChange: (v: T) => void }) {
  return (
    <div className="segmented" role="radiogroup">
      {options.map((o) => (
        <button
          key={String(o.value)}
          role="radio"
          aria-checked={o.value === value}
          className={o.value === value ? 'active' : ''}
          onClick={() => {
            sfx('click');
            onChange(o.value);
          }}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Avatar({ name, color, hue, size = 44 }: { name: string; color?: Color; hue?: number; size?: number }) {
  const initials = name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]!.toUpperCase())
    .join('') || '?';
  const base = color ? PLAYER_HEX[color] : `hsl(${hue ?? 210} 75% 55%)`;
  return (
    <div
      className="avatar"
      style={{
        width: size,
        height: size,
        fontSize: size * 0.38,
        background: `radial-gradient(circle at 30% 25%, rgba(255,255,255,.55), transparent 45%), ${base}`,
      }}
    >
      {initials}
    </div>
  );
}

export function Slider({ value, onChange, label }: { value: number; onChange: (v: number) => void; label: string }) {
  return (
    <label className="slider-row">
      <span>{label}</span>
      <input type="range" min={0} max={1} step={0.05} value={value} onChange={(e) => onChange(Number(e.target.value))} />
    </label>
  );
}
