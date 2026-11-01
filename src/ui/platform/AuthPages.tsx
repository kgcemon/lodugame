import { motion } from 'framer-motion';
import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { post } from '../../lib/api';
import { refreshMe, useAuth } from '../../store/auth';
import { Button } from '../common';
import { CoinIcon, ErrorNote, Field } from './kit';

/** Register / login / forgot / reset / verify. The server enforces every rule; the client mirrors them for instant feedback. */

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

export function safeNext(v: string | null): string {
  return v && /^\/(?![/\\])[^\s]*$/.test(v) ? v : '/';
}

export function passwordIssue(pw: string): string | null {
  if (pw.length < 8) return 'At least 8 characters';
  if (!/[A-Za-z]/.test(pw) || !/[0-9]/.test(pw)) return 'Use letters and numbers';
  return null;
}

function AuthLayout({ title, subtitle, children, footer }: { title: string; subtitle?: ReactNode; children: ReactNode; footer?: ReactNode }) {
  return (
    <div className="auth-screen">
      <motion.div
        className="auth-card glass"
        initial={{ opacity: 0, y: 24, scale: 0.98 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        transition={{ type: 'spring', stiffness: 240, damping: 24 }}
      >
        <div className="logo small auth-logo">
          <span className="logo-ludo">LUDO</span>
          <span className="logo-3d">3D</span>
        </div>
        <h1>{title}</h1>
        {subtitle && <p className="muted auth-sub">{subtitle}</p>}
        {children}
        {footer && <div className="auth-footer">{footer}</div>}
      </motion.div>
    </div>
  );
}

export function RegisterPage() {
  const nav = useNavigate();
  const [params] = useSearchParams();
  const bonus = useAuth((s) => s.config?.welcomeBonus ?? 1000);
  const [f, setF] = useState({ fullName: '', email: '', password: '', confirmPassword: '' });
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const errors = {
    fullName: f.fullName.trim().length < 2 ? 'Enter your name (at least 2 characters)' : null,
    email: !EMAIL_RE.test(f.email.trim()) ? 'Enter a valid email address' : null,
    password: passwordIssue(f.password),
    confirmPassword: f.confirmPassword !== f.password ? 'Passwords do not match' : null,
  };
  const valid = !Object.values(errors).some(Boolean);
  const show = (k: keyof typeof errors) => (touched[k] ? errors[k] : null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setTouched({ fullName: true, email: true, password: true, confirmPassword: true });
    if (!valid || busy) return;
    setBusy(true);
    setError(null);
    try {
      await post('/auth/register', { ...f, email: f.email.trim(), fullName: f.fullName.trim() });
      await refreshMe();
      nav(safeNext(params.get('next')), { replace: true });
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  };

  return (
    <AuthLayout
      title="Create your account"
      subtitle={
        <span className="bonus-note">
          <CoinIcon size={18} /> Get <b>{bonus.toLocaleString()} free coins</b> when you join
        </span>
      }
      footer={
        <>
          Already have an account? <Link to={`/login${params.get('next') ? `?next=${encodeURIComponent(params.get('next')!)}` : ''}`}>Sign in</Link>
        </>
      }
    >
      <form className="stack" onSubmit={submit} noValidate>
        <Field label="Full name" autoComplete="name" maxLength={60} value={f.fullName} error={show('fullName')}
          onChange={(e) => setF({ ...f, fullName: e.target.value })} onBlur={() => setTouched({ ...touched, fullName: true })} />
        <Field label="Email address" type="email" autoComplete="email" inputMode="email" maxLength={254} value={f.email} error={show('email')}
          onChange={(e) => setF({ ...f, email: e.target.value })} onBlur={() => setTouched({ ...touched, email: true })} />
        <Field label="Password" type="password" autoComplete="new-password" maxLength={128} value={f.password} error={show('password')}
          hint="At least 8 characters with letters and numbers"
          onChange={(e) => setF({ ...f, password: e.target.value })} onBlur={() => setTouched({ ...touched, password: true })} />
        <Field label="Confirm password" type="password" autoComplete="new-password" maxLength={128} value={f.confirmPassword} error={show('confirmPassword')}
          onChange={(e) => setF({ ...f, confirmPassword: e.target.value })} onBlur={() => setTouched({ ...touched, confirmPassword: true })} />
        <ErrorNote error={error} />
        <Button variant="primary" size="lg" type="submit" disabled={busy}>
          {busy ? 'Creating account…' : 'Create account'}
        </Button>
      </form>
    </AuthLayout>
  );
}

export function LoginPage() {
  const nav = useNavigate();
  const [params] = useSearchParams();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [remember, setRemember] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(params.get('reset') ? null : null);
  const next = params.get('next');

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    if (!EMAIL_RE.test(email.trim())) return setError('Enter a valid email address');
    if (!password) return setError('Enter your password');
    setBusy(true);
    setError(null);
    try {
      await post('/auth/login', { email: email.trim(), password, remember });
      await refreshMe();
      nav(safeNext(next), { replace: true });
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  };

  return (
    <AuthLayout
      title="Welcome back"
      subtitle={params.get('reset') ? 'Your password was changed. Sign in with the new one.' : 'Sign in to play, earn coins and challenge friends.'}
      footer={
        <>
          New here? <Link to={`/register${next ? `?next=${encodeURIComponent(next)}` : ''}`}>Create an account</Link>
        </>
      }
    >
      <form className="stack" onSubmit={submit} noValidate>
        <Field label="Email address" type="email" autoComplete="email" inputMode="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        <Field label="Password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
        <div className="row auth-row">
          <label className="check">
            <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} /> Keep me signed in
          </label>
          <Link to="/forgot-password">Forgot password?</Link>
        </div>
        <ErrorNote error={error} />
        <Button variant="primary" size="lg" type="submit" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </Button>
      </form>
    </AuthLayout>
  );
}

export function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!EMAIL_RE.test(email.trim())) return setError('Enter a valid email address');
    setBusy(true);
    setError(null);
    try {
      const r = await post<{ message: string }>('/auth/forgot-password', { email: email.trim() });
      setDone(r.message);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <AuthLayout title="Reset your password" subtitle="We'll email you a link to choose a new password." footer={<Link to="/login">Back to sign in</Link>}>
      {done ? (
        <p className="form-success" role="status">
          {done}
        </p>
      ) : (
        <form className="stack" onSubmit={submit} noValidate>
          <Field label="Email address" type="email" autoComplete="email" inputMode="email" value={email} onChange={(e) => setEmail(e.target.value)} />
          <ErrorNote error={error} />
          <Button variant="primary" size="lg" type="submit" disabled={busy}>
            {busy ? 'Sending…' : 'Send reset link'}
          </Button>
        </form>
      )}
    </AuthLayout>
  );
}

export function ResetPasswordPage() {
  const [params] = useSearchParams();
  const nav = useNavigate();
  const token = params.get('token') ?? '';
  const [pw, setPw] = useState('');
  const [pw2, setPw2] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(token ? null : 'This reset link is incomplete. Request a new one.');
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const issue = passwordIssue(pw);
    if (issue) return setError(issue);
    if (pw !== pw2) return setError('Passwords do not match');
    setBusy(true);
    setError(null);
    try {
      await post('/auth/reset-password', { token, password: pw, confirmPassword: pw2 });
      useAuth.setState({ user: null, wallet: null, status: 'guest' });
      nav('/login?reset=1', { replace: true });
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  };
  return (
    <AuthLayout title="Choose a new password" footer={<Link to="/forgot-password">Request a new link</Link>}>
      <form className="stack" onSubmit={submit} noValidate>
        <Field label="New password" type="password" autoComplete="new-password" value={pw} hint="At least 8 characters with letters and numbers" onChange={(e) => setPw(e.target.value)} />
        <Field label="Confirm new password" type="password" autoComplete="new-password" value={pw2} onChange={(e) => setPw2(e.target.value)} />
        <ErrorNote error={error} />
        <Button variant="primary" size="lg" type="submit" disabled={busy || !token}>
          {busy ? 'Saving…' : 'Save new password'}
        </Button>
      </form>
    </AuthLayout>
  );
}

export function VerifyEmailPage() {
  const [params] = useSearchParams();
  const [state, setState] = useState<'working' | 'ok' | string>('working');
  useEffect(() => {
    const token = params.get('token');
    if (!token) return setState('This verification link is incomplete.');
    post('/auth/verify-email', { token }).then(
      () => {
        setState('ok');
        void refreshMe();
      },
      (e: Error) => setState(e.message),
    );
  }, [params]);
  return (
    <AuthLayout title="Email verification" footer={<Link to="/">Go to the dashboard</Link>}>
      {state === 'working' ? (
        <p className="muted">Verifying…</p>
      ) : state === 'ok' ? (
        <p className="form-success">Your email address is verified. Thank you!</p>
      ) : (
        <p className="form-error">{state}</p>
      )}
    </AuthLayout>
  );
}
