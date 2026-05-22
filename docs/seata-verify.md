# Seata 接入验证清单（T3.1）

本文档给出 ShopSphere 三个业务服务（order / product / user）接入 Seata 分布式事务后的
**验证步骤与判定标准**。T3.1 只做「客户端接入 + 事务表初始化 + XID 透传验证」，不含下单业务。

- Seata 版本：**1.8.0**（Server 与 Client 一致；版本经父 pom `dependencyManagement` 锁定）
- 事务分组：`shopsphere-tx-group` → 集群 `default`
- Seata 注册/配置中心：Nacos，**namespace `""`（public）、group `SEATA_GROUP`**
  （注意：业务自身的服务发现/配置用 namespace `dev`，与 Seata 的 `""` 互不影响）

---

## 1. 前置：基础设施就绪（T0.2）

```bash
docker compose -f docker-compose.infra.yml up -d
docker compose -f docker-compose.infra.yml ps      # seata-server 应为 healthy
```

判定：
- [ ] `shopsphere-seata` 容器 healthy（端口 `8091` TC / `7091` 控制台）。
- [ ] MySQL 库 `shopsphere_seata` 含 `global_table` / `branch_table` / `lock_table` / `distributed_lock`
      （由 `scripts/03-seata-server.sql` 建）。
- [ ] Nacos `SEATA_GROUP` 下有 `service.vgroupMapping.shopsphere-tx-group`、`store.mode` 等配置
      （由 `scripts/seata-config.txt` + `seata-nacos-config.sh` 推送）。

## 2. 编译与依赖版本核对

```bash
mvn -T 2 -pl shopsphere-order,shopsphere-product,shopsphere-user -am clean install -DskipTests
mvn -q -pl shopsphere-order dependency:tree | grep io.seata
```

判定：
- [ ] 全 reactor 编译通过（新增 `shopsphere-order` 模块不破坏既有构建）。
- [ ] 所有 `io.seata:*` 依赖解析为 **1.8.0**（无 2.x 泄漏）。

## 3. 部署 Order 服务的 Nacos 配置

参照 `docs/nacos/shopsphere-order.yaml` 与 `docs/nacos/shopsphere-order-dev.yaml.template`，
在 Nacos（namespace `dev`、group `DEFAULT_GROUP`）建：
- `shopsphere-order.yaml`（共享非密）
- `shopsphere-order-dev.yaml`（DB 口令 `ENC(...)`，Jasypt 加密；密钥经 `JASYPT_ENCRYPTOR_PASSWORD` 注入）

判定：
- [ ] 两个 dataId 已创建，Order 服务可读取并连上 `shopsphere_order` 库。

## 4. 启动三服务，检查 TM/RM 注册日志

```bash
# 各服务启动后 grep 启动日志
grep -E "register (TM|RM)_ROLE? .*success|register success" <服务日志>
```

判定（order / product / user 各一遍）：
- [ ] 日志含 Seata 客户端连上 TC 的成功记录（`register TM success` / `register RM success`，
      或等价的 `RmNettyRemotingClient`/`TmNettyRemotingClient` 连接成功行）。
- [ ] 无 `can not connect to ... cause:...` 之类的 TC 连接失败堆栈。

## 5. Flyway 建表核对

判定：
- [ ] `shopsphere_order`：`t_order` / `t_order_item` / `t_order_request` / `t_local_message` / `undo_log`。
- [ ] `shopsphere_user`：新增 `undo_log`。
- [ ] `shopsphere_product`：**无** `undo_log`（库存走 TCC，不用 AT —— 预期）。
- [ ] 各库 `flyway_schema_history` 记录新版本（order `V20260521_1000`/`V20260521_1001`，
      user `V20260521_1000`）。

## 6. Seata 控制台

浏览器开 `http://localhost:7091`，账号 `seata` / `seata`。

判定：
- [ ] 三个业务服务的 RM 资源在线（数据源被 Seata 代理后随服务启动注册）。

## 7. XID 透传验证（核心）

`/internal/**` 不被 Gateway 路由，直连订单服务调用临时验证端点：

```bash
curl -s localhost:8083/internal/seata/verify | jq
```

期望响应：

```json
{
  "code": 0,
  "data": {
    "orderXid": "192.168.x.x:8091:xxxxxxxxxx",
    "productXid": "192.168.x.x:8091:xxxxxxxxxx",
    "match": true
  }
}
```

判定：
- [ ] `data.orderXid` 非 null（`@GlobalTransactional` 已开启全局事务）。
- [ ] `data.productXid` == `data.orderXid`（XID 经 Feign 透传到 Product 并被正确绑定）。
- [ ] `data.match` 为 `true`。
- [ ] Seata 控制台出现一条名为 `seata-xid-verify` 的全局事务记录。

> 验证端点为临时件（`TempSeataEchoController` / `SeataEchoClient` / `TempSeataVerifyController`，
> 均标 `// TEMP T3.1`），T3.2 接入正式下单链路后删除。

## 8. 回归

判定：
- [ ] Product 既有公开接口正常：`curl localhost:8080/api/product/2001` → `code=0`。
- [ ] User 既有接口正常：`curl -XPOST localhost:8080/api/user/login ...` → 正常签发。
- [ ] 接入 Seata 后三服务原有功能无回归。

---

## 已知边界（T3.1 范围内、有意推迟）

- **Product 关闭数据源 AT 自动代理**（`seata.enable-auto-data-source-proxy: false`，T3.2 起）：
  Product 为 TCC-only 服务，库存分支走 TCC（`@TwoPhaseBusinessAction`，T3.3），不做 AT 分支、
  不建 `undo_log`。全局事务经 Feign 透传 XID 到 Product 后，其 `t_stock_tcc_log` 写入为普通本地
  事务（非 Seata 分支）。若保留 AT 代理，该写入会被注册为 AT 分支并要求 `undo_log` → 下单失败。
- `t_local_message` 的 PENDING 行投递（outbox 中继任务）为后续任务。
- 库存 TCC-Confirm/Cancel（`/pay`、超时取消）见 T3.3。
