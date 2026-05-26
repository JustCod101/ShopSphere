import { create } from 'zustand';

export interface CartItem {
  productId: number;
  quantity: number;
  name: string;
  price: number;
}

interface CartState {
  items: CartItem[];
  getCount: () => number;
}

export const useCartStore = create<CartState>((_set, get) => ({
  items: [],
  getCount: () => get().items.reduce((sum, it) => sum + it.quantity, 0),
}));
