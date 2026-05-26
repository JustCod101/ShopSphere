export interface Result<T> {
  code: number;
  message: string;
  data: T | null;
  traceId: string;
  timestamp: string;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
}

export const ErrorCode = {
  OK: 0,
  PARAM_INVALID: 1000,
  UNAUTHORIZED: 1001,
  RATE_LIMITED: 1003,
  NOT_FOUND_GATEWAY: 1004,
  INTERNAL_ERROR: 1500,
  USERNAME_EXISTS: 2001,
  PASSWORD_WRONG: 2002,
  USER_NOT_FOUND: 2003,
  PRODUCT_NOT_FOUND: 3001,
  STOCK_INSUFFICIENT: 3002,
  STOCK_TRY_FAILED: 3003,
  ORDER_NOT_FOUND: 4001,
  ORDER_STATE_ILLEGAL: 4002,
  ORDER_TX_ROLLBACK: 4003,
  RECO_COLD_START: 5001,
  RECO_MODEL_NOT_READY: 5002,
  SERVER_ERROR: -1,
  REQUEST_ABORTED: -2,
} as const;

export type ErrorCodeValue = (typeof ErrorCode)[keyof typeof ErrorCode];

export class BizError extends Error {
  readonly code: number;
  readonly traceId: string;

  constructor(code: number, message: string, traceId = '') {
    super(message);
    this.name = 'BizError';
    this.code = code;
    this.traceId = traceId;
    Object.setPrototypeOf(this, BizError.prototype);
  }
}
