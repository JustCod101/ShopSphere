// k6 负载脚本：压 POST /api/user/behavior @ 500 QPS / 60s
//
// 用法：
//   brew install k6   # 一次性
//   TOKEN=$(curl -sX POST http://localhost:8080/api/user/login \
//           -H 'Content-Type: application/json' \
//           -d '{"username":"alice","password":"Aa12345678"}' | jq -r .data.token)
//   TOKEN=$TOKEN k6 run scripts/perf/behavior-load.k6.js
//
// 断言：http_req_duration{status:200} P99 < 50ms

import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN;
if (!TOKEN) {
  throw new Error('环境变量 TOKEN 未设置（先 curl /api/user/login 拿一个）');
}

const ITEMS = [1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008];
const ACTIONS = ['view', 'cart', 'order'];

export const options = {
  scenarios: {
    behavior_500qps: {
      executor: 'constant-arrival-rate',
      rate: 500,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 100,
      maxVUs: 400,
    },
  },
  // 仅对成功响应计 P99（5xx/超时另算到 http_req_failed）
  thresholds: {
    'http_req_duration{status:200}': ['p(99)<50'],
    'http_req_failed': ['rate<0.01'],   // 错误率 < 1%
  },
};

export default function () {
  const itemId = ITEMS[Math.floor(Math.random() * ITEMS.length)];
  const actionType = ACTIONS[Math.floor(Math.random() * ACTIONS.length)];
  const r = http.post(
    `${BASE}/api/user/behavior`,
    JSON.stringify({ itemId, actionType }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${TOKEN}`,
      },
      tags: { name: 'behavior' },
    }
  );
  check(r, {
    'status 200': (res) => res.status === 200,
    'code = 0': (res) => res.json('code') === 0,
  });
}
