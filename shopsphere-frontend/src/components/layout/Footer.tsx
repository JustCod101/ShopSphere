import { Layout } from 'antd';

const { Footer: AntFooter } = Layout;

export function Footer() {
  return (
    <AntFooter style={{ textAlign: 'center', color: '#999' }}>
      © 2026 ShopSphere · 学习项目,非真实电商
    </AntFooter>
  );
}
