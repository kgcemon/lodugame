import { AnimatePresence, motion } from 'framer-motion';
import { useEffect, useState } from 'react';
import { Navigate, NavLink, useNavigate, useParams } from 'react-router-dom';
import { logout, useAuth } from '../../store/auth';
import { useToasts } from '../../store/toast';
import { Avatar } from '../common';
import { ADMIN_SECTIONS } from './AdminPage';

/**
 * The website is the LudoX admin panel (players play in the Android app): a sidebar of
 * sections grouped by area, a top bar naming the open section, and the section itself.
 * On a phone the sidebar slides in from a menu button.
 */

function useSignOut() {
  const nav = useNavigate();
  return async () => {
    await logout();
    nav('/login', { replace: true });
  };
}

export function AdminShell() {
  const { section = '' } = useParams();
  const user = useAuth((s) => s.user);
  const signOut = useSignOut();
  const [menu, setMenu] = useState(false);
  const current = ADMIN_SECTIONS.find((s) => s.path === section);
  useEffect(() => {
    setMenu(false);
    document.querySelector('.admin-main')?.scrollTo({ top: 0 });
    if (current) document.title = `${current.label} · LudoX Admin`;
  }, [section]); // eslint-disable-line react-hooks/exhaustive-deps
  if (!current) return <Navigate to="/admin/overview" replace />;
  const groups = [...new Set(ADMIN_SECTIONS.map((s) => s.group))];
  const Page = current.page;
  return (
    <div className="admin-app">
      <aside className={`admin-side ${menu ? 'open' : ''}`} aria-label="Admin sections">
        <div className="admin-brand">
          <span className="admin-logo" aria-hidden>
            👑
          </span>
          <div>
            <b>LudoX</b>
            <span>Admin panel</span>
          </div>
        </div>
        <nav className="admin-nav">
          {groups.map((g) => (
            <div className="admin-nav-group" key={g}>
              <span className="admin-nav-title">{g}</span>
              {ADMIN_SECTIONS.filter((s) => s.group === g).map((s) => (
                <NavLink key={s.path} to={`/admin/${s.path}`} className={({ isActive }) => `admin-nav-item ${isActive ? 'active' : ''}`}>
                  <span className="admin-nav-icon" aria-hidden>
                    {s.icon}
                  </span>
                  {s.label}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
        {user && (
          <div className="admin-me">
            <Avatar name={user.fullName || user.username} hue={user.avatarHue} size={36} />
            <div className="admin-me-text">
              <b>{user.fullName || user.username}</b>
              <span>{user.email}</span>
            </div>
            <button className="admin-signout" onClick={signOut} title="Sign out" aria-label="Sign out">
              ⎋
            </button>
          </div>
        )}
      </aside>
      {menu && <div className="admin-scrim" onClick={() => setMenu(false)} />}
      <div className="admin-main">
        <header className="admin-top">
          <button className="admin-menu-btn" onClick={() => setMenu(true)} aria-label="Open menu">
            ☰
          </button>
          <div className="admin-top-title">
            <h1>
              <span aria-hidden>{current.icon}</span> {current.label}
            </h1>
            <p>{current.hint}</p>
          </div>
          <span className="admin-pill" title="Every change here is recorded in the audit log">
            🛡️ Audited
          </span>
        </header>
        <main className="admin-content">
          <Page key={current.path} />
        </main>
      </div>
      <AdminToasts />
    </div>
  );
}

/** Shown to a signed-in player: the website is only for administrators. */
export function AdminOnly() {
  const user = useAuth((s) => s.user);
  const signOut = useSignOut();
  return (
    <div className="auth-screen">
      <div className="auth-card glass admin-only">
        <span className="admin-logo big" aria-hidden>
          👑
        </span>
        <h1>LudoX Admin</h1>
        <p className="muted">
          This website is only for LudoX administrators{user ? `, and ${user.email} is not one of them` : ''}. Play LudoX in the Android app — your coins, diamonds and
          boards are all there.
        </p>
        <button className="btn btn-primary btn-lg" onClick={signOut}>
          <span>Sign out</span>
        </button>
      </div>
    </div>
  );
}

function AdminToasts() {
  const toasts = useToasts((s) => s.toasts);
  return (
    <div className="admin-toasts" aria-live="polite">
      <AnimatePresence>
        {toasts.map((t) => (
          <motion.div key={t.id} className="admin-toast" initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 8 }}>
            ✓ {t.text}
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  );
}
