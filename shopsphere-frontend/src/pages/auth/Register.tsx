import { Card, Typography } from 'antd';

const { Title, Paragraph } = Typography;

export default function RegisterPage() {
  return (
    <Card style={{ width: 380, margin: '0 auto' }}>
      <Title level={3}>注册</Title>
      <Paragraph type="secondary">注册表单将在 F0.4 接入 /api/user/register。</Paragraph>
    </Card>
  );
}
