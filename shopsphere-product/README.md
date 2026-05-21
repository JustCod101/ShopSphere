# shopsphere-product

商品服务 —— 商品/类目查询、详情 Redis 缓存、库存原子预扣、库存 TCC 接口。

- 服务名:`shopsphere-product`
- 端口:`8082`
- 数据库:`shopsphere_product`
- 接口契约:`docs/api-contracts.md §6.2 §4.3`
- 路由 + 白名单:`docs/api-contracts.md §3.1`(`/api/product/**` 已含)
- 专题文档:缓存 `docs/perf-cache.md`、库存 Redis `docs/stock-redis.md`

## 1. 接口

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| `GET` | `/api/product/{id}` | 公开 | 商品详情;Cache-Aside;不存在 `3001` |
| `GET` | `/api/product/list?categoryId=&keyword=&page=&size=` | 公开 | 列表分页;空集 `records=[]` |
| `GET` | `/api/product/category/tree` | 公开 | 完整类目树 |
| `GET` | `/internal/product/{id}` | 内部 | Feign `ProductFeignClient.getDetail`,返回 `ProductDetailDTO` |
| `POST` | `/internal/product/stock/try` | 内部 | 库存 TCC-Try 预扣(T2.4 骨架,完整 TCC T3.3) |
| `POST` | `/internal/product/stock/confirm` | 内部 | 库存 TCC-Confirm(T2.4 骨架) |
| `POST` | `/internal/product/stock/cancel` | 内部 | 库存 TCC-Cancel 释放回补(T2.4 骨架) |
| `POST/PUT/DELETE` | `/internal/admin/{product,category}/**` | 内部 | 后台 CRUD 占位 `1500`(后续) |

`/internal/**` 不被 Gateway 路由(§4.1,T1.1 已落地);服务间走 Nacos 直连。

**列表分页**:`page` 默认 1、最小 1;`size` 默认 20、最大 100(超出截断)。统一 `Result<PageResult<T>>`(§1.2)。

**详情 `stock` 字段**:可售量,T2.3 起取自 Redis 常驻计数器 `stock:product:{id}`(契约 §4.3)。

## 2. 数据库迁移(Flyway)

| 版本 | 内容 |
|---|---|
| `V20260521_1000__init_product.sql` | `t_category`×5 + `t_product`×20(id 2001-2020)+ `t_product_stock`×20(stock=100) |
| `V20260521_1001__add_stock_tcc_log.sql` | `t_stock_tcc_log` —— 库存 TCC 幂等/空回滚日志 |

时间列 `DATETIME(3)` + Java `OffsetDateTime` UTC TypeHandler(`support.OffsetDateTimeTypeHandler`)。

## 3. MyBatis-Plus

- **雪花 ID**:`id-type: ASSIGN_ID`
- **全局逻辑删除**:字段 `status`(`1` 有效 / `0` 已删除);`t_product_stock`、`t_stock_tcc_log` 无 status 列,MP 自动跳过
- **自动填充**:`config.MybatisPlusMetaObjectHandler` 在 INSERT/UPDATE 写 `OffsetDateTime.now(UTC)` 到 `createdAt`/`updatedAt`
- **分页 + 乐观锁**插件:`config.MybatisPlusConfig`(`@Version` 占位,T3.3 库存 DB 条件更新时启用)

## 4. 商品详情缓存(T2.2,详见 `docs/perf-cache.md`)

- key `product:detail:{id}` —— 静态字段缓存,TTL 30min + 0~300s 随机抖动(防雪崩)
- 防穿透:不存在也缓存 `__NULL__`(TTL 120s);防击穿:Redisson 锁 + 重试
- 命中率埋点:5 个 Micrometer Counter → `/actuator/metrics/product.detail.cache.*`

## 5. 库存 Redis 原子预扣(T2.3,详见 `docs/stock-redis.md`)

- key `stock:product:{id}` —— 常驻计数器(无 TTL),启动 `StockWarmupRunner` 从 DB 预热
- Lua 脚本(`resources/scripts/`):`stock_prededuct.lua`(GET+判断+DECRBY 原子)、`stock_restore.lua`
- 对账任务 `StockReconciliationTask`:每 5min 比对 Redis vs DB,差异打 ERROR(不自动修复)

## 6. 库存 TCC 接口骨架(T2.4)

- `ProductFeignClient`(`product-api`)4 方法:`getDetail` + `stockTry/stockConfirm/stockCancel`
- `StockTccService` 骨架:幂等表 `t_stock_tcc_log` 写入(幂等键 `(orderId, productId, phase)`)+ 直调 `StockRedisService`
- **完整 Seata `@TwoPhaseBusinessAction` / 空回滚 / 防悬挂 / DB `t_product_stock` 条件更新 → T3.3**(代码内 `TODO(T3.3)` 已标)

## 7. Nacos dataId 清单

| dataId | 内容 | 谁来填 |
|---|---|---|
| `shopsphere-product.yaml` | 共享非密 | 仓库 `docs/nacos/shopsphere-product.yaml` 镜像 |
| `shopsphere-product-dev.yaml` | DB/Redis 凭据(Jasypt) | 部署人手工,对照 `.template` |

Jasypt 主密钥从环境变量 `JASYPT_ENCRYPTOR_PASSWORD` 注入(与 user 服务同源,见 `shopsphere-user/README.md §2.3`)。

## 8. 启动

```bash
docker compose -f docker-compose.infra.yml up -d mysql nacos redis
export JASYPT_ENCRYPTOR_PASSWORD=...   # 与 user 同
mvn -q -pl shopsphere-product -am spring-boot:run
# Flyway 自动执行 V20260521_1000 / V20260521_1001;StockWarmupRunner 预热库存到 Redis
```

健康检查:`curl localhost:8082/actuator/health`(内部探活)

## 9. 端到端验证

```bash
# 详情（命中缓存）
curl localhost:8080/api/product/2001              # code=0; data.stock=100
redis-cli get product:detail:2001                 # 静态字段 JSON
redis-cli get stock:product:2001                  # 100（常驻计数器，TTL=-1）

# 列表 / 类目树 / 不存在
curl 'localhost:8080/api/product/list?categoryId=1005&keyword=深入'   # 仅《深入理解 JVM》
curl localhost:8080/api/product/category/tree     # 5 个根节点
curl localhost:8080/api/product/99999             # code=3001

# 库存 TCC 骨架（直连 8082，Gateway 不路由 /internal）
curl -sXPOST localhost:8082/internal/product/stock/try -H 'Content-Type: application/json' \
  -d '{"xid":"x1","orderId":9001,"items":[{"productId":2001,"quantity":3}]}'
# code=0；stock:product:2001 → 97；t_stock_tcc_log 多一行 TRY
curl localhost:8082/internal/product/2001         # ProductDetailDTO
```

## 10. 测试

`mvn -pl shopsphere-product test` —— 75 例(含 `StockRedisServiceTest` Testcontainers 真 Redis,**需 Docker**)。
新版 Docker 守护进程的 API 版本兼容已在 surefire 固定 `api.version=1.44`(详见 `docs/stock-redis.md §8`)。

## 11. 后续路线(T3.x)

| 任务 | 关键点 |
|---|---|
| T3.2 | Order 服务引用 `ProductFeignClient` 下单 |
| T3.3 | 完整 Seata TCC:`@TwoPhaseBusinessAction` + 空回滚/防悬挂 + DB `t_product_stock` 条件更新 |
