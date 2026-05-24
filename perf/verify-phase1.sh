#!/usr/bin/env bash
# T5.3 阶段 1 验证:
#   A 不超卖     成功订单数 <= INIT_STOCK
#   B 账平       stock + locked_stock == INIT_STOCK
#   C R/DB 一致  redis.stock == DB.stock - DB.locked_stock(契约 §4.3:Redis 镜像可售)
#   D TRY 计数   t_stock_tcc_log(TRY,state=1) == success
#   E 失败合规   errors.csv 行数 == 0;oversold == USERS - success
# 任一失败 → exit 1 + 写 blocker-snapshot.txt(BLOCKER 流程)。
set -uo pipefail

PRODUCT_ID="${PRODUCT_ID:-2001}"
INIT_STOCK="${INIT_STOCK:-500}"
USERS="${USERS:-1000}"
MYSQL_CONT="${MYSQL_CONT:-shopsphere-mysql}"
REDIS_CONT="${REDIS_CONT:-shopsphere-redis}"
MYSQL_PASS="${MYSQL_ROOT_PASSWORD:-root123}"

PERF_DIR="$(cd "$(dirname "$0")" && pwd)"
R="$PERF_DIR/results"
EVID="$R/evidence.txt"
SNAP="$R/blocker-snapshot.txt"
mkdir -p "$R"
: > "$EVID"

# ------- 工具函数 -------
red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
log()   { echo "$@" | tee -a "$EVID"; }

mysql_q() {
  docker exec -i "$MYSQL_CONT" mysql -uroot -p"$MYSQL_PASS" -N -B -e "$1" 2>/dev/null
}

dump_blocker() {
  {
    echo "===== T5.3 BLOCKER snapshot ====="
    echo "时间: $(date -u +%FT%TZ)"
    echo "失败断言: $1"
    echo
    echo "--- t_product_stock ---"
    mysql_q "SELECT product_id, stock, locked_stock, version FROM shopsphere_product.t_product_stock WHERE product_id=$PRODUCT_ID;"
    echo
    echo "--- t_stock_tcc_log by (phase,state) ---"
    mysql_q "SELECT phase, state, COUNT(*) AS cnt FROM shopsphere_product.t_stock_tcc_log GROUP BY phase, state;"
    echo
    echo "--- redis stock:product:$PRODUCT_ID ---"
    docker exec "$REDIS_CONT" redis-cli GET "stock:product:$PRODUCT_ID"
    echo
    echo "--- orders by status ---"
    mysql_q "SELECT status, COUNT(*) FROM shopsphere_order.t_order GROUP BY status;"
    echo
    echo "--- shopsphere-order log tail ---"
    docker logs --tail 100 shopsphere-order 2>&1 || true
    echo
    echo "--- shopsphere-product log tail ---"
    docker logs --tail 100 shopsphere-product 2>&1 || true
  } > "$SNAP"
  red "BLOCKER:$1"
  red "已 dump 到 $SNAP"
  exit 1
}

# ------- 实测 -------
success=$(wc -l < "$R/orders-success.csv" 2>/dev/null | tr -d ' ' || echo 0)
oversold=$(wc -l < "$R/oversold-rejected.csv" 2>/dev/null | tr -d ' ' || echo 0)
errors=$(wc -l < "$R/errors.csv" 2>/dev/null | tr -d ' ' || echo 0)

db_row=$(mysql_q "SELECT stock, locked_stock FROM shopsphere_product.t_product_stock WHERE product_id=$PRODUCT_ID;")
db_stock=$(echo "$db_row" | awk '{print $1}')
db_locked=$(echo "$db_row" | awk '{print $2}')
redis_val=$(docker exec "$REDIS_CONT" redis-cli GET "stock:product:$PRODUCT_ID" | tr -d '\r')
try_ok=$(mysql_q "SELECT COUNT(*) FROM shopsphere_product.t_stock_tcc_log WHERE phase='TRY' AND state=1;")
total_orders=$(mysql_q "SELECT COUNT(*) FROM shopsphere_order.t_order;")

log "=== T5.3 Phase 1 verify ($(date -u +%FT%TZ)) ==="
log "USERS=$USERS INIT_STOCK=$INIT_STOCK PRODUCT_ID=$PRODUCT_ID"
log "success=$success oversold=$oversold errors=$errors"
log "DB.stock=$db_stock DB.locked_stock=$db_locked"
log "Redis.stock=$redis_val"
log "TCC.TRY(state=1)=$try_ok"
log "t_order rows=$total_orders"
log ""

# A. 不超卖
if [ "$success" -gt "$INIT_STOCK" ]; then
  dump_blocker "A 超卖:成功 $success > 库存 $INIT_STOCK"
fi
log "A 不超卖:OK ($success <= $INIT_STOCK)"

# B. 账平
sum=$((db_stock + db_locked))
if [ "$sum" -ne "$INIT_STOCK" ]; then
  dump_blocker "B 账不平:stock+locked = $sum != $INIT_STOCK"
fi
log "B 账平:OK (stock+locked=$sum == $INIT_STOCK)"

# C. Redis/DB 一致(契约:Redis 镜像 = DB.stock)
expected_redis="$db_stock"
if [ "$redis_val" != "$expected_redis" ]; then
  dump_blocker "C Redis/DB 不一致:redis=$redis_val DB.stock=$expected_redis"
fi
log "C Redis/DB 一致:OK (redis=$redis_val == DB.stock=$expected_redis)"

# D. TCC TRY 计数 == success(每成功订单 = 一行 TRY,state=1)
if [ "$try_ok" -ne "$success" ]; then
  dump_blocker "D TRY 计数与成功订单不等:TRY=$try_ok success=$success"
fi
log "D TRY 计数:OK ($try_ok == success $success)"

# E. errors 必须 = 0,oversold 数 == USERS - success
if [ "$errors" -ne 0 ]; then
  red "E 失败码合规:有 $errors 条 errors.csv(非 3002 / 5xx 等)"
  red "errors.csv 前 5 行:"
  head -5 "$R/errors.csv" || true
  dump_blocker "E errors=$errors,期望 0;扣除合理 3002 后有异常码"
fi
expected_oversold=$((USERS - success))
if [ "$oversold" -ne "$expected_oversold" ]; then
  red "E oversold=$oversold,期望 USERS-success=$expected_oversold"
  dump_blocker "E oversold 计数偏差:实测 $oversold,期望 $expected_oversold"
fi
log "E 失败合规:OK (errors=0; oversold=$oversold == USERS-success=$expected_oversold)"

green "=== Phase 1 PASS ==="
