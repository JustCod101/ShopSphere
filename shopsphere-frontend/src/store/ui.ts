import { create } from 'zustand';

export type ToastType = 'success' | 'info' | 'warning' | 'error';

export interface Toast {
  id: string;
  type: ToastType;
  message: string;
  description?: string;
  duration?: number;
}

export interface PendingPayment {
  orderId: number;
  payExpireAt: string;
}

interface UiState {
  toasts: Toast[];
  pendingPayment: PendingPayment | null;
  pushToast: (t: Omit<Toast, 'id'>) => void;
  consumeToast: (id: string) => void;
  setPendingPayment: (p: PendingPayment | null) => void;
  clearPendingPayment: () => void;
}

export const useUiStore = create<UiState>((set) => ({
  toasts: [],
  pendingPayment: null,
  pushToast: (t) =>
    set((s) => ({
      toasts: [...s.toasts, { ...t, id: crypto.randomUUID() }],
    })),
  consumeToast: (id) =>
    set((s) => ({
      toasts: s.toasts.filter((x) => x.id !== id),
    })),
  setPendingPayment: (p) => set({ pendingPayment: p }),
  clearPendingPayment: () => set({ pendingPayment: null }),
}));
