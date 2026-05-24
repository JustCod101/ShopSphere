#!/usr/bin/env bash
# T5.3 阶段 2:批量 /pay 所有成功订单。
# 输入:perf/results/orders-success.csv(orderId,userId,token)
# 输出:perf/results/orders-paid.csv / pay-errors.csv
set -euo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
PERF_DIR="$(cd "$(dirname "$0")" && pwd)"
R="$PERF_DIR/results"

IN="$R/orders-success.csv"
OUT="$R/orders-paid.csv"
ERR="$R/pay-errors.csv"

[ -f "$IN" ] || { echo "缺少 $IN(请先跑 jmeter)" >&2; exit 1; }
: > "$OUT"
: > "$ERR"

pay_one() {
  local line="$1"
  local oid uid token resp code
  oid="$(echo "$line" | awk -F, '{print $1}')"
  uid="$(echo "$line" | awk -F, '{print $2}')"
  token="$(echo "$line" | awk -F, '{print $3}')"

  resp=$(curl -fsS -X POST -H "Authorization: Bearer $token" "$GATEWAY/api/order/$oid/pay" 2>/dev/null || echo '{"code":-1}')
  code=$(echo "$resp" | jq -r '.code // -1' 2>/dev/null || echo -1)

  if [ "$code" = "0" ]; then
    printf '%s,%s,%s\n' "$oid" "$uid" "$token" >> "$OUT"
  else
    printf '%s,%s,%s,%s\n' "$oid" "$uid" "$code" "$(echo "$resp" | tr -d '\n' | cut -c1-200)" >> "$ERR"
  fi
}
export -f pay_one
export GATEWAY OUT ERR

total=$(wc -l < "$IN" | tr -d ' ')
echo "/pay × $total(并发 50)"
cat "$IN" | xargs -I{} -P 50 bash -c 'pay_one "$@"' _ {} || true

paid=$(wc -l < "$OUT" | tr -d ' ')
fail=$(wc -l < "$ERR" | tr -d ' ')
echo "paid=$paid err=$fail"
