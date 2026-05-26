import { beforeEach, describe, expect, it } from 'vitest';
import { BizError, ErrorCode } from '@/api/types';
import { useUiStore } from '@/store/ui';
import { handleApiError, mapErrorToToast } from './error-mapper';

describe('mapErrorToToast', () => {
  it('1001 静默', () => {
    expect(mapErrorToToast(new BizError(ErrorCode.UNAUTHORIZED, 'no auth'))).toBeNull();
  });

  it('REQUEST_ABORTED 静默', () => {
    expect(mapErrorToToast(new BizError(ErrorCode.REQUEST_ABORTED, 'canceled'))).toBeNull();
  });

  it('1003 warning + 固定文案', () => {
    const spec = mapErrorToToast(new BizError(ErrorCode.RATE_LIMITED, ''))!;
    expect(spec.type).toBe('warning');
    expect(spec.message).toBe('操作过于频繁,请稍后再试');
  });

  it('3002 warning + 前端固定文案(忽略后端 message)', () => {
    const spec = mapErrorToToast(new BizError(ErrorCode.STOCK_INSUFFICIENT, '后端原文'))!;
    expect(spec.type).toBe('warning');
    expect(spec.message).toBe('库存不足');
  });

  it('2001 error + 后端 message 原文', () => {
    const spec = mapErrorToToast(new BizError(ErrorCode.USERNAME_EXISTS, 'username taken'))!;
    expect(spec.type).toBe('error');
    expect(spec.message).toBe('username taken');
  });

  it('4003 error + 固定文案', () => {
    const spec = mapErrorToToast(new BizError(ErrorCode.ORDER_TX_ROLLBACK, 'tx rolled back'))!;
    expect(spec).toMatchObject({ type: 'error', message: '下单失败,请重试' });
  });

  it('未知 code 落 default', () => {
    const spec = mapErrorToToast(new BizError(9999, 'whatever'))!;
    expect(spec.type).toBe('error');
    expect(spec.message).toBe('whatever');
  });

  it('未知 code 且无 message → 兜底文案带 code', () => {
    const spec = mapErrorToToast(new BizError(9999, ''))!;
    expect(spec.message).toBe('操作失败(code=9999)');
  });
});

describe('handleApiError', () => {
  beforeEach(() => {
    useUiStore.setState({ toasts: [] });
  });

  it('BizError → 推入 ui store', () => {
    handleApiError(new BizError(ErrorCode.STOCK_INSUFFICIENT, 'x'));
    const toasts = useUiStore.getState().toasts;
    expect(toasts).toHaveLength(1);
    expect(toasts[0]!.type).toBe('warning');
    expect(toasts[0]!.message).toBe('库存不足');
  });

  it('BizError(1001) 静默 → 不推入', () => {
    handleApiError(new BizError(ErrorCode.UNAUTHORIZED, 'no auth'));
    expect(useUiStore.getState().toasts).toHaveLength(0);
  });

  it('非 BizError → 推"未知错误"', () => {
    handleApiError(new Error('raw js error'));
    const toasts = useUiStore.getState().toasts;
    expect(toasts).toHaveLength(1);
    expect(toasts[0]!.message).toBe('未知错误');
  });
});
