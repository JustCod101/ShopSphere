import { Alert, Button } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useUiStore } from '@/store/ui';

export function GlobalCountdownBar() {
  const pending = useUiStore((s) => s.pendingPayment);
  const navigate = useNavigate();
  if (!pending) return null;

  return (
    <Alert
      type="warning"
      showIcon
      banner
      message={
        <span>
          订单 #{pending.orderId} 待支付(将于 {pending.payExpireAt} 过期)
        </span>
      }
      action={
        <Button size="small" type="primary" onClick={() => navigate(`/orders/${pending.orderId}`)}>
          立即支付
        </Button>
      }
    />
  );
}
