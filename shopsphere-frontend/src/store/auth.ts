import { create } from 'zustand';

interface AuthState {
  token: string | null;
  expiresAt: number | null;
  setToken: (token: string | null, expiresInSec?: number) => void;
  logout: () => void;
  getToken: () => string | null;
  isAuthenticated: () => boolean;
}

const STORAGE_KEY = 'shopsphere.auth';
const LEGACY_TOKEN_KEY = 'shopsphere.token';

interface PersistedAuth {
  token: string | null;
  expiresAt: number | null;
}

function readPersisted(): PersistedAuth {
  if (typeof sessionStorage === 'undefined') return { token: null, expiresAt: null };
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<PersistedAuth>;
      return {
        token: typeof parsed.token === 'string' ? parsed.token : null,
        expiresAt: typeof parsed.expiresAt === 'number' ? parsed.expiresAt : null,
      };
    }
    // F0.2 旧键迁移
    const legacy = sessionStorage.getItem(LEGACY_TOKEN_KEY);
    if (legacy) {
      sessionStorage.removeItem(LEGACY_TOKEN_KEY);
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ token: legacy, expiresAt: null }));
      return { token: legacy, expiresAt: null };
    }
  } catch {
    // 损坏 JSON 视同未登录
  }
  return { token: null, expiresAt: null };
}

function writePersisted(p: PersistedAuth): void {
  if (typeof sessionStorage === 'undefined') return;
  if (p.token === null) sessionStorage.removeItem(STORAGE_KEY);
  else sessionStorage.setItem(STORAGE_KEY, JSON.stringify(p));
}

const initial = readPersisted();

export const useAuthStore = create<AuthState>((set, get) => ({
  token: initial.token,
  expiresAt: initial.expiresAt,
  setToken: (token, expiresInSec) => {
    const expiresAt =
      token && typeof expiresInSec === 'number' ? Date.now() + expiresInSec * 1000 : null;
    writePersisted({ token, expiresAt });
    set({ token, expiresAt });
  },
  logout: () => {
    writePersisted({ token: null, expiresAt: null });
    set({ token: null, expiresAt: null });
  },
  getToken: () => get().token,
  isAuthenticated: () => {
    const { token, expiresAt } = get();
    if (!token) return false;
    if (expiresAt === null) return true;
    return expiresAt > Date.now();
  },
}));
