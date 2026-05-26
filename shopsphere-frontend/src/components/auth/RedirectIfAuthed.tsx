import { Navigate, Outlet, useSearchParams } from 'react-router-dom';
import { useAuthStore } from '@/store/auth';

export function RedirectIfAuthed() {
  const authed = useAuthStore((s) => s.isAuthenticated());
  const [params] = useSearchParams();
  if (authed) {
    const target = params.get('redirect') || '/';
    return <Navigate to={target} replace />;
  }
  return <Outlet />;
}
