import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '@/store/auth';
import { __http_instance, request, requestPage } from './http';
import { BizError, ErrorCode, type PageResult } from './types';

const mock = new MockAdapter(__http_instance);

interface FakeProduct {
  id: number;
  name: string;
}

function okResult<T>(data: T, traceId = 't-1') {
  return {
    code: 0,
    message: 'ok',
    data,
    traceId,
    timestamp: '2026-05-26T10:00:00Z',
  };
}

function failResult(code: number, message: string, traceId = 't-1') {
  return { code, message, data: null, traceId, timestamp: '2026-05-26T10:00:00Z' };
}

function stubLocation(pathname: string, search = '') {
  const assignSpy = vi.fn();
  Object.defineProperty(window, 'location', {
    configurable: true,
    writable: true,
    value: { pathname, search, assign: assignSpy, href: `http://localhost${pathname}${search}` },
  });
  return assignSpy;
}

describe('http.ts 响应拦截器', () => {
  beforeEach(() => {
    mock.reset();
    useAuthStore.setState({ token: null });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('code=0 → 解包返回 data', async () => {
    mock.onGet('/product/1').reply(200, okResult<FakeProduct>({ id: 1, name: 'A' }));
    const data = await request<FakeProduct>({ url: '/product/1', method: 'GET' });
    expect(data).toEqual({ id: 1, name: 'A' });
  });

  it('code=3002 → 抛 BizError 三字段', async () => {
    mock.onGet('/product/9').reply(200, failResult(3002, '库存不足', 'trace-xyz'));
    await expect(request({ url: '/product/9', method: 'GET' })).rejects.toMatchObject({
      code: 3002,
      message: '库存不足',
      traceId: 'trace-xyz',
    });
  });

  it('code=1001 → logout + 跳 /login?redirect=...', async () => {
    const assignSpy = stubLocation('/cart', '?foo=1');
    useAuthStore.setState({ token: 'abc' });
    mock.onGet('/me').reply(200, failResult(1001, 'unauth'));

    await expect(request({ url: '/me', method: 'GET' })).rejects.toBeInstanceOf(BizError);
    expect(useAuthStore.getState().token).toBeNull();
    expect(assignSpy).toHaveBeenCalledTimes(1);
    const url = assignSpy.mock.calls[0]![0] as string;
    expect(url).toContain('/login?redirect=');
    expect(url).toContain(encodeURIComponent('/cart?foo=1'));
  });

  it('已在 /login 时,1001 不二次跳转', async () => {
    const assignSpy = stubLocation('/login', '?redirect=%2Fcart');
    mock.onPost('/user/login').reply(200, failResult(1001, 'unauth'));

    await expect(request({ url: '/user/login', method: 'POST' })).rejects.toBeInstanceOf(BizError);
    expect(assignSpy).not.toHaveBeenCalled();
  });

  it('HTTP 500 → BizError(SERVER_ERROR, "网络错误")', async () => {
    mock.onGet('/x').reply(500, { whatever: true });
    await expect(request({ url: '/x', method: 'GET' })).rejects.toMatchObject({
      code: ErrorCode.SERVER_ERROR,
      message: '网络错误',
    });
  });

  it('响应体非 Result 结构 → SERVER_ERROR "响应格式错误"', async () => {
    mock.onGet('/raw').reply(200, { foo: 'bar' });
    await expect(request({ url: '/raw', method: 'GET' })).rejects.toMatchObject({
      code: ErrorCode.SERVER_ERROR,
      message: '响应格式错误',
    });
  });

  it('网络错误(network error)→ SERVER_ERROR', async () => {
    mock.onGet('/x').networkError();
    await expect(request({ url: '/x', method: 'GET' })).rejects.toMatchObject({
      code: ErrorCode.SERVER_ERROR,
      message: '网络错误',
    });
  });
});

describe('http.ts 请求拦截器', () => {
  beforeEach(() => {
    mock.reset();
    useAuthStore.setState({ token: null });
  });

  it('/order/create 缺 X-Request-Id → 抛 BizError(PARAM_INVALID),不发请求', async () => {
    mock.onPost('/order/create').reply(200, okResult({ orderId: 1 }));

    await expect(
      request({ url: '/order/create', method: 'POST', data: { items: [] } }),
    ).rejects.toMatchObject({ code: ErrorCode.PARAM_INVALID });

    expect(mock.history.post ?? []).toHaveLength(0);
  });

  it('/order/create 带 X-Request-Id → 正常通过', async () => {
    mock.onPost('/order/create').reply(200, okResult({ orderId: 1 }));

    const data = await request<{ orderId: number }>({
      url: '/order/create',
      method: 'POST',
      headers: { 'X-Request-Id': 'req-uuid-1' },
      data: { items: [] },
    });

    expect(data).toEqual({ orderId: 1 });
    const postHistory = mock.history.post ?? [];
    expect(postHistory).toHaveLength(1);
    const h = postHistory[0]!.headers as Record<string, string>;
    expect(h['X-Request-Id']).toBe('req-uuid-1');
  });

  it('有 token → 自动注入 Authorization: Bearer', async () => {
    useAuthStore.setState({ token: 'tk-001' });
    mock.onGet('/me').reply(200, okResult({ id: 1 }));

    await request({ url: '/me', method: 'GET' });
    const h = (mock.history.get ?? [])[0]!.headers as Record<string, string>;
    expect(h['Authorization']).toBe('Bearer tk-001');
  });

  it('无 token → 不注入 Authorization', async () => {
    mock.onGet('/product/1').reply(200, okResult({ id: 1 }));
    await request({ url: '/product/1', method: 'GET' });
    const h = (mock.history.get ?? [])[0]!.headers as Record<string, string>;
    expect(h['Authorization']).toBeUndefined();
  });

  it('剥离 X-User-Id / X-User-Name / X-Trace-Id(即便业务硬塞)', async () => {
    mock.onGet('/x').reply(200, okResult(null));
    await request({
      url: '/x',
      method: 'GET',
      headers: {
        'X-User-Id': '666',
        'X-User-Name': 'mallory',
        'X-Trace-Id': 'forge-trace',
      },
    });
    const h = (mock.history.get ?? [])[0]!.headers as Record<string, string>;
    expect(h['X-User-Id']).toBeUndefined();
    expect(h['X-User-Name']).toBeUndefined();
    expect(h['X-Trace-Id']).toBeUndefined();
  });
});

describe('requestPage', () => {
  beforeEach(() => {
    mock.reset();
  });

  it('返回 PageResult<T> 形态', async () => {
    const page: PageResult<FakeProduct> = {
      records: [{ id: 1, name: 'A' }],
      total: 1,
      page: 1,
      size: 20,
    };
    mock.onGet('/product/list').reply(200, okResult(page));

    const data = await requestPage<FakeProduct>({ url: '/product/list', method: 'GET' });
    expect(data.records).toHaveLength(1);
    expect(data.total).toBe(1);
    expect(data.page).toBe(1);
    expect(data.size).toBe(20);
  });
});
