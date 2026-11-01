import { AnimatePresence, motion } from 'framer-motion';
import { useState, type ReactNode } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { disconnect } from '../../game/net';
import { logout, markNotificationsRead, useAuth } from '../../store/auth';
import { useGame } from '../../store/game';
import { Avatar, IconButton } from '../common';
import {
  IconBell,
  IconBolt,
  IconGamepad,
  IconHistory,
  IconHome,
  IconKey,
  IconLogout,
  IconMenu,
  IconPlus,
  IconSettings,
  IconShield,
  IconTrophy,
  IconUser,
  IconUsers,
  IconWallet,
} from '../icons';
import { Coins, timeAgo } from './kit';

/** Signed-in layout: desktop sidebar, top bar, mobile bottom navigation. */

interface NavItem {
  to: string;
  label: string;
  icon: ReactNode;
  end?: boolean;
}

const NAV: NavItem[] = [
  { to: '/', label: 'Dashboard', icon: <IconHome />, end: true },
  { to: '/play', label: 'Play Game', icon: <IconGamepad /> },
  { to: '/quick', label: 'Quick Match', icon: <IconBolt /> },
  { to: '/create', label: 'Create Room', icon: <IconPlus /> },
  { to: '/join', label: 'Join Room', icon: <IconKey /> },
  { to: '/friends', label: 'Friends', icon: <IconUsers /> },
  { to: '/leaderboard', label: 'Leaderboard', icon: <IconTrophy /> },
  { to: '/wallet', label: 'Wallet', icon: <IconWallet /> },
  { to: '/history', label: 'Match History', icon: <IconHistory /> },
  { to: '/profile', label: 'Profile', icon: <IconUser /> },
  { to: '/settings', label: 'Settings', icon: <IconSettings /> },
];

const MOBILE: NavItem[] = [
  { to: '/', label: 'Home', icon: <IconHome />, end: true },
  { to: '/play', label: 'Play', icon: <IconGamepad /> },
  { to: '/friends', label: 'Friends', icon: <IconUsers /> },
  { to: '/wallet', label: 'Wallet', icon: <IconWallet /> },
];

function useSignOut() {
  const nav = useNavigate();
  return async () => {
    disconnect();
    useGame.getState().set({ room: null });
    await logout();
    nav('/login', { replace: true });
  };
}

function notificationText(n: { type: string; payload: Record<string, unknown> }): string {
  const p = n.payload as Record<string, string | number | boolean>;
  switch (n.type) {
    case 'friend_request': return `${p.from} sent you a friend request`;
    case 'friend_accepted': return `${p.by} accepted your friend request`;
    case 'room_invite': return `${p.from} invited you to a ${p.modeName} room · code ${p.code}${Number(p.entryFee) ? ` · ${p.entryFee} coins` : ''}`;
    case 'match_result': return p.won ? `You won! +${p.payout} coins` : `Match finished${p.rank ? ` · you placed #${p.rank}` : ''}`;
    case 'wallet': return `Administrator adjusted your balance by ${p.amount} coins${p.reason ? `: ${p.reason}` : ''}`;
    default: return 'Notification';
  }
}

function Notifications() {
  const [open, setOpen] = useState(false);
  const items = useAuth((s) => s.notifications);
  const unread = useAuth((s) => s.unread);
  const nav = useNavigate();
  const go = (n: (typeof items)[number]) => {
    setOpen(false);
    if (n.type === 'room_invite') nav(`/join?code=${encodeURIComponent(String(n.payload.code))}`);
    else if (n.type === 'friend_request' || n.type === 'friend_accepted') nav('/friends');
    else if (n.type === 'match_result') nav('/history');
    else if (n.type === 'wallet') nav('/wallet');
  };
  return (
    <div className="notif-wrap">
      <IconButton
        label={unread ? `Notifications (${unread} unread)` : 'Notifications'}
        className="chat-btn"
        onClick={() => {
          setOpen(!open);
          if (!open && unread) void markNotificationsRead();
        }}
      >
        <IconBell />
        {unread > 0 && <span className="badge">{unread > 9 ? '9+' : unread}</span>}
      </IconButton>
      <AnimatePresence>
        {open && (
          <>
            <div className="click-away" onClick={() => setOpen(false)} />
            <motion.div className="notif-menu glass" initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -6 }} role="menu">
              <h3>Notifications</h3>
              {items.length === 0 ? (
                <p className="muted">Nothing yet.</p>
              ) : (
                <ul>
                  {items.map((n) => (
                    <li key={n.id}>
                      <button role="menuitem" className={n.readAt ? '' : 'unread'} onClick={() => go(n)}>
                        <span>{notificationText(n)}</span>
                        <time>{timeAgo(n.createdAt)}</time>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </motion.div>
          </>
        )}
      </AnimatePresence>
    </div>
  );
}

function TopBar() {
  const user = useAuth((s) => s.user)!;
  const balance = useAuth((s) => s.wallet?.balance ?? 0);
  const nav = useNavigate();
  return (
    <header className="topbar">
      <button className="topbar-user" onClick={() => nav('/profile')} aria-label="Open profile">
        <Avatar name={user.username} hue={user.avatarHue} size={36} />
        <span className="topbar-name">{user.username}</span>
      </button>
      <button className="topbar-coins glass" onClick={() => nav('/wallet')} aria-label="Open wallet">
        <Coins value={balance} size={22} />
      </button>
      <div className="row gap-s">
        <Notifications />
        <IconButton label="Settings" onClick={() => nav('/settings')}>
          <IconSettings />
        </IconButton>
      </div>
    </header>
  );
}

function MoreSheet({ onClose }: { onClose: () => void }) {
  const isAdmin = useAuth((s) => s.user?.role === 'admin');
  const signOut = useSignOut();
  const items = NAV.filter((n) => !MOBILE.some((m) => m.to === n.to));
  return (
    <motion.div className="modal-backdrop sheet-backdrop" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={onClose}>
      <motion.nav
        className="sheet glass"
        aria-label="More"
        initial={{ y: '100%' }}
        animate={{ y: 0 }}
        exit={{ y: '100%' }}
        transition={{ type: 'spring', stiffness: 320, damping: 32 }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="sheet-grip" />
        {items.map((n) => (
          <NavLink key={n.to} to={n.to} className="sheet-link" onClick={onClose}>
            {n.icon}
            {n.label}
          </NavLink>
        ))}
        {isAdmin && (
          <NavLink to="/admin" className="sheet-link" onClick={onClose}>
            <IconShield /> Admin
          </NavLink>
        )}
        <button className="sheet-link danger" onClick={signOut}>
          <IconLogout /> Log out
        </button>
      </motion.nav>
    </motion.div>
  );
}

export function Shell() {
  const isAdmin = useAuth((s) => s.user?.role === 'admin');
  const signOut = useSignOut();
  const [more, setMore] = useState(false);
  return (
    <div className="shell">
      <aside className="sidebar glass" aria-label="Main navigation">
        <div className="logo small sidebar-logo">
          <span className="logo-ludo">LUDO</span>
          <span className="logo-3d">3D</span>
        </div>
        <nav>
          {NAV.map((n) => (
            <NavLink key={n.to} to={n.to} end={n.end} className="side-link">
              {n.icon}
              <span>{n.label}</span>
            </NavLink>
          ))}
          {isAdmin && (
            <NavLink to="/admin" className="side-link">
              <IconShield />
              <span>Admin</span>
            </NavLink>
          )}
        </nav>
        <button className="side-link logout" onClick={signOut}>
          <IconLogout />
          <span>Logout</span>
        </button>
      </aside>
      <div className="shell-main">
        <TopBar />
        <main className="shell-content">
          <Outlet />
        </main>
      </div>
      <nav className="bottom-nav glass" aria-label="Main navigation">
        {MOBILE.map((n) => (
          <NavLink key={n.to} to={n.to} end={n.end} className="bottom-link">
            {n.icon}
            <span>{n.label}</span>
          </NavLink>
        ))}
        <button className="bottom-link" onClick={() => setMore(true)} aria-haspopup="dialog">
          <IconMenu />
          <span>More</span>
        </button>
      </nav>
      <AnimatePresence>{more && <MoreSheet onClose={() => setMore(false)} />}</AnimatePresence>
    </div>
  );
}
