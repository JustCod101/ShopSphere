import { Card, Typography } from 'antd';

const { Title, Paragraph } = Typography;

export default function LoginPage() {
  return (
    <Card style={{ width: 380, margin: '0 auto' }}>
      <Title level={3}>登录</Title>
      <Paragraph type="secondary">登录表单将在 F0.4 接入 /api/user/login。</Paragraph>
    </Card>
  );
}
