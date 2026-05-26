import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  Badge,
  Button,
  Drawer,
  Dropdown,
  Grid,
  Input,
  Layout,
  Space,
  Typography,
  type MenuProps,
} from 'antd';
import { MenuOutlined, ShoppingCartOutlined, UserOutlined } from '@ant-design/icons';
import { useAuthStore } from '@/store/auth';
import { useCartStore } from '@/store/cart';
import { useLogout } from '@/hooks/useLogout';

const { Header: AntHeader } = Layout;
const { useBreakpoint } = Grid;

function Logo() {
  return (
    <Link to="/" style={{ color: '#fff', fontSize: 20, fontWeight: 600 }}>
      <Typography.Text style={{ color: '#fff' }}>ShopSphere</Typography.Text>
    </Link>
  );
}

function CategoryLink() {
  return (
    <Link to="/products" style={{ color: '#fff' }}>
      全部商品
    </Link>
  );
}

function SearchBox({ onSubmit }: { onSubmit?: (q: string) => void }) {
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const submit = () => {
    const keyword = q.trim();
    if (!keyword) return;
    navigate(`/products?keyword=${encodeURIComponent(keyword)}`);
    onSubmit?.(keyword);
  };
  return (
    <Input.Search
      placeholder="搜索商品"
      value={q}
      onChange={(e) => setQ(e.target.value)}
      onSearch={submit}
      allowClear
      style={{ maxWidth: 360 }}
    />
  );
}

function CartBadge() {
  const count = useCartStore((s) => s.getCount());
  return (
    <Link to="/cart" aria-label="购物车">
      <Badge count={count} size="small" offset={[-2, 2]}>
        <ShoppingCartOutlined style={{ fontSize: 22, color: '#fff' }} />
      </Badge>
    </Link>
  );
}

function UserMenu() {
  const authed = useAuthStore((s) => s.isAuthenticated());
  const logout = useLogout();
  const navigate = useNavigate();

  if (!authed) {
    return (
      <Space>
        <Button type="link" style={{ color: '#fff' }} onClick={() => navigate('/login')}>
          登录
        </Button>
        <Button type="primary" ghost onClick={() => navigate('/register')}>
          注册
        </Button>
      </Space>
    );
  }

  const items: MenuProps['items'] = [
    { key: 'orders', label: '我的订单', onClick: () => navigate('/orders') },
    { key: 'profile', label: '个人资料', onClick: () => navigate('/me') },
    { type: 'divider' },
    { key: 'logout', label: '退出登录', danger: true, onClick: () => logout() },
  ];

  return (
    <Dropdown menu={{ items }} placement="bottomRight">
      <Button type="text" style={{ color: '#fff' }} icon={<UserOutlined />}>
        我的
      </Button>
    </Dropdown>
  );
}

export function Header() {
  const screens = useBreakpoint();
  const isDesktop = !!screens['md'];
  const [drawerOpen, setDrawerOpen] = useState(false);

  return (
    <AntHeader
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 16,
        padding: '0 16px',
        position: 'sticky',
        top: 0,
        zIndex: 100,
      }}
    >
      {!isDesktop && (
        <Button
          type="text"
          icon={<MenuOutlined style={{ color: '#fff', fontSize: 20 }} />}
          onClick={() => setDrawerOpen(true)}
          aria-label="菜单"
        />
      )}
      <Logo />
      {isDesktop && (
        <>
          <CategoryLink />
          <div style={{ flex: 1, maxWidth: 360 }}>
            <SearchBox />
          </div>
        </>
      )}
      <div style={{ flex: 1 }} />
      <Space size="middle">
        <CartBadge />
        <UserMenu />
      </Space>

      <Drawer
        placement="left"
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        title="ShopSphere"
        width={280}
      >
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <SearchBox onSubmit={() => setDrawerOpen(false)} />
          <Link to="/products" onClick={() => setDrawerOpen(false)}>
            全部商品
          </Link>
          <Link to="/cart" onClick={() => setDrawerOpen(false)}>
            购物车
          </Link>
        </Space>
      </Drawer>
    </AntHeader>
  );
}
