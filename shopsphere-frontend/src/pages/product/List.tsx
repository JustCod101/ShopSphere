import { Typography } from 'antd';

const { Title, Paragraph } = Typography;

export default function ProductListPage() {
  return (
    <div>
      <Title level={2}>商品列表</Title>
      <Paragraph type="secondary">F0.4 接入 /api/product/list,含类目/关键词筛选。</Paragraph>
    </div>
  );
}
