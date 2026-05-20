# shopsphere-product

商品服务（商品/类目/库存表;查询为主,后台写接口本期为骨架）。

- 服务名:`shopsphere-product`
- 端口:`8082`
- 数据库:`shopsphere_product`
- 接口契约:`docs/api-contracts.md §6.2 §4.3`
- 路由 + 白名单:`docs/api-contracts.md §3.1`(`/api/product/**` 已含)

## 1. 接口

| 方法 | 路径 | 鉴权 | 错误码 |
|---|---|---|---|
| `GET` | `/api/product/{id}` | 公开 | `3001` 商品不存在 |
| `GET` | `/api/product/list?categoryId=&keyword=&page=&size=` | 公开 | — (空集 records=[]) |
| `GET` | `/api/product/category/tree` | 公开 | — |
| `POST` | `/internal/product/stock/{try,confirm,cancel}` | 内部 (Gateway 拒外) | `1500` not implemented (本期 T2.1 骨架,T2.4 落地) |
| `POST/PUT/DELETE` | `/internal/admin/{product,category}/**` | 内部 | `1500` not implemented |

**列表分页**:`page` 默认 1、最小 1;`size` 默认 20、最大 100(超出截断)。响应统一 `Result<PageResult<T>>`(契约 §1.2)。

**详情 `stock` 字段语义**:可售量 = `t_product_stock.stock - locked_stock`(契约 §4.3,`locked_stock` 为 TCC-Try 预留态)。

**类目树**:`/api/product/category/tree` 而非 `/api/category/tree` — 本期复用现有 Gateway 路由 `/api/product/**`,零 Gateway 改动。后续若需顶级 `/api/category` 入口,T2.x 再补。

## 2. 数据库迁移

Flyway 自动执行 `classpath:db/migration/V20260521_1000__init_product.sql`:
- `t_category` × 5(数码/服饰/家居/食品/图书)
- `t_product` × 20(id 2001-2020)
- `t_product_stock` × 20(每条 stock=100, locked_stock=0)

时间列 `DATETIME(3)` + Java `OffsetDateTime` UTC TypeHandler(`com.shopsphere.product.support.OffsetDateTimeTypeHandler`)。

## 3. MyBatis-Plus

- **雪花 ID**:`id-type: ASSIGN_ID`
- **全局逻辑删除**:字段 `status`,`1=有效 / 0=已删除`;`t_product_stock` 无 status 列,MP 自动跳过
- **自动填充**:`com.shopsphere.product.config.MybatisPlusMetaObjectHandler` 在 INSERT/UPDATE 写入 `OffsetDateTime.now(ZoneOffset.UTC)` 到 `createdAt`/`updatedAt`(实体字段标 `@TableField(fill=...)`)
- **分页 + 乐观锁**插件:`MybatisPlusConfig`(乐观锁 T2.4 启用 `@Version`)

## 4. Nacos dataId 清单

| dataId | 内容 | 谁来填 |
|---|---|---|
| `shopsphere-product.yaml` | 共享非密(本期空壳) | 仓库 `docs/nacos/shopsphere-product.yaml` 镜像 |
| `shopsphere-product-dev.yaml` | DB/Redis 凭据(Jasypt) | 部署人手工,对照 `.template` |

Jasypt 主密钥从环境变量 `JASYPT_ENCRYPTOR_PASSWORD` 注入(与 user 服务同源,见 `shopsphere-user/README.md §2.3`)。

## 5. 启动

```bash
docker compose -f docker-compose.infra.yml up -d mysql nacos redis
export JASYPT_ENCRYPTOR_PASSWORD=...   # 与 user 同
mvn -q -pl shopsphere-product -am spring-boot:run
# Flyway 自动执行 V20260521_1000__init_product.sql
```

健康检查:`curl localhost:8082/actuator/health`(内部探活)

## 6. 端到端验证

```bash
# 详情
curl localhost:8080/api/product/2001
# code=0; data.stock=100; data.price="14999.00"; data.createdAt 末尾 Z

# 列表 + 分页截断
curl 'localhost:8080/api/product/list?page=1&size=5'
# data.records.length=5; data.total=20

curl 'localhost:8080/api/product/list?size=999'
# data.size=100（截断生效）

# 过滤 + 关键字
curl 'localhost:8080/api/product/list?categoryId=1005&keyword=深入'
# 仅含《深入理解 JVM》

# 类目树
curl localhost:8080/api/product/category/tree
# 5 个根节点；children 为空数组

# 不存在
curl localhost:8080/api/product/99999
# code=3001 message="商品不存在"
```

**逻辑删除验证**:
```sql
UPDATE t_product SET status=0 WHERE id=2001;
```
再访问 `/api/product/2001` → `code=3001`;`/api/product/list` 不再包含。

**内部端点占位**(直连 8082,Gateway 不路由 /internal):
```bash
curl -X POST localhost:8082/internal/product/stock/try \
     -H 'Content-Type: application/json' -d '{"orderId":1,"items":[]}'
# code=1500 message="stock/try not implemented (T2.4)"
```

## 7. 后续路线(T2.x)

| 任务 | 关键点 |
|---|---|
| T2.2 商品详情 Redis Cache-Aside | TTL 随机 + 空值缓存防穿透 + 主动失效 |
| T2.3 库存预扣 Lua 脚本 | 原子性 + key `stock:product:{id}` |
| T2.4 库存 TCC 三段 | `/try` `/confirm` `/cancel` + `t_stock_tcc_log` 幂等/空回滚/防悬挂 |
