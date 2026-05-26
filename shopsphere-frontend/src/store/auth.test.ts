import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from './auth';

const STORAGE_KEY = 'shopsphere.auth';

function resetStore() {
  useAuthStore.setState({ token: null, expiresAt: null });
  sessionStorage.clear();
}

describe('useAuthStore', () => {
  beforeEach(resetStore);
  afterEach(() => {
    vi.useRealTimers();
  });

  it('初始 token=null, expiresAt=null, isAuthenticated()=false', () => {
    const s = useAuthStore.getState();
    expect(s.token).toBeNull();
    expect(s.expiresAt).toBeNull();
    expect(s.isAuthenticated()).toBe(false);
  });

  it('setToken(token, expiresIn) 后已认证 + expiresAt 接近 now+ms', () => {
    const now = Date.now();
    useAuthStore.getState().setToken('tk', 3600);
    const s = useAuthStore.getState();
    expect(s.token).toBe('tk');
    expect(s.isAuthenticated()).toBe(true);
    expect(s.expiresAt).not.toBeNull();
    expect(s.expiresAt!).toBeGreaterThanOrEqual(now + 3600_000 - 100);
    expect(s.expiresAt!).toBeLessThanOrEqual(now + 3600_000 + 100);
  });

  it('setToken(token) 单参兼容 — expiresAt=null,视为已认证', () => {
    useAuthStore.getState().setToken('tk');
    const s = useAuthStore.getState();
    expect(s.token).toBe('tk');
    expect(s.expiresAt).toBeNull();
    expect(s.isAuthenticated()).toBe(true);
  });

  it('expiresAt 已过期 → isAuthenticated()=false', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-05-26T10:00:00Z'));
    useAuthStore.getState().setToken('tk', 1);
    expect(useAuthStore.getState().isAuthenticated()).toBe(true);
    vi.setSystemTime(new Date('2026-05-26T10:00:02Z'));
    expect(useAuthStore.getState().isAuthenticated()).toBe(false);
  });

  it('logout() 清三字段', () => {
    useAuthStore.getState().setToken('tk', 3600);
    useAuthStore.getState().logout();
    const s = useAuthStore.getState();
    expect(s.token).toBeNull();
    expect(s.expiresAt).toBeNull();
    expect(s.isAuthenticated()).toBe(false);
  });

  it('持久化:setToken 后 sessionStorage 含 { token, expiresAt }', () => {
    useAuthStore.getState().setToken('tk-persist', 600);
    const raw = sessionStorage.getItem(STORAGE_KEY);
    expect(raw).not.toBeNull();
    const parsed = JSON.parse(raw!);
    expect(parsed.token).toBe('tk-persist');
    expect(typeof parsed.expiresAt).toBe('number');
  });

  it('logout 后 sessionStorage 也被清', () => {
    useAuthStore.getState().setToken('tk', 600);
    useAuthStore.getState().logout();
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });
});
