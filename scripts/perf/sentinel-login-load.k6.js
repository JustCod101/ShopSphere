// k6 负载脚本：压 POST /api/user/login，验证 Sentinel 限流（QPS=20 网关规则）触发
//
// 用法：
//   brew install k6
//   k6 run scripts/perf/sentinel-login-load.k6.js
//
// 期望（Nacos 已 push shopsphere-gateway-flow-rules.json 且网关已起）：
//   1000 次请求 50 并发，远超 20 QPS → 至少 900 次返回 code=1003（请求过于频繁）
//   命中 1003 的响应须含非空 traceId 字段（32 位 hex）

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const codeCounter = new Counter('biz_code');
const traceIdPresent = new Counter('traceid_present_on_1003');

export const options = {
  scenarios: {
    burst: {
      executor: 'shared-iterations',
      vus: 50,
      iterations: 1000,
      maxDuration: '30s',
    },
  },
  thresholds: {
    // HTTP 层零 5xx（业务错误一律 HTTP 200，契约 §1.1）
    'http_req_failed': ['rate<0.01'],
    // 限流命中率 ≥ 90%（50 VUs 远超 QPS=20）
    'biz_code{code:1003}': ['count>=900'],
    // 命中 1003 时必须有 traceId
    'traceid_present_on_1003': ['count>=900'],
  },
};

export default function () {
  const r = http.post(
    `${BASE}/api/user/login`,
    JSON.stringify({ username: 'alice', password: 'WRONG_PWD_99' }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'login' },
    }
  );

  let code = -1;
  let traceId = null;
  try {
    const body = r.json();
    code = body.code;
    traceId = body.traceId;
  } catch (e) {
    // 解析失败保持 code=-1（异常计数）
  }

  codeCounter.add(1, { code: String(code) });
  if (code === 1003 && traceId && /^[0-9a-f]{32}$/.test(traceId)) {
    traceIdPresent.add(1);
  }

  check(r, {
    'http 200': (res) => res.status === 200,
    'code is 1003 or 2002/2003': (res) => [1003, 2002, 2003].includes(code),
  });
}
