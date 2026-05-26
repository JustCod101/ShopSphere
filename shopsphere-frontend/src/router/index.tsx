import { lazy } from 'react';
import { Route, Routes } from 'react-router-dom';
import { MainLayout } from '@/components/layout/MainLayout';
import { BlankLayout } from '@/components/layout/BlankLayout';
import { ScrollToTop } from '@/components/layout/ScrollToTop';
import { RequireAuth } from '@/components/auth/RequireAuth';
import { RedirectIfAuthed } from '@/components/auth/RedirectIfAuthed';

const Home = lazy(() => import('@/pages/home/Home'));
const Login = lazy(() => import('@/pages/auth/Login'));
const Register = lazy(() => import('@/pages/auth/Register'));
const ProductList = lazy(() => import('@/pages/product/List'));
const ProductDetail = lazy(() => import('@/pages/product/Detail'));
const Cart = lazy(() => import('@/pages/cart/Cart'));
const Checkout = lazy(() => import('@/pages/checkout/Checkout'));
const OrderSuccess = lazy(() => import('@/pages/checkout/OrderSuccess'));
const OrderList = lazy(() => import('@/pages/order/List'));
const OrderDetail = lazy(() => import('@/pages/order/Detail'));
const Profile = lazy(() => import('@/pages/user/Profile'));
const NotFound = lazy(() => import('@/pages/NotFound'));

export function AppRouter() {
  return (
    <>
      <ScrollToTop />
      <Routes>
        <Route element={<BlankLayout />}>
          <Route element={<RedirectIfAuthed />}>
            <Route path="/login" element={<Login />} />
            <Route path="/register" element={<Register />} />
          </Route>
        </Route>

        <Route element={<MainLayout />}>
          <Route index element={<Home />} />
          <Route path="products" element={<ProductList />} />
          <Route path="products/:id" element={<ProductDetail />} />
          <Route path="cart" element={<Cart />} />

          <Route element={<RequireAuth />}>
            <Route path="checkout" element={<Checkout />} />
            <Route path="checkout/success/:orderId" element={<OrderSuccess />} />
            <Route path="orders" element={<OrderList />} />
            <Route path="orders/:id" element={<OrderDetail />} />
            <Route path="me" element={<Profile />} />
          </Route>

          <Route path="*" element={<NotFound />} />
        </Route>
      </Routes>
    </>
  );
}
