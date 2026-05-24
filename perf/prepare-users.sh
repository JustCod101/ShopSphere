#!/usr/bin/env bash
# T5.3 预创建 1000 个压测用户 + 拿 JWT,落 perf/results/users.csv。
# 用法:bash perf/prepare-users.sh [--force]
#       USERS=1000 GATEWAY=http://localhost:8080 bash perf/prepare-users.sh
# 幂等:users.csv 已存在且行数 == USERS 时直接跳过,除非 --force。
# 默认串行并轻微限速,避免撞 Gateway api-user-register 5 QPS Sentinel 规则。
set -euo pipefail

USERS="${USERS:-1000}"
GATEWAY="${GATEWAY:-http://localhost:8080}"
PREPARE_PARALLEL="${PREPARE_PARALLEL:-1}"
PREPARE_SLEEP="${PREPARE_SLEEP:-0.25}"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)/results"
USERS_CSV="$OUT_DIR/users.csv"
FORCE=""

for arg in "$@"; do
  case "$arg" in
    --force|-f) FORCE=1 ;;
    *) echo "未知参数: $arg" >&2; exit 2 ;;
  esac
done

mkdir -p "$OUT_DIR"

if [ -z "$FORCE" ] && [ -f "$USERS_CSV" ]; then
  existing=$(wc -l < "$USERS_CSV" | tr -d ' ')
  if [ "$existing" -eq "$USERS" ]; then
    echo "users.csv 已存在($existing 行),跳过注册。--force 强制重建。"
    exit 0
  fi
  echo "users.csv 存在但行数 $existing != $USERS,重建。"
fi

command -v jq >/dev/null 2>&1 || { echo "需要 jq:brew install jq" >&2; exit 1; }

# 全新生成:先清空
: > "$USERS_CSV"
: > "$OUT_DIR/prepare-errors.log"

# 时间戳前缀让用户名唯一(每次跑 prepare 都新一批);用户登录密码统一
TS="$(date +%s)"
PASSWORD="Perf12345"

register_one() {
  local i="$1"
  local uname="pf${TS}$(printf '%04d' "$i")"
  local reg_body resp uid login_resp token

  reg_body=$(jq -nc --arg u "$uname" --arg p "$PASSWORD" '{username:$u, password:$p}')

  resp=$(curl -fsS -X POST -H 'Content-Type: application/json' \
    -d "$reg_body" "$GATEWAY/api/user/register" 2>>"$OUT_DIR/prepare-errors.log" || true)
  uid=$(echo "$resp" | jq -r '.data.id // empty' 2>/dev/null || true)
  if [ -z "$uid" ]; then
    echo "register-fail $i $uname $resp" >> "$OUT_DIR/prepare-errors.log"
    return 1
  fi

  login_resp=$(curl -fsS -X POST -H 'Content-Type: application/json' \
    -d "$reg_body" "$GATEWAY/api/user/login" 2>>"$OUT_DIR/prepare-errors.log" || true)
  token=$(echo "$login_resp" | jq -r '.data.token // empty' 2>/dev/null || true)
  if [ -z "$token" ]; then
    echo "login-fail $i $uname $login_resp" >> "$OUT_DIR/prepare-errors.log"
    return 1
  fi

  # 单行 append 由 OS 在 < PIPE_BUF 时原子;CSV 一行 << 4KB 安全
  printf '%s,%s\n' "$uid" "$token" >> "$USERS_CSV"
  sleep "$PREPARE_SLEEP"
}

export -f register_one
export TS PASSWORD GATEWAY OUT_DIR USERS_CSV PREPARE_SLEEP

echo "注册 $USERS 用户 -> $USERS_CSV(并发 $PREPARE_PARALLEL, sleep ${PREPARE_SLEEP}s)"
seq 1 "$USERS" | xargs -P "$PREPARE_PARALLEL" -I {} bash -c 'register_one "$@"' _ {} || true

got=$(wc -l < "$USERS_CSV" | tr -d ' ')
echo "完成:$got/$USERS"
if [ "$got" -lt "$USERS" ]; then
  echo "失败 $((USERS - got)) 条,详见 $OUT_DIR/prepare-errors.log"
  exit 1
fi
