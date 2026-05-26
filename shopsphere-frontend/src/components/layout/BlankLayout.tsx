import { Suspense } from 'react';
import { Layout } from 'antd';
import { Link, Outlet } from 'react-router-dom';
import { PageLoading } from '@/components/feedback/PageLoading';

export function BlankLayout() {
  return (
    <Layout
      style={{
        minHeight: '100vh',
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'center',
        alignItems: 'center',
        padding: 24,
      }}
    >
      <Link to="/" style={{ fontSize: 24, fontWeight: 600, marginBottom: 24 }}>
        ShopSphere
      </Link>
      <Suspense fallback={<PageLoading />}>
        <Outlet />
      </Suspense>
    </Layout>
  );
}
