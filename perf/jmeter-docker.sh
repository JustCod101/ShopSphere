#!/usr/bin/env bash
# T5.3 用 docker 跑 JMeter(避免本机装)。挂 perf/ 到容器里。
# 若本机已装 jmeter,优先 `jmeter -n -t ...` 速度更快。
set -euo pipefail

PERF_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$PERF_DIR/.." && pwd)"
USERS="${USERS:-1000}"
PRODUCT_ID="${PRODUCT_ID:-2001}"
RAMPUP="${RAMPUP:-1}"
# 容器内访问 host 上的 gateway 需要 host.docker.internal(macOS / Win)
BASE_URL="${BASE_URL:-http://host.docker.internal:8080}"

# 当 prepare-users 已写好 users.csv 时,results/ 内必有
[ -f "$PERF_DIR/results/users.csv" ] || { echo "缺少 users.csv,先跑 perf/prepare-users.sh" >&2; exit 1; }

mkdir -p "$PERF_DIR/results/html-report"

docker run --rm \
  --add-host=host.docker.internal:host-gateway \
  -v "$PROJECT_ROOT:/work" \
  -w /work \
  justb4/jmeter:5.6.3 \
  -n -t perf/order-create.jmx \
  -l perf/results/order-create.jtl \
  -j perf/results/jmeter.log \
  -e -o perf/results/html-report \
  -Jbase.url="$BASE_URL" \
  -Jproduct.id="$PRODUCT_ID" \
  -Jusers="$USERS" \
  -Jrampup="$RAMPUP" \
  -Jresults.dir=perf/results
