// k6 负载脚本：压 GET /api/product/{id}，验证 T2.2 Redis 详情缓存效果
//
// 用法：
//   brew install k6
//   k6 run scripts/perf/product-detail-cache-load.k6.js
//   k6 run -e BASE=http://localhost:8080 -e PID=2001 scripts/perf/product-detail-cache-load.k6.js
//
// 期望（product 服务已起、Redis 可用、商品 2001 存在）：
//   预热后稳定命中缓存 → p99 < 5ms、错误率 < 1%、业务 code 全 0
//   对照组：停 Redis 或 flushdb 后重跑，p99 退化到 DB 查询量级（数十 ms）

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const PID = __ENV.PID || '2001';

const bizOk = new Counter('biz_code_ok');     // code===0 计数
const bizErr = new Counter('biz_code_err');   // code!==0 计数

export const options = {
  scenarios: {
    // 1 VU 预热 3s：把 product:detail:{id} + stock:product:{id} 填进缓存
    warmup: {
      executor: 'constant-vus',
      vus: 1,
      duration: '3s',
      exec: 'warmup',
    },
    // 主压测：恒定到达率 500 req/s 持续 30s（预热结束后启动）
    main: {
      executor: 'constant-arrival-rate',
      rate: 500,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 50,
      maxVUs: 100,
      startTime: '4s',
    },
  },
  thresholds: {
    // 缓存命中路径 p99 < 5ms（仅统计主压测场景）
    'http_req_duration{scenario:main}': ['p(99)<5'],
    // HTTP 层错误率 < 1%（业务错误一律 200，契约 §1.1）
    'http_req_failed': ['rate<0.01'],
    // 业务码必须全部为 0
    'biz_code_err': ['count==0'],
  },
};

export function warmup() {
  http.get(`${BASE}/api/product/${PID}`);
}

export default function () {
  const r = http.get(`${BASE}/api/product/${PID}`, { tags: { name: 'product-detail' } });

  let code = -1;
  try {
    code = r.json('code');
  } catch (e) {
    // 解析失败保持 -1
  }
  if (code === 0) {
    bizOk.add(1);
  } else {
    bizErr.add(1);
  }

  check(r, {
    'http 200': (res) => res.status === 200,
    'code=0': () => code === 0,
  });
}
