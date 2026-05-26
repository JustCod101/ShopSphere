import { Typography } from 'antd';
import { useParams } from 'react-router-dom';

const { Title, Paragraph } = Typography;

export default function OrderDetailPage() {
  const { id } = useParams<{ id: string }>();
  return (
    <div>
      <Title level={2}>订单详情 #{id}</Title>
      <Paragraph type="secondary">F0.4 接入 /api/order/{`{id}`}。</Paragraph>
    </div>
  );
}
