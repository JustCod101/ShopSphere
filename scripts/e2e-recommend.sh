#!/usr/bin/env bash
# ============================================================================
# T4.4 E2E:推荐服务端到端集成验证(经 Gateway + 直连 Python /internal)
#
# 链路:注册 → 登录 → 行为埋点 × 3 → 等 MQ 落库 → (可选 mysql 验证)
#       → 触发训练(/internal 直连 Python,绕 Gateway)→ 轮询 /health
#       → 个性化推荐(带 Bearer,经 Gateway)→ 未登录拦截 → similar 白名单放行
#       → /internal 经 Gateway 应被 403/1004
#
# 依赖:bash + curl + jq;可选 mysql CLI(缺则 SKIP step 5)
# 用法:
#   bash scripts/e2e-recommend.sh
# 可覆盖的环境变量(默认见下方):
#   GW                  Gateway 入口 URL(默认 http://localhost:8080)
#   RECO                Python 推荐服务直连 URL(默认 http://localhost:8000)
#   MYSQL_HOST/PORT/USER/PASSWORD/RECO_DB
#   MAX_TRAIN_WAIT_SEC  训练完成等待上限(默认 60s)
# ============================================================================
set -euo pipefail

GW="${GW:-http://localhost:8080}"
RECO="${RECO:-http://localhost:8000}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
MYSQL_RECO_DB="${MYSQL_RECO_DB:-shopsphere_reco}"
MAX_TRAIN_WAIT_SEC="${MAX_TRAIN_WAIT_SEC:-60}"

USERNAME="e2e_$(date +%s)"
PASS="Pass1234"
ITEMS=(101 102 103)
TOPK=5

red()    { printf '\033[31m%s\033[0m\n' "$*"; }
green()  { printf '\033[32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }
step()   { echo; echo "[step $1] $2"; }

# 前置依赖
for cmd in curl jq; do
  command -v "$cmd" >/dev/null 2>&1 || { red "$cmd 必装"; exit 1; }
done

echo "================================================================"
echo "T4.4 推荐服务端到端验证"
echo "  GW=$GW  RECO=$RECO  user=$USERNAME"
echo "================================================================"

# ---- 1. 注册 ----
step 1 "POST $GW/api/user/register"
REG=$(curl -sS -X POST "$GW/api/user/register" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASS\"}")
echo "$REG" | jq .
CODE=$(echo "$REG" | jq -r '.code')
[ "$CODE" = "0" ] || { red "register failed code=$CODE"; exit 1; }
USER_ID=$(echo "$REG" | jq -r '.data.id')
green "userId=$USER_ID"

# ---- 2. 登录拿 token ----
step 2 "POST $GW/api/user/login"
LOGIN=$(curl -sS -X POST "$GW/api/user/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASS\"}")
TOKEN=$(echo "$LOGIN" | jq -r '.data.token')
if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
  red "login failed"; echo "$LOGIN" | jq .; exit 1
fi
green "token len=${#TOKEN}"

# ---- 3. 行为埋点 3 次(view 不同 itemId) ----
step 3 "POST $GW/api/user/behavior × 3(view ${ITEMS[*]})"
for ITEM in "${ITEMS[@]}"; do
  BR=$(curl -sS -X POST "$GW/api/user/behavior" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -d "{\"itemId\":$ITEM,\"actionType\":\"view\"}")
  BCODE=$(echo "$BR" | jq -r '.code')
  [ "$BCODE" = "0" ] || { red "behavior view itemId=$ITEM failed code=$BCODE"; echo "$BR" | jq .; exit 1; }
  echo "  view itemId=$ITEM → code=0"
done

# ---- 4. 等 MQ 消费者落库 ----
step 4 "sleep 5(等 BehaviorConsumer 落 shopsphere_reco.behavior_event)"
sleep 5

# ---- 5. (可选)验证 behavior_event 行数 ----
step 5 "验证 behavior_event 入库 3 条"
if command -v mysql >/dev/null 2>&1; then
  if [ -n "$MYSQL_PASSWORD" ]; then
    PW_ARG="-p$MYSQL_PASSWORD"
  else
    PW_ARG=""
  fi
  COUNT=$(mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" $PW_ARG \
    -D "$MYSQL_RECO_DB" -BN -e \
    "SELECT COUNT(*) FROM behavior_event WHERE user_id=$USER_ID AND action_type='view'" 2>/dev/null || echo "QUERY_FAIL")
  if [ "$COUNT" = "3" ]; then
    green "behavior_event user_id=$USER_ID action=view 共 3 条 OK"
  elif [ "$COUNT" = "QUERY_FAIL" ]; then
    yellow "[SKIP] mysql 连接失败(可能凭证不对),依赖后续 recall 隐式验证"
  else
    red "expected 3 rows, got $COUNT"; exit 1
  fi
else
  yellow "[SKIP] mysql CLI not found;依赖后续 recall fallback=false 隐式验证"
fi

# ---- 6. 触发训练(直连 Python /internal,绕 Gateway) ----
step 6 "POST $RECO/internal/recommend/train(直连 Python)"
TR=$(curl -sS -X POST "$RECO/internal/recommend/train")
echo "$TR" | jq .
TRIG=$(echo "$TR" | jq -r '.data.triggered')
if [ "$TRIG" != "true" ]; then
  REASON=$(echo "$TR" | jq -r '.data.reason // "?"')
  red "train trigger failed (triggered=$TRIG reason=$REASON);若 reason=already_running,等当前训练完成后重跑"
  exit 1
fi
RUN_ID=$(echo "$TR" | jq -r '.data.runId')
green "training runId=$RUN_ID"

# ---- 7. 轮询 /api/recommend/health 等 model_ready=true ----
step 7 "轮询 $GW/api/recommend/health 直到 data.model_ready=true(最多 ${MAX_TRAIN_WAIT_SEC}s)"
ELAPSED=0
READY="false"
while [ "$ELAPSED" -lt "$MAX_TRAIN_WAIT_SEC" ]; do
  H=$(curl -sS "$GW/api/recommend/health" || echo '{}')
  READY=$(echo "$H" | jq -r '.data.model_ready // false')
  if [ "$READY" = "true" ]; then
    green "model_ready=true after ${ELAPSED}s"
    break
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
if [ "$READY" != "true" ]; then
  red "训练 ${MAX_TRAIN_WAIT_SEC}s 未完成。请优先排查:"
  red "  1) Python 推荐服务是否在 Nacos 注册成功(控制台看 shopsphere-recommendation 实例)"
  red "  2) Nacos 注册 IP 是否容器外可达(env NACOS_REGISTER_IP)"
  red "  3) Python /internal 端口是否对外开放(RECO=$RECO 是否能直连)"
  red "  4) shopsphere_reco.t_train_log 最近一行 status / error"
  exit 1
fi

# ---- 8. 经 Gateway 调个性化推荐 ----
step 8 "GET $GW/api/recommend/user/$USER_ID?topk=$TOPK(Bearer)"
RR=$(curl -sS "$GW/api/recommend/user/$USER_ID?topk=$TOPK" \
  -H "Authorization: Bearer $TOKEN")
echo "$RR" | jq .
RCODE=$(echo "$RR" | jq -r '.code')
RFB=$(echo "$RR" | jq -r '.data.fallback')
RN=$(echo "$RR" | jq -r '.data.items | length')
if [ "$RCODE" = "0" ] && [ "$RFB" = "false" ] && [ "$RN" -gt 0 ]; then
  green "推荐 code=0 fallback=false items=$RN OK"
else
  red "expected code=0 fallback=false items>0; got code=$RCODE fallback=$RFB items=$RN"
  red "可能原因:行为未消费、训练未产出 sim、user:behavior:$USER_ID 未写 Redis"
  exit 1
fi

# ---- 9. 未登录调 user 推荐 → 1001 ----
step 9 "无 Bearer GET $GW/api/recommend/user/$USER_ID → 应返 code=1001"
NA=$(curl -sS "$GW/api/recommend/user/$USER_ID?topk=$TOPK")
NACODE=$(echo "$NA" | jq -r '.code')
if [ "$NACODE" = "1001" ]; then
  green "未登录拦截 code=1001 OK"
else
  red "expected 1001, got $NACODE"; echo "$NA" | jq .; exit 1
fi

# ---- 10. 无 token 调 similar → 白名单放行 ----
step 10 "无 Bearer GET $GW/api/recommend/similar/${ITEMS[0]} → 白名单应放行"
SIM=$(curl -sS "$GW/api/recommend/similar/${ITEMS[0]}?topk=$TOPK")
SCODE=$(echo "$SIM" | jq -r '.code')
if [ "$SCODE" = "0" ]; then
  green "similar 白名单放行 code=0 OK"
else
  red "expected code=0, got $SCODE"; echo "$SIM" | jq .; exit 1
fi

# ---- 11. /internal 经 Gateway 应被拒 403/1004 ----
step 11 "POST $GW/internal/recommend/train → 应返 HTTP 403 + code=1004"
INT_BODY=$(curl -sS -o /tmp/e2e_internal_body.json -w '%{http_code}' \
  -X POST "$GW/internal/recommend/train" || echo "000")
HTTP_CODE="$INT_BODY"
BODY_CODE=$(jq -r '.code' < /tmp/e2e_internal_body.json 2>/dev/null || echo "?")
if [ "$HTTP_CODE" = "403" ] && [ "$BODY_CODE" = "1004" ]; then
  green "/internal 经 Gateway 拒绝 HTTP=403 code=1004 OK"
else
  red "expected http=403 body_code=1004, got http=$HTTP_CODE body_code=$BODY_CODE"
  cat /tmp/e2e_internal_body.json 2>/dev/null || true
  exit 1
fi

echo
green "================== E2E PASS =================="
green "userId=$USER_ID  trainRunId=$RUN_ID  items=$RN"
