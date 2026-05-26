import { Suspense } from 'react';
import { Layout } from 'antd';
import { Outlet } from 'react-router-dom';
import { Header } from './Header';
import { Footer } from './Footer';
import { GlobalCountdownBar } from './GlobalCountdownBar';
import { PageLoading } from '@/components/feedback/PageLoading';

const { Content } = Layout;

export function MainLayout() {
  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header />
      <GlobalCountdownBar />
      <Content style={{ padding: '24px 16px', maxWidth: 1200, width: '100%', margin: '0 auto' }}>
        <Suspense fallback={<PageLoading />}>
          <Outlet />
        </Suspense>
      </Content>
      <Footer />
    </Layout>
  );
}
