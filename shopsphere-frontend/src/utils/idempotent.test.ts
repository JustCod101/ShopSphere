import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getOrCreateRequestId, resetRequestId } from './idempotent';

describe('idempotent request id', () => {
  beforeEach(() => {
    sessionStorage.clear();
    vi.useRealTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('同一 key 多次调用返回同一 UUID', () => {
    const a = getOrCreateRequestId('order-submit-001');
    const b = getOrCreateRequestId('order-submit-001');
    const c = getOrCreateRequestId('order-submit-001');
    expect(a).toBe(b);
    expect(b).toBe(c);
    expect(a).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('不同 key 不互通', () => {
    const a = getOrCreateRequestId('a');
    const b = getOrCreateRequestId('b');
    expect(a).not.toBe(b);
  });

  it('resetRequestId 后产生新 UUID', () => {
    const a = getOrCreateRequestId('k');
    resetRequestId('k');
    const b = getOrCreateRequestId('k');
    expect(a).not.toBe(b);
  });

  it('超 5min 过期,产生新 UUID', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-05-26T10:00:00Z'));
    const a = getOrCreateRequestId('k');

    vi.setSystemTime(new Date('2026-05-26T10:06:00Z'));
    const b = getOrCreateRequestId('k');
    expect(a).not.toBe(b);
  });

  it('跨"刷新"持久化(sessionStorage 还原)', () => {
    const a = getOrCreateRequestId('k');
    // 模拟刷新:沒有清掉 sessionStorage,直接再读
    const b = getOrCreateRequestId('k');
    expect(a).toBe(b);
  });
});
