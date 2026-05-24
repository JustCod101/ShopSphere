#!/usr/bin/env bash
# T5.3 阶段 3:从 orders-paid.csv 随机取 N 单 /cancel。
# 用法:bash perf/cancel-sample.sh
#       CANCEL_N=100 bash perf/cancel-sample.sh
set -euo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
CANCEL_N="${CANCEL_N:-100}"
PERF_DIR="$(cd "$(dirname "$0")" && pwd)"
R="$PERF_DIR/results"

IN="$R/orders-paid.csv"
OUT="$R/orders-cancelled.csv"
ERR="$R/cancel-errors.csv"

[ -f "$IN" ] || { echo "缺少 $IN" >&2; exit 1; }
: > "$OUT"
: > "$ERR"

total=$(wc -l < "$IN" | tr -d ' ')
if [ "$total" -lt "$CANCEL_N" ]; then
  echo "paid=$total < CANCEL_N=$CANCEL_N,改用全量"
  CANCEL_N="$total"
fi
if [ "$CANCEL_N" -le 0 ]; then
  echo "cancelled=0 err=0"
  exit 0
fi

# 随机取 N 行(macOS/Linux:用 awk + shuf 不便,直接用 head + sort -R)
if command -v shuf >/dev/null 2>&1; then
  SAMPLE=$(shuf -n "$CANCEL_N" "$IN")
else
  SAMPLE=$(sort -R "$IN" | sed -n "1,${CANCEL_N}p")
fi

cancel_one() {
  local line="$1"
  local oid uid token resp code
  oid="$(echo "$line" | awk -F, '{print $1}')"
  uid="$(echo "$line" | awk -F, '{print $2}')"
  token="$(echo "$line" | awk -F, '{print $3}')"

  resp=$(curl -fsS -X POST -H "Authorization: Bearer $token" "$GATEWAY/api/order/$oid/cancel" 2>/dev/null || echo '{"code":-1}')
  code=$(echo "$resp" | jq -r '.code // -1' 2>/dev/null || echo -1)

  if [ "$code" = "0" ]; then
    printf '%s,%s,%s\n' "$oid" "$uid" "$token" >> "$OUT"
  else
    printf '%s,%s,%s,%s\n' "$oid" "$uid" "$code" "$(echo "$resp" | tr -d '\n' | cut -c1-200)" >> "$ERR"
  fi
}
export -f cancel_one
export GATEWAY OUT ERR

run_parallel() {
  local concurrency="$1"
  local n=0
  while IFS= read -r line; do
    cancel_one "$line" &
    n=$((n + 1))
    if [ $((n % concurrency)) -eq 0 ]; then
      wait || true
    fi
  done
  wait || true
}

echo "/cancel × $CANCEL_N(并发 30)"
run_parallel 30 <<< "$SAMPLE"

ok=$(wc -l < "$OUT" | tr -d ' ')
fail=$(wc -l < "$ERR" | tr -d ' ')
echo "cancelled=$ok err=$fail"
