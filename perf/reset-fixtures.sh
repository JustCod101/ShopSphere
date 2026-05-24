#!/usr/bin/env bash
# T5.3 压测前:truncate orders/tcc_log/local_msg + 商品 2001 stock=500 locked=0 + Redis SET。
# 不动 t_user(prepare-users 已写入 users.csv,持久复用)。
# 用法:bash perf/reset-fixtures.sh
#       MYSQL_ROOT_PASSWORD=root123 PRODUCT_ID=2001 INIT_STOCK=500 bash perf/reset-fixtures.sh
set -euo pipefail

PRODUCT_ID="${PRODUCT_ID:-2001}"
INIT_STOCK="${INIT_STOCK:-500}"
MYSQL_CONT="${MYSQL_CONT:-shopsphere-mysql}"
REDIS_CONT="${REDIS_CONT:-shopsphere-redis}"
MYSQL_PASS="${MYSQL_ROOT_PASSWORD:-root123}"

# 也清残留输出
PERF_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS="$PERF_DIR/results"
for f in orders-success.csv oversold-rejected.csv errors.csv orders-paid.csv pay-errors.csv orders-cancelled.csv cancel-errors.csv aggregate.csv order-create.jtl jmeter.log evidence.txt blocker-snapshot.txt; do
  rm -f "$RESULTS/$f"
done
rm -rf "$RESULTS/html-report"

echo "[reset] MySQL truncate + stock reset(product=$PRODUCT_ID stock=$INIT_STOCK)"
docker exec -i "$MYSQL_CONT" mysql -uroot -p"$MYSQL_PASS" -N -e "
TRUNCATE shopsphere_order.t_order;
TRUNCATE shopsphere_order.t_order_item;
TRUNCATE shopsphere_order.t_order_request;
TRUNCATE shopsphere_order.t_local_message;
TRUNCATE shopsphere_product.t_stock_tcc_log;
UPDATE shopsphere_product.t_product_stock SET stock=$INIT_STOCK, locked_stock=0, version=0 WHERE product_id=$PRODUCT_ID;
SELECT stock, locked_stock FROM shopsphere_product.t_product_stock WHERE product_id=$PRODUCT_ID;
" 2>/dev/null

echo "[reset] Redis stock:product:$PRODUCT_ID = $INIT_STOCK,清取消标记"
docker exec "$REDIS_CONT" redis-cli SET "stock:product:$PRODUCT_ID" "$INIT_STOCK" >/dev/null
docker exec "$REDIS_CONT" redis-cli GET "stock:product:$PRODUCT_ID"

echo "[reset] OK"
