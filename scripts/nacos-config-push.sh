#!/bin/sh
# T5.1 — 把 docs/nacos/ 下的公共配置推到 Nacos,实现"启栈即就绪"。
#
# 不推 jasypt 加密的 dev 模板(user/product/order-dev.yaml.template):
# 这些含 ENC(...) 密文,用户需先用 jasypt CLI 加密对应口令后,手工 paste 进 Nacos 控制台。
# 详见 docs/deployment.md §7。
#
# 推送内容(9 份):
#   - dev namespace: shopsphere-{gateway,user,product,order}.yaml(Java 公共非密)
#   - dev namespace: shopsphere-jwt-public-key.pem + Sentinel 流控规则
#   - public namespace: shopsphere-recommendation.yaml + shopsphere-recommendation-dev.yaml
set -e

NACOS_URL="${NACOS_URL:-http://nacos:8848}"
GROUP="${NACOS_GROUP:-DEFAULT_GROUP}"
CONFIG_DIR="${CONFIG_DIR:-/configs}"
DEV_NAMESPACE="${NACOS_DEV_NAMESPACE:-dev}"

namespace_exists() {
  namespace_id="$1"
  curl -fsS "$NACOS_URL/nacos/v1/console/namespaces" \
    | grep -q "\"namespace\":\"$namespace_id\""
}

ensure_namespace() {
  namespace_id="$1"
  [ -n "$namespace_id" ] || return 0

  if namespace_exists "$namespace_id"; then
    echo "[namespace] $namespace_id exists"
    return 0
  fi

  echo "[namespace] create $namespace_id"
  resp=$(curl -fsS -X POST "$NACOS_URL/nacos/v1/console/namespaces" \
    --data-urlencode "customNamespaceId=$namespace_id" \
    --data-urlencode "namespaceName=$namespace_id" \
    --data-urlencode "namespaceDesc=ShopSphere $namespace_id namespace")
  if [ "$resp" = "true" ] || namespace_exists "$namespace_id"; then
    echo "  OK"
    return 0
  fi

  echo "  FAIL body=$resp"
  exit 1
}

push() {
  data_id="$1"
  type="$2"
  tenant="${3:-}"
  file="$CONFIG_DIR/$data_id"

  if [ ! -f "$file" ]; then
    echo "[skip] $data_id (file not found: $file)"
    return 0
  fi

  if [ -n "$tenant" ]; then
    echo "[push] $data_id (type=$type tenant=$tenant)"
  else
    echo "[push] $data_id (type=$type tenant=public)"
  fi
  content=$(cat "$file")
  # Nacos OpenAPI v1:application/x-www-form-urlencoded,POST /v1/cs/configs
  if [ -n "$tenant" ]; then
    resp=$(curl -fsS -o /tmp/nacos_resp -w "%{http_code}" -X POST \
      "$NACOS_URL/nacos/v1/cs/configs" \
      -H "Content-Type: application/x-www-form-urlencoded" \
      --data-urlencode "dataId=$data_id" \
      --data-urlencode "group=$GROUP" \
      --data-urlencode "type=$type" \
      --data-urlencode "tenant=$tenant" \
      --data-urlencode "content=$content")
  else
    resp=$(curl -fsS -o /tmp/nacos_resp -w "%{http_code}" -X POST \
      "$NACOS_URL/nacos/v1/cs/configs" \
      -H "Content-Type: application/x-www-form-urlencoded" \
      --data-urlencode "dataId=$data_id" \
      --data-urlencode "group=$GROUP" \
      --data-urlencode "type=$type" \
      --data-urlencode "content=$content")
  fi
  body=$(cat /tmp/nacos_resp 2>/dev/null || echo "")
  if [ "$resp" != "200" ] || [ "$body" != "true" ]; then
    echo "  FAIL http=$resp body=$body"
    exit 1
  fi
  echo "  OK"
}

echo "== Nacos config push -> $NACOS_URL =="

ensure_namespace "$DEV_NAMESPACE"

# Java 服务读取 namespace=dev
push shopsphere-gateway.yaml          yaml "$DEV_NAMESPACE"
push shopsphere-user.yaml             yaml "$DEV_NAMESPACE"
push shopsphere-product.yaml          yaml "$DEV_NAMESPACE"
push shopsphere-order.yaml            yaml "$DEV_NAMESPACE"
push shopsphere-jwt-public-key.pem    text "$DEV_NAMESPACE"

# Python 服务读取 public namespace(容器/本地皆可用,用 ${VAR:default})
push shopsphere-recommendation.yaml   yaml
push shopsphere-recommendation-dev.yaml yaml

# Sentinel 流控规则(Java 服务读取 namespace=dev)
push shopsphere-gateway-flow-rules.json json "$DEV_NAMESPACE"
push shopsphere-user-flow-rules.json    json "$DEV_NAMESPACE"

echo "== Done =="
