import { describe, expect, it } from 'vitest';
import { BizError, ErrorCode } from './types';

describe('BizError', () => {
  it('instanceof Error 与 BizError', () => {
    const e = new BizError(1001, 'unauth', 'trace-abc');
    expect(e).toBeInstanceOf(Error);
    expect(e).toBeInstanceOf(BizError);
    expect(e.name).toBe('BizError');
    expect(e.code).toBe(1001);
    expect(e.message).toBe('unauth');
    expect(e.traceId).toBe('trace-abc');
  });

  it('traceId 可省略,默认空串', () => {
    const e = new BizError(3002, '库存不足');
    expect(e.traceId).toBe('');
  });
});

describe('ErrorCode 常量集', () => {
  it('成功码与关键业务码', () => {
    expect(ErrorCode.OK).toBe(0);
    expect(ErrorCode.UNAUTHORIZED).toBe(1001);
    expect(ErrorCode.STOCK_INSUFFICIENT).toBe(3002);
    expect(ErrorCode.ORDER_TX_ROLLBACK).toBe(4003);
  });

  it('本地造的负码不与后端号段冲突', () => {
    expect(ErrorCode.SERVER_ERROR).toBeLessThan(0);
    expect(ErrorCode.REQUEST_ABORTED).toBeLessThan(0);
  });
});
