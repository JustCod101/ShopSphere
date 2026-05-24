#!/bin/sh
# 把 order.payment.queueTtlMs 改成 30000(毫秒) 推到 Nacos,然后 compose down -v + up,
# 让 RabbitMQ 队列 q.order.timeout.wait 以新 TTL 重声明。case h 跑前必须执行一次。
#
# 用法:
#   bash scripts/e2e-set-timeout.sh
#   mvn -f shopsphere-e2e-test/pom.xml -Pe2e,e2e-slow verify
set -e

NACOS_URL="${NACOS_URL:-http://localhost:8848}"
GROUP="${NACOS_GROUP:-DEFAULT_GROUP}"
DATA_ID="shopsphere-order.yaml"
TENANT="${NACOS_DEV_NAMESPACE:-dev}"
TIMEOUT_MS="${E2E_QUEUE_TTL_MS:-30000}"

echo "== E2E timeout setup: queueTtlMs=$TIMEOUT_MS ms =="

# 1) 拉当前配置
echo "[step 1] fetch current $DATA_ID"
current=$(curl -fsS \
  "$NACOS_URL/nacos/v1/cs/configs?dataId=$DATA_ID&group=$GROUP&tenant=$TENANT" || true)

if [ -z "$current" ]; then
  echo "  WARN: 当前 Nacos 没有 $DATA_ID,本脚本期望 nacos-config-init 已推过基线。退出。"
  exit 1
fi

# 2) 移除已有 queueTtlMs 行,追加新值。简单文本处理 — yaml 末尾追加在 order.payment 块下
patched=$(printf "%s\n" "$current" | awk -v ttl="$TIMEOUT_MS" '
  BEGIN { injected = 0 }
  /^\s*queueTtlMs:/ { next }
  /^\s*timeoutMinutes:/ {
    print
    # 同缩进追加 queueTtlMs
    match($0, /^ */)
    indent = substr($0, 1, RLENGTH)
    print indent "queueTtlMs: " ttl
    injected = 1
    next
  }
  { print }
  END {
    if (!injected) {
      print ""
      print "order:"
      print "  payment:"
      print "    queueTtlMs: " ttl
    }
  }
')

# 3) 推回
echo "[step 2] push patched $DATA_ID"
http_code=$(curl -fsS -o /tmp/e2e_nacos_resp -w "%{http_code}" -X POST \
  "$NACOS_URL/nacos/v1/cs/configs" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "dataId=$DATA_ID" \
  --data-urlencode "group=$GROUP" \
  --data-urlencode "tenant=$TENANT" \
  --data-urlencode "type=yaml" \
  --data-urlencode "content=$patched")
body=$(cat /tmp/e2e_nacos_resp 2>/dev/null || echo "")
if [ "$http_code" != "200" ] || [ "$body" != "true" ]; then
  echo "  FAIL http=$http_code body=$body"
  exit 1
fi
echo "  OK"

# 4) compose down -v 让队列以新 TTL 重声明
echo "[step 3] docker compose down -v && up -d --build (让 q.order.timeout.wait 以新 TTL 重声明)"
docker compose down -v
docker compose up -d --build

echo ""
echo "== Done。等栈 healthy 后运行 mvn -f shopsphere-e2e-test/pom.xml -Pe2e,e2e-slow verify =="
