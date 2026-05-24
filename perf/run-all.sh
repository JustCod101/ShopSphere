#!/usr/bin/env bash
# T5.3 一键编排:prepare → reset → jmeter → verify1 → pay → verify2 → cancel → verify3
# 任一阶段失败 → exit 1(BLOCKER 流程:不擅自修复,看 blocker-snapshot.txt)。
set -euo pipefail

PERF_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$(dirname "$PERF_DIR")"

USERS="${USERS:-1000}"
INIT_STOCK="${INIT_STOCK:-500}"
PRODUCT_ID="${PRODUCT_ID:-2001}"
RAMPUP="${RAMPUP:-1}"
CANCEL_N="${CANCEL_N:-100}"
GATEWAY="${GATEWAY:-http://localhost:8080}"

export USERS INIT_STOCK PRODUCT_ID RAMPUP CANCEL_N GATEWAY

step() { printf '\n\033[36m=== %s ===\033[0m\n' "$*"; }

step "1/8 prepare-users(若 users.csv 已存在则跳过)"
bash perf/prepare-users.sh

step "2/8 reset-fixtures"
bash perf/reset-fixtures.sh

step "3/8 run JMeter"
if command -v jmeter >/dev/null 2>&1; then
  jmeter -n -t perf/order-create.jmx \
    -l perf/results/order-create.jtl \
    -j perf/results/jmeter.log \
    -e -o perf/results/html-report \
    -Jbase.url="$GATEWAY" \
    -Jproduct.id="$PRODUCT_ID" \
    -Jusers="$USERS" \
    -Jrampup="$RAMPUP" \
    -Jresults.dir=perf/results
else
  echo "本机无 jmeter,用 docker"
  USERS="$USERS" PRODUCT_ID="$PRODUCT_ID" RAMPUP="$RAMPUP" bash perf/jmeter-docker.sh
fi

step "4/8 verify-phase1(无超卖 + 账平 + TCC TRY)"
bash perf/verify-phase1.sh

step "5/8 pay-phase"
bash perf/pay-phase.sh

step "6/8 verify-phase2(TCC CONFIRM)"
bash perf/verify-phase2.sh

step "7/8 cancel-sample(N=$CANCEL_N)"
bash perf/cancel-sample.sh

step "8/8 verify-phase3(TCC CANCEL + Redis 回补)"
bash perf/verify-phase3.sh

printf '\n\033[32m=== PERF-PASS ===\033[0m\n'
echo "HTML 报告:perf/results/html-report/index.html"
echo "一致性证据:perf/results/evidence.txt"
echo "Grafana:http://localhost:3000/d/order-perf(admin/admin123)"
