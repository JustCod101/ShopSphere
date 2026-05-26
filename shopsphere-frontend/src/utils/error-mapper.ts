import { BizError, ErrorCode } from '@/api/types';
import { useUiStore, type ToastType } from '@/store/ui';

export type ToastSpec = {
  type: ToastType;
  message: string;
  description?: string;
} | null;

export function mapErrorToToast(err: BizError): ToastSpec {
  const { code, message } = err;
  switch (code) {
    case ErrorCode.UNAUTHORIZED:
    case ErrorCode.REQUEST_ABORTED:
      return null;

    case ErrorCode.PARAM_INVALID:
      return { type: 'error', message: '请求参数有误', description: message };
    case ErrorCode.RATE_LIMITED:
      return { type: 'warning', message: '操作过于频繁,请稍后再试' };
    case ErrorCode.INTERNAL_ERROR:
    case ErrorCode.SERVER_ERROR:
      return { type: 'error', message: '服务暂不可用,请稍后重试' };

    case ErrorCode.USERNAME_EXISTS:
    case ErrorCode.PASSWORD_WRONG:
    case ErrorCode.USER_NOT_FOUND:
      return { type: 'error', message };

    case ErrorCode.PRODUCT_NOT_FOUND:
      return { type: 'error', message: '商品不存在或已下架' };
    case ErrorCode.STOCK_INSUFFICIENT:
      return { type: 'warning', message: '库存不足' };
    case ErrorCode.STOCK_TRY_FAILED:
      return { type: 'error', message: '库存预扣失败,请稍后重试' };

    case ErrorCode.ORDER_NOT_FOUND:
      return { type: 'error', message: '订单不存在' };
    case ErrorCode.ORDER_STATE_ILLEGAL:
      return { type: 'warning', message: '订单状态不允许此操作' };
    case ErrorCode.ORDER_TX_ROLLBACK:
      return { type: 'error', message: '下单失败,请重试' };

    default:
      return { type: 'error', message: message || `操作失败(code=${code})` };
  }
}

export function handleApiError(err: unknown): void {
  if (!(err instanceof BizError)) {
    useUiStore.getState().pushToast({ type: 'error', message: '未知错误' });
    return;
  }
  const spec = mapErrorToToast(err);
  if (spec) useUiStore.getState().pushToast(spec);
}
