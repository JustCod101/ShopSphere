#!/usr/bin/env bash
# T5.3 阶段 3 验证:
#   I CANCEL 计数 == cancelled
#   J DB.stock = INIT_STOCK - success + cancelled(Cancel 回补 stock)
#   K locked_stock == success - paid + 0(取消的是 paid 的,locked 已为 0;若取消 CREATED 单 locked-=q)
#   L Redis 回补:redis == DB.stock
#   M 抽 3 单:t_order.status='CANCELLED'
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
    echo "===== T5.3 BLOCKER snapshot (phase 3) ====="
    echo "时间: $(date -u +%FT%TZ)"
    echo "失败断言: $1"
    echo
    mysql_q "SELECT product_id, stock, locked_stock, version FROM shopsphere_product.t_product_stock WHERE product_id=$PRODUCT_ID;"
    echo
    mysql_q "SELECT phase, state, COUNT(*) AS cnt FROM shopsphere_product.t_stock_tcc_log GROUP BY phase, state;"
    echo
    mysql_q "SELECT status, COUNT(*) FROM shopsphere_order.t_order GROUP BY status;"
  } > "$SNAP"
  red "BLOCKER:$1 → $SNAP"
  exit 1
}

success=$(wc -l < "$R/orders-success.csv" 2>/dev/null | tr -d ' ' || echo 0)
paid=$(wc -l < "$R/orders-paid.csv" 2>/dev/null | tr -d ' ' || echo 0)
cancelled=$(wc -l < "$R/orders-cancelled.csv" 2>/dev/null | tr -d ' ' || echo 0)

cancel_ok=$(mysql_q "SELECT COUNT(*) FROM shopsphere_product.t_stock_tcc_log WHERE phase='CANCEL' AND state=1;")
db_row=$(mysql_q "SELECT stock, locked_stock FROM shopsphere_product.t_product_stock WHERE product_id=$PRODUCT_ID;")
db_stock=$(echo "$db_row" | awk '{print $1}')
db_locked=$(echo "$db_row" | awk '{print $2}')
redis_val=$(docker exec "$REDIS_CONT" redis-cli GET "stock:product:$PRODUCT_ID" | tr -d '\r')

log ""
log "=== Phase 3 verify ($(date -u +%FT%TZ)) ==="
log "success=$success paid=$paid cancelled=$cancelled"
log "TCC.CANCEL(state=1)=$cancel_ok"
log "DB.stock=$db_stock DB.locked_stock=$db_locked"
log "Redis.stock=$redis_val"
log ""

# I. CANCEL 计数
if [ "$cancel_ok" -ne "$cancelled" ]; then
  dump_blocker "I CANCEL=$cancel_ok != cancelled=$cancelled"
fi
log "I CANCEL 计数:OK ($cancel_ok == cancelled $cancelled)"

# J. DB.stock 回补:INIT_STOCK - success + cancelled
expected_stock=$((INIT_STOCK - success + cancelled))
if [ "$db_stock" -ne "$expected_stock" ]; then
  dump_blocker "J DB.stock=$db_stock,期望 INIT_STOCK-success+cancelled=$expected_stock"
fi
log "J DB.stock 回补:OK ($db_stock == $expected_stock)"

# K. locked_stock = success - paid(未支付的成功订单仍占 locked,取消的 paid 单 locked 已为 0)
expected_locked=$((success - paid))
if [ "$db_locked" -ne "$expected_locked" ]; then
  dump_blocker "K locked_stock=$db_locked,期望 success-paid=$expected_locked"
fi
log "K locked_stock:OK ($db_locked == $expected_locked)"

# L. Redis == DB.stock
if [ "$redis_val" != "$db_stock" ]; then
  dump_blocker "L Redis=$redis_val != DB.stock=$db_stock"
fi
log "L Redis/DB 一致:OK ($redis_val == $db_stock)"

# M. 抽 3 个取消单确认 status='CANCELLED'
if [ "$cancelled" -ge 3 ]; then
  sample_ids=$(head -n 3 "$R/orders-cancelled.csv" | awk -F, '{print $1}' | tr '\n' ',' | sed 's/,$//')
  s=$(mysql_q "SELECT GROUP_CONCAT(status) FROM shopsphere_order.t_order WHERE id IN ($sample_ids);")
  log "M 抽样取消单 status=$s"
  case "$s" in
    CANCELLED,CANCELLED,CANCELLED) log "M status 全 CANCELLED:OK" ;;
    *) dump_blocker "M 抽样取消单状态非 CANCELLED:$s" ;;
  esac
fi

green "=== Phase 3 PASS ==="
