"""Locust 压测 —— T4.3 推荐接口 P99 < 100ms @ 200 并发验证。

用法:
  locust -f perf/locust-recommend.py --host=http://localhost:8000 \
         --users=200 --spawn-rate=20 --run-time=2m \
         --headless --html /tmp/reco-p99.html

预置数据:
  1) shopsphere-recommendation 已起,Nacos / Redis / MySQL 在线。
  2) `python scripts/gen_reco_perf_data.py --rows 100000 --users 5000 --items 2000`
     灌 behavior_event,然后 `curl -X POST -H 'X-User-Id: 1' http://.../internal/recommend/train`
     训练完成(redis-cli GET reco:model:ready 应为 "1")。
  3) 部分 user 需有 user:behavior:{uid} ZSET(consumer 自动写入或手动 ZADD)。

验收:
  - /api/recommend/user/{userId}  P99 < 100ms
  - /api/recommend/similar/{itemId} P99 < 100ms

70/30 流量分配近似真实(用户主页推荐多于商品详情页 i2i)。
"""
from __future__ import annotations

import random

from locust import HttpUser, between, task

USER_RANGE = (1, 5000)     # 与 gen_reco_perf_data.py --users 对齐
ITEM_RANGE = (1, 2000)     # 与 --items 对齐


class RecoUser(HttpUser):
    wait_time = between(0.05, 0.2)

    @task(70)
    def user_reco(self) -> None:
        uid = random.randint(*USER_RANGE)
        headers = {
            "X-User-Id": str(uid),
            "X-Trace-Id": f"locust-{uid}-{random.randint(0, 10**9)}",
        }
        self.client.get(
            f"/api/recommend/user/{uid}?topk=10",
            headers=headers,
            name="/api/recommend/user/{userId}",
        )

    @task(30)
    def similar(self) -> None:
        item_id = random.randint(*ITEM_RANGE)
        self.client.get(
            f"/api/recommend/similar/{item_id}?topk=10",
            name="/api/recommend/similar/{itemId}",
        )
