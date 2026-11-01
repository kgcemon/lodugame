import { AnimatePresence, motion } from 'framer-motion';
import { Component, lazy, Suspense, useEffect, useState, type ReactNode } from 'react';
import { Navigate, Route, Routes, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { validMoves } from '../shared/engine';
import { audio } from './audio/audio';
import { chooseToken, requestRoll, setPaused, showDemo } from './game/controller';
import { net } from './game/net';
import { loadConfig, refreshMe, useAuth } from './store/auth';
import { useGame } from './store/game';
import { useSettings } from './store/settings';
import { Hud } from './ui/Hud';
import { Modals } from './ui/Modals';
import { ForgotPasswordPage, LoginPage, RegisterPage, ResetPasswordPage, safeNext, VerifyEmailPage } from './ui/platform/AuthPages';
import { Spinner } from './ui/platform/kit';
import { Dashboard, FriendsPage, HistoryPage, LeaderboardPage, PlayerPage, ProfilePage, SettingsPage, WalletPage } from './ui/platform/Pages';
import { CreateRoomPage, JoinRoomPage, PlayHub, QuickMatchPage } from './ui/platform/PlayPages';
import { RoomPage } from './ui/platform/RoomPage';
import { Shell } from './ui/platform/Shell';
import { Setup } from './ui/Setup';

// The 3D engine is the heaviest part of the app: load it after the first paint.
const Scene = lazy(() => import('./three/Scene').then((m) => ({ default: m.Scene })));
// Only admins open the admin console: keep it out of everyone's first download.
const AdminPage = lazy(() => import('./ui/platform/AdminPage').then((m) => ({ default: m.AdminPage })));

function webglAvailable(): boolean {
  try {
    const c = document.createElement('canvas');
    return !!(window.WebGLRenderingContext && (c.getContext('webgl2') || c.getContext('webgl')));
  } catch {
    return false;
  }
}

class SceneBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  state = { error: null as Error | null };
  static getDerivedStateFromError(error: Error) {
    return { error };
  }
  componentDidCatch(error: Error) {
    console.error('3D scene crashed', error);
  }
  render() {
    if (this.state.error) {
      return (
        <div className="fatal">
          <h2>The 3D view stopped working</h2>
          <p>{this.state.error.message}</p>
          <button className="btn btn-primary btn-md" onClick={() => this.setState({ error: null })}>
            Try again
          </button>
          <button className="btn btn-glass btn-md" onClick={() => location.reload()}>
            Reload
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

function Loader() {
  const [p, setP] = useState(8);
  useEffect(() => {
    const id = setInterval(() => setP((v) => Math.min(92, v + (100 - v) * 0.12)), 120);
    return () => clearInterval(id);
  }, []);
  return (
    <motion.div className="loader" exit={{ opacity: 0 }} transition={{ duration: 0.4 }}>
      <div className="logo small">
        <span className="logo-ludo">LUDO</span>
        <span className="logo-3d">3D</span>
      </div>
      <div className="loader-bar">
        <i style={{ width: `${p}%` }} />
      </div>
      <span className="muted">Building the board…</span>
    </motion.div>
  );
}

function Toasts() {
  const toasts = useGame((s) => s.toasts);
  return (
    <div className="toasts" aria-live="polite">
      <AnimatePresence>
        {toasts.map((t) => (
          <motion.div
            key={t.id}
            className="toast glass"
            style={t.color ? { ['--pc' as string]: `var(--${t.color})` } : undefined}
            initial={{ opacity: 0, y: -16, scale: 0.95 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -10 }}
          >
            {t.text}
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  );
}

function FullSpinner() {
  return (
    <div className="center-fill">
      <Spinner />
    </div>
  );
}

function RequireAuth({ children }: { children: ReactNode }) {
  const status = useAuth((s) => s.status);
  const loc = useLocation();
  if (status === 'loading') return <FullSpinner />;
  if (status === 'guest') return <Navigate to={`/login?next=${encodeURIComponent(loc.pathname + loc.search)}`} replace />;
  return <>{children}</>;
}

function GuestOnly({ children }: { children: ReactNode }) {
  const status = useAuth((s) => s.status);
  const [params] = useSearchParams();
  if (status === 'loading') return <FullSpinner />;
  if (status === 'ready') return <Navigate to={safeNext(params.get('next'))} replace />;
  return <>{children}</>;
}

function RequireAdmin({ children }: { children: ReactNode }) {
  const role = useAuth((s) => s.user?.role);
  if (role !== 'admin') return <Navigate to="/" replace />;
  return <>{children}</>;
}

function PracticePage() {
  const [params] = useSearchParams();
  const nav = useNavigate();
  return <Setup preset={params.get('preset') === 'local' ? 'local' : 'ai'} onBack={() => nav('/play')} />;
}

function PlayerRoute() {
  const { publicId = '' } = useParams();
  return <PlayerPage publicId={publicId} />;
}

export default function App() {
  const screen = useGame((s) => s.screen);
  const pendingNav = useGame((s) => s.pendingNav);
  const user = useAuth((s) => s.user);
  const nav = useNavigate();
  const [webgl] = useState(webglAvailable);
  const [sceneReady, setSceneReady] = useState(false);
  const inGame = screen === 'game';

  useEffect(() => {
    showDemo();
    void loadConfig();
    void refreshMe();
  }, []);

  // One authenticated game socket while signed in; it also restores an interrupted online match.
  useEffect(() => {
    if (user) void net.connect();
    else net.disconnect();
  }, [user?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  // Navigation requested from outside React (socket events, leaving a match).
  useEffect(() => {
    if (!pendingNav) return;
    nav(pendingNav);
    useGame.getState().set({ pendingNav: null });
  }, [pendingNav, nav]);

  // Keep the audio engine in sync with settings; unlock on the first gesture.
  useEffect(() => {
    const apply = () => {
      const s = useSettings.getState();
      audio.configure({ sound: s.sound, soundVolume: s.soundVolume, music: s.music, musicVolume: s.musicVolume });
    };
    apply();
    const unsub = useSettings.subscribe(apply);
    const unlock = () => audio.unlock();
    window.addEventListener('pointerdown', unlock);
    window.addEventListener('keydown', unlock);
    return () => {
      unsub();
      window.removeEventListener('pointerdown', unlock);
      window.removeEventListener('keydown', unlock);
    };
  }, []);

  // Keyboard: Space/Enter roll, 1-4 move, Esc pause.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const s = useGame.getState();
      const tag = (e.target as HTMLElement)?.tagName;
      // Typing in chat, or pressing a focused button, must never roll or move.
      const typing = tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || tag === 'BUTTON';
      if (s.screen !== 'game' || (typing && e.key !== 'Escape')) return;
      if (e.key === 'Escape') {
        if (s.chatOpen || s.emoteOpen) s.set({ chatOpen: false, emoteOpen: false });
        else if (s.modal) s.set({ modal: null });
        else setPaused(!s.paused);
      } else if ((e.key === ' ' || e.key === 'Enter') && !s.paused) {
        e.preventDefault();
        requestRoll();
      } else if (/^[1-4]$/.test(e.key) && s.view) {
        const t = Number(e.key) - 1;
        if (validMoves(s.view).includes(t)) chooseToken(t);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  return (
    <div className={`app ${inGame ? 'in-game' : 'in-pages'}`}>
      <div className="backdrop" />
      {/* The board renders only during a match; elsewhere the canvas is paused and hidden to save the GPU. */}
      {webgl && user && (
        <SceneBoundary>
          <div className={`stage ${inGame ? 'stage-game' : 'stage-hidden'}`} aria-hidden={!inGame}>
            <Suspense fallback={null}>
              <Scene active={inGame} onReady={() => setSceneReady(true)} />
            </Suspense>
          </div>
        </SceneBoundary>
      )}
      {inGame ? (
        <div className="ui">
          {webgl ? (
            <Hud />
          ) : (
            <div className="fatal">
              <h2>3D graphics are not available</h2>
              <p>Ludo 3D needs WebGL. Enable hardware acceleration in your browser settings or try a different browser.</p>
            </div>
          )}
          <Modals />
        </div>
      ) : (
        <div className="pages">
          <Routes>
            <Route path="/login" element={<GuestOnly><LoginPage /></GuestOnly>} />
            <Route path="/register" element={<GuestOnly><RegisterPage /></GuestOnly>} />
            <Route path="/forgot-password" element={<ForgotPasswordPage />} />
            <Route path="/reset-password" element={<ResetPasswordPage />} />
            <Route path="/verify-email" element={<VerifyEmailPage />} />
            <Route element={<RequireAuth><Shell /></RequireAuth>}>
              <Route path="/" element={<Dashboard />} />
              <Route path="/play" element={<PlayHub />} />
              <Route path="/quick" element={<QuickMatchPage />} />
              <Route path="/create" element={<CreateRoomPage />} />
              <Route path="/join" element={<JoinRoomPage />} />
              <Route path="/room" element={<RoomPage />} />
              <Route path="/practice" element={<PracticePage />} />
              <Route path="/friends" element={<FriendsPage />} />
              <Route path="/leaderboard" element={<LeaderboardPage />} />
              <Route path="/player/:publicId" element={<PlayerRoute />} />
              <Route path="/wallet" element={<WalletPage />} />
              <Route path="/history" element={<HistoryPage />} />
              <Route path="/profile" element={<ProfilePage />} />
              <Route path="/settings" element={<SettingsPage />} />
              <Route path="/admin" element={<RequireAdmin><Suspense fallback={<Spinner />}><AdminPage /></Suspense></RequireAdmin>} />
            </Route>
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </div>
      )}
      <Toasts />
      <AnimatePresence>{inGame && webgl && !sceneReady && <Loader key="loader" />}</AnimatePresence>
    </div>
  );
}
