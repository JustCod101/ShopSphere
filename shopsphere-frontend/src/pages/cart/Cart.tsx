import { Typography } from 'antd';

const { Title, Paragraph } = Typography;

export default function CartPage() {
  return (
    <div>
      <Title level={2}>购物车</Title>
      <Paragraph type="secondary">F0.4 接入 useCartStore 增删改与去结算。</Paragraph>
    </div>
  );
}
