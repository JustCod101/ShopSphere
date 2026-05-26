import { Typography } from 'antd';

const { Title, Paragraph } = Typography;

export default function ProfilePage() {
  return (
    <div>
      <Title level={2}>个人资料</Title>
      <Paragraph type="secondary">F0.4 接入 /api/user/me。</Paragraph>
    </div>
  );
}
