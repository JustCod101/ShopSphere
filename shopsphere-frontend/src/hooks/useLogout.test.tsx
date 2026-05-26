import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '@/store/auth';
import { useLogout } from './useLogout';

function wrapperFactory(qc: QueryClient, locationCapture: { pathname: string }) {
  function LocationProbe() {
    const loc = useLocation();
    locationCapture.pathname = loc.pathname;
    return null;
  }
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return (
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/orders']}>
          <Routes>
            <Route
              path="*"
              element={
                <>
                  <LocationProbe />
                  {children}
                </>
              }
            />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    );
  };
}

describe('useLogout', () => {
  beforeEach(() => {
    useAuthStore.setState({ token: null, expiresAt: null });
    sessionStorage.clear();
  });

  it('触发后 token 清空 + RQ 缓存被清 + 跳 /login', () => {
    const qc = new QueryClient();
    qc.setQueryData(['probe'], { v: 1 });
    const clearSpy = vi.spyOn(qc, 'clear');
    useAuthStore.getState().setToken('tk', 3600);

    const locationCapture = { pathname: '' };
    const { result } = renderHook(() => useLogout(), {
      wrapper: wrapperFactory(qc, locationCapture),
    });

    act(() => {
      result.current();
    });

    expect(useAuthStore.getState().token).toBeNull();
    expect(clearSpy).toHaveBeenCalledTimes(1);
    expect(qc.getQueryData(['probe'])).toBeUndefined();
    expect(locationCapture.pathname).toBe('/login');
  });
});
