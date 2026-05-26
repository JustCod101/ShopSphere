import axios, {
  type AxiosError,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios';
import { useAuthStore } from '@/store/auth';
import { BizError, ErrorCode, type PageResult, type Result } from './types';

const HTTP_TIMEOUT_MS = 15_000;
const LOGIN_PATH = '/login';
const ORDER_CREATE_PATH = '/order/create';

const instance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE,
  timeout: HTTP_TIMEOUT_MS,
  validateStatus: () => true,
});

instance.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = useAuthStore.getState().getToken();
  if (token && !config.headers.has('Authorization')) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }

  const url = config.url ?? '';
  if (url.includes(ORDER_CREATE_PATH) && !config.headers.has('X-Request-Id')) {
    throw new BizError(
      ErrorCode.PARAM_INVALID,
      '调用 /order/create 必须提供 X-Request-Id(请用 useIdempotentRequestId)',
    );
  }

  config.headers.delete('X-User-Id');
  config.headers.delete('X-User-Name');
  config.headers.delete('X-Trace-Id');

  return config;
});

function isResult(x: unknown): x is Result<unknown> {
  return !!x && typeof x === 'object' && 'code' in x && 'message' in x;
}

function redirectToLoginOnce(): void {
  if (typeof window === 'undefined') return;
  const { pathname, search } = window.location;
  if (pathname.startsWith(LOGIN_PATH)) return;
  const redirect = encodeURIComponent(pathname + search);
  window.location.assign(`${LOGIN_PATH}?redirect=${redirect}`);
}

instance.interceptors.response.use(
  // 解包 Result<T>.data 后,后续 .then 看到的不是 AxiosResponse 而是业务数据本身;
  // axios v1 允许这种 transform,但 TS 签名仍按 AxiosResponse 走 — 用 unknown 桥接。
  (resp: AxiosResponse): AxiosResponse => {
    if (resp.status !== 200) {
      throw new BizError(ErrorCode.SERVER_ERROR, '网络错误');
    }
    if (!isResult(resp.data)) {
      throw new BizError(ErrorCode.SERVER_ERROR, '响应格式错误');
    }
    const r = resp.data as Result<unknown>;
    if (r.code === ErrorCode.OK) return r.data as unknown as AxiosResponse;
    if (r.code === ErrorCode.UNAUTHORIZED) {
      useAuthStore.getState().logout();
      redirectToLoginOnce();
      throw new BizError(r.code, r.message, r.traceId);
    }
    throw new BizError(r.code, r.message, r.traceId);
  },
  (err: AxiosError) => {
    if (err instanceof BizError) return Promise.reject(err);
    if (err.code === 'ERR_CANCELED') {
      return Promise.reject(new BizError(ErrorCode.REQUEST_ABORTED, '请求已取消'));
    }
    return Promise.reject(new BizError(ErrorCode.SERVER_ERROR, '网络错误'));
  },
);

export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  return instance.request<unknown, T>(config);
}

export async function requestPage<T>(config: AxiosRequestConfig): Promise<PageResult<T>> {
  return request<PageResult<T>>(config);
}

export const __http_instance = instance;
