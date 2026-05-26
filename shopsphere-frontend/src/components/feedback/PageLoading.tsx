import { Spin } from 'antd';

export function PageLoading() {
  return (
    <div
      style={{
        minHeight: '50vh',
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
      }}
    >
      <Spin size="large" />
    </div>
  );
}
