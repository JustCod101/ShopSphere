import { Button, Result, Typography } from 'antd';
import { Component, type ErrorInfo, type ReactNode } from 'react';
import { BizError } from '@/api/types';

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

function logError(error: Error, info?: ErrorInfo): void {
  // 占位:Phase 5 监控接入(Sentry / 自研)在此发送
  // eslint-disable-next-line no-console
  console.error('[ErrorBoundary]', error, info?.componentStack);
}

export class ErrorBoundary extends Component<Props, State> {
  override state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    logError(error, info);
  }

  private handleReload = () => {
    window.location.reload();
  };

  private handleHome = () => {
    window.location.assign('/');
  };

  override render(): ReactNode {
    const { error } = this.state;
    if (!error) return this.props.children;

    const isBiz = error instanceof BizError;
    const traceId = isBiz ? error.traceId : '';

    return (
      <Result
        status="500"
        title="页面出错了"
        subTitle={error.message || '未知错误'}
        extra={
          <>
            <Button type="primary" onClick={this.handleReload}>
              刷新页面
            </Button>
            <Button onClick={this.handleHome}>回首页</Button>
            {traceId && (
              <div style={{ marginTop: 16 }}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  trace: {traceId}
                </Typography.Text>
              </div>
            )}
          </>
        }
      />
    );
  }
}
