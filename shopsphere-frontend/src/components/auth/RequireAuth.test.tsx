import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '@/store/auth';
import { RequireAuth } from './RequireAuth';
import { RedirectIfAuthed } from './RedirectIfAuthed';

function Protected() {
  return <div data-testid="protected">Protected Content</div>;
}

function LoginStub() {
  return <div data-testid="login">Login Page</div>;
}

function HomeStub() {
  return <div data-testid="home">Home Page</div>;
}

function OrdersStub() {
  return <div data-testid="orders">Orders Page</div>;
}

function renderRoutes(initialPath: string) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route element={<RedirectIfAuthed />}>
          <Route path="/login" element={<LoginStub />} />
        </Route>
        <Route path="/" element={<HomeStub />} />
        <Route path="/orders" element={<OrdersStub />} />
        <Route element={<RequireAuth />}>
          <Route path="/checkout" element={<Protected />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe('RequireAuth', () => {
  beforeEach(() => {
    useAuthStore.setState({ token: null, expiresAt: null });
    sessionStorage.clear();
  });
  afterEach(() => {
    useAuthStore.setState({ token: null, expiresAt: null });
  });

  it('未登录访问受保护路由 → 跳 /login?redirect=', () => {
    renderRoutes('/checkout');
    expect(screen.getByTestId('login')).toBeInTheDocument();
    expect(screen.queryByTestId('protected')).not.toBeInTheDocument();
  });

  it('已登录(token + 未过期)→ 渲染子路由', () => {
    useAuthStore.getState().setToken('tk', 3600);
    renderRoutes('/checkout');
    expect(screen.getByTestId('protected')).toBeInTheDocument();
  });

  it('token 存在但已过期 → 重定向 /login', () => {
    useAuthStore.setState({ token: 'tk', expiresAt: Date.now() - 1000 });
    renderRoutes('/checkout');
    expect(screen.getByTestId('login')).toBeInTheDocument();
  });
});

describe('RedirectIfAuthed', () => {
  beforeEach(() => {
    useAuthStore.setState({ token: null, expiresAt: null });
    sessionStorage.clear();
  });

  it('未登录访问 /login → 渲染 LoginStub', () => {
    renderRoutes('/login');
    expect(screen.getByTestId('login')).toBeInTheDocument();
  });

  it('已登录访问 /login(无 redirect)→ 跳 /', () => {
    useAuthStore.getState().setToken('tk', 3600);
    renderRoutes('/login');
    expect(screen.getByTestId('home')).toBeInTheDocument();
  });

  it('已登录访问 /login?redirect=/orders → 跳 /orders', () => {
    useAuthStore.getState().setToken('tk', 3600);
    renderRoutes('/login?redirect=/orders');
    expect(screen.getByTestId('orders')).toBeInTheDocument();
  });
});
