import { Typography } from 'antd';

const { Title, Paragraph } = Typography;

export default function CheckoutPage() {
  return (
    <div>
      <Title level={2}>结算</Title>
      <Paragraph type="secondary">
        F0.4 接入 /api/order/create + useIdempotentRequestId。
      </Paragraph>
    </div>
  );
}
