import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/store/auth';

export function RequireAuth() {
  const authed = useAuthStore((s) => s.isAuthenticated());
  const loc = useLocation();
  if (!authed) {
    const redirect = encodeURIComponent(loc.pathname + loc.search);
    return <Navigate to={`/login?redirect=${redirect}`} replace />;
  }
  return <Outlet />;
}
