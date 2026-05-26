import { Typography } from 'antd';

const { Title, Paragraph } = Typography;

export default function OrderListPage() {
  return (
    <div>
      <Title level={2}>我的订单</Title>
      <Paragraph type="secondary">F0.4 接入 /api/order/list。</Paragraph>
    </div>
  );
}
