import { useEffect, type ReactNode } from 'react';
import { Navigate, Route, Routes, useLocation, useSearchParams } from 'react-router-dom';
import { refreshMe, useAuth } from './store/auth';
import { AdminOnly, AdminShell } from './ui/platform/AdminShell';
import { ForgotPasswordPage, LoginPage, ResetPasswordPage, safeNext, VerifyEmailPage } from './ui/platform/AuthPages';
import { Spinner } from './ui/platform/kit';

/**
 * The website is the LudoX admin panel: players play in the Android app. It keeps the
 * pages that emails link to (password reset, email verification); everything else is
 * the admin panel, for administrators only.
 */

function FullSpinner() {
  return (
    <div className="center-fill">
      <Spinner />
    </div>
  );
}

function RequireAdmin({ children }: { children: ReactNode }) {
  const status = useAuth((s) => s.status);
  const role = useAuth((s) => s.user?.role);
  const loc = useLocation();
  if (status === 'loading') return <FullSpinner />;
  if (status === 'guest') return <Navigate to={`/login?next=${encodeURIComponent(loc.pathname + loc.search)}`} replace />;
  if (role !== 'admin') return <AdminOnly />;
  return <>{children}</>;
}

function GuestOnly({ children }: { children: ReactNode }) {
  const status = useAuth((s) => s.status);
  const [params] = useSearchParams();
  if (status === 'loading') return <FullSpinner />;
  if (status === 'ready') return <Navigate to={safeNext(params.get('next'))} replace />;
  return <>{children}</>;
}

export default function App() {
  useEffect(() => {
    void refreshMe();
  }, []);

  return (
    <div className="app in-pages">
      <div className="backdrop" />
      <div className="pages">
        <Routes>
          <Route path="/login" element={<GuestOnly><LoginPage /></GuestOnly>} />
          <Route path="/forgot-password" element={<ForgotPasswordPage />} />
          <Route path="/reset-password" element={<ResetPasswordPage />} />
          <Route path="/verify-email" element={<VerifyEmailPage />} />
          <Route path="/admin/:section" element={<RequireAdmin><AdminShell /></RequireAdmin>} />
          <Route path="*" element={<Navigate to="/admin/overview" replace />} />
        </Routes>
      </div>
    </div>
  );
}
