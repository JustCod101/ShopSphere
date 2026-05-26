import { Typography } from 'antd';
import { useParams } from 'react-router-dom';

const { Title, Paragraph } = Typography;

export default function ProductDetailPage() {
  const { id } = useParams<{ id: string }>();
  return (
    <div>
      <Title level={2}>商品详情 #{id}</Title>
      <Paragraph type="secondary">F0.4 接入 /api/product/{`{id}`}。</Paragraph>
    </div>
  );
}
