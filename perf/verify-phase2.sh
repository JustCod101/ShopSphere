#!/usr/bin/env bash
# T5.3 阶段 2 验证:
#   F CONFIRM 计数 == paid
#   G locked_stock == 0(全 Confirm 完;尚未取消)
#   H DB.stock 不变(契约:Confirm 只 locked-=q,stock 维持 Try 后值)
set -uo pipefail

PRODUCT_ID="${PRODUCT_ID:-2001}"
INIT_STOCK="${INIT_STOCK:-500}"
MYSQL_CONT="${MYSQL_CONT:-shopsphere-mysql}"
REDIS_CONT="${REDIS_CONT:-shopsphere-redis}"
MYSQL_PASS="${MYSQL_ROOT_PASSWORD:-root123}"

PERF_DIR="$(cd "$(dirname "$0")" && pwd)"
R="$PERF_DIR/results"
EVID="$R/evidence.txt"
SNAP="$R/blocker-snapshot.txt"

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
log()   { echo "$@" | tee -a "$EVID"; }
mysql_q() { docker exec -i "$MYSQL_CONT" mysql -uroot -p"$MYSQL_PASS" -N -B -e "$1" 2>/dev/null; }

dump_blocker() {
  {
    echo "===== T5.3 BLOCKER snapshot (phase 2) ====="
    echo "时间: $(date -u +%FT%TZ)"
    echo "失败断言: $1"
    echo
    echo "--- t_product_stock ---"
    mysql_q "SELECT product_id, stock, locked_stock, version FROM shopsphere_product.t_product_stock WHERE product_id=$PRODUCT_ID;"
    echo
    echo "--- t_stock_tcc_log by (phase,state) ---"
    mysql_q "SELECT phase, state, COUNT(*) AS cnt FROM shopsphere_product.t_stock_tcc_log GROUP BY phase, state;"
    echo
    echo "--- orders by status ---"
    mysql_q "SELECT status, COUNT(*) FROM shopsphere_order.t_order GROUP BY status;"
  } > "$SNAP"
  red "BLOCKER:$1 → $SNAP"
  exit 1
}

paid=$(wc -l < "$R/orders-paid.csv" 2>/dev/null | tr -d ' ' || echo 0)
success=$(wc -l < "$R/orders-success.csv" 2>/dev/null | tr -d ' ' || echo 0)
confirm_ok=$(mysql_q "SELECT COUNT(*) FROM shopsphere_product.t_stock_tcc_log WHERE phase='CONFIRM' AND state=1;")
db_row=$(mysql_q "SELECT stock, locked_stock FROM shopsphere_product.t_product_stock WHERE product_id=$PRODUCT_ID;")
db_stock=$(echo "$db_row" | awk '{print $1}')
db_locked=$(echo "$db_row" | awk '{print $2}')
redis_val=$(docker exec "$REDIS_CONT" redis-cli GET "stock:product:$PRODUCT_ID" | tr -d '\r')

log ""
log "=== Phase 2 verify ($(date -u +%FT%TZ)) ==="
log "paid=$paid success=$success"
log "TCC.CONFIRM(state=1)=$confirm_ok"
log "DB.stock=$db_stock DB.locked_stock=$db_locked"
log "Redis.stock=$redis_val"
log ""

# F. CONFIRM 计数 == paid
if [ "$confirm_ok" -ne "$paid" ]; then
  dump_blocker "F CONFIRM 计数 $confirm_ok != paid $paid"
fi
log "F CONFIRM 计数:OK ($confirm_ok == paid $paid)"

# G. locked_stock == success - paid(尚未支付的成功订单仍占 locked)
expected_locked=$((success - paid))
if [ "$db_locked" -ne "$expected_locked" ]; then
  dump_blocker "G locked_stock=$db_locked,期望 success-paid=$expected_locked"
fi
log "G locked_stock:OK ($db_locked == success-paid=$expected_locked)"

# H. DB.stock 不变(Confirm 只 locked-=q,stock 维持 Try 后值)
expected_stock=$((INIT_STOCK - success))
if [ "$db_stock" -ne "$expected_stock" ]; then
  dump_blocker "H DB.stock=$db_stock,期望 INIT_STOCK-success=$expected_stock"
fi
log "H DB.stock:OK ($db_stock == INIT_STOCK-success=$expected_stock)"

# Redis 同步(Confirm 不动 Redis,Redis 维持 Try 后)
if [ "$redis_val" != "$db_stock" ]; then
  dump_blocker "Redis 与 DB.stock 不一致:redis=$redis_val DB.stock=$db_stock"
fi
log "Redis/DB:OK ($redis_val == $db_stock)"

green "=== Phase 2 PASS ==="
