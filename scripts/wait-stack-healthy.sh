#!/bin/sh
# 轮询 docker compose ps,等到所有 service 都 healthy 或退出。
# 默认 5 分钟超时;首次构建可改 E2E_WAIT_SEC=900。
#
# 用法:bash scripts/wait-stack-healthy.sh
set -e

TIMEOUT="${E2E_WAIT_SEC:-300}"
INTERVAL="${E2E_WAIT_INTERVAL:-5}"
elapsed=0

echo "== 等 docker compose 全 healthy(最长 ${TIMEOUT}s) =="
while [ "$elapsed" -lt "$TIMEOUT" ]; do
  # 含 (healthy) 的服务列出
  total=$(docker compose ps --status running --format '{{.Service}}\t{{.Health}}' 2>/dev/null | wc -l | tr -d ' ')
  healthy=$(docker compose ps --status running --format '{{.Service}}\t{{.Health}}' 2>/dev/null | grep -c "healthy" || true)
  # 一些容器没声明 healthcheck(如 init container);它们的 Health=""
  no_check=$(docker compose ps --status running --format '{{.Service}}\t{{.Health}}' 2>/dev/null | awk -F'\t' '$2 == "" {c++} END {print c+0}')

  if [ "$total" -gt 0 ] && [ "$((healthy + no_check))" -ge "$total" ]; then
    echo "  OK: total=$total healthy=$healthy no_check=$no_check (elapsed=${elapsed}s)"
    exit 0
  fi
  printf "  ... elapsed=%ss total=%s healthy=%s\n" "$elapsed" "$total" "$healthy"
  sleep "$INTERVAL"
  elapsed=$((elapsed + INTERVAL))
done

echo "  TIMEOUT: 栈未在 ${TIMEOUT}s 内全 healthy。打印当前状态:"
docker compose ps
exit 1
