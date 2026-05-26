import { Typography } from 'antd';
import { useParams } from 'react-router-dom';

const { Title, Paragraph } = Typography;

export default function OrderSuccessPage() {
  const { orderId } = useParams<{ orderId: string }>();
  return (
    <div>
      <Title level={2}>下单成功 #{orderId}</Title>
      <Paragraph type="secondary">F0.4 显示 payExpireAt 倒计时 + 立即支付。</Paragraph>
    </div>
  );
}
