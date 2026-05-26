import { useCallback } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/store/auth';

export function useLogout() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const logout = useAuthStore((s) => s.logout);
  return useCallback(() => {
    logout();
    qc.clear();
    navigate('/login', { replace: true });
  }, [qc, navigate, logout]);
}
