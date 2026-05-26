import { Typography } from 'antd';

const { Title, Paragraph } = Typography;

export default function HomePage() {
  return (
    <div>
      <Title level={2}>首页</Title>
      <Paragraph type="secondary">
        欢迎来到 ShopSphere。推荐位、热门商品将在 F0.4 接入。
      </Paragraph>
    </div>
  );
}
