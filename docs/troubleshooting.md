# 排障手册

> 部署期常见问题(端口冲突、Nacos 注册 IP 等)见 [deployment.md §7](deployment.md);本手册聚焦**运行期 / 集成期**故障,按错误现象归类。

## 1. Nacos 连不上

**现象**
- 服务启动卡住 / 报 `NacosException: failed to req API: /nacos/v2/...`
- Nacos 控制台 `服务列表` 缺服务

**排查**
1. `docker compose ps nacos` 看 `STATUS` 是否 `healthy`
2. 宿主机 `curl http://localhost:8848/nacos/v1/console/health/liveness` 应返 `UP`
3. 容器内服务连地址必须用 **`nacos:8848`**(compose 网络名),宿主机才用 `localhost:8848`
4. Nacos 2.x 需要 **9848 / 9849 gRPC 端口**(`docker-compose.yml` 已暴露,如改了请同步)
5. JVM 启动参数 / `spring.cloud.nacos.server-addr` 是否被覆盖错

**常见根因**
- `.env` 里的 `NACOS_PORT` 改了,但 `application.yml` 引用的还是 8848
- 防火墙拦 9848 gRPC(只放 8848 不够)

---

## 2. Seata 注册失败 / XID 透传失败

**现象**
- `io.seata.common.exception.NotSupportYetException`
- 业务方法标了 `@GlobalTransactional` 但下游 branch 未挂(Seata 控制台事务列表为空)
- 跨服务调用后回滚失败

**排查**
1. `docker compose logs seata | grep "Server started"` — 服务端就绪
2. 客户端 `seata.tx-service-group=default_tx_group`,与 server 注册的 group **一致**(Nacos 上 `service.vgroupMapping.default_tx_group=default`)
3. Feign 调用是否经过 `SeataHandlerInterceptor`(SCA 自带,无需额外配置;但若用了自定义 `RequestInterceptor` 必须 chain 调用)
4. 业务方法是否在 `@GlobalTransactional` **方法范围内**(标在 controller 上 vs service 上,代理是否生效)
5. 看 Order 服务日志,搜 `XID`,是否有 `XID: 192.168.x.x:8091:xxxx` 字样

**详细验收清单**:[seata-verify.md](seata-verify.md)

---

## 3. TCC 空回滚 / 悬挂 ★

**关键定义**
- **空回滚**:Try 未执行(网络丢失 / 超时)但 Cancel 被 Seata 调用 → Cancel 必须能识别"我没 Try 过"并仅占位,**不执行库存回滚**
- **悬挂**:Cancel 比 Try 先到(网络乱序) → 后续 Try 必须**拒绝**(否则库存被永久占用,无人 Cancel)

**实现**(详见 [ADR-003](adr/ADR-003-stock-seata-tcc.md))
- 表:`t_stock_tcc_log`,UK `(order_id, product_id, phase)`
- Cancel 时 INSERT `phase=CANCEL, state=1` 占位
- Try 时检测同 `(order_id, product_id)` 是否已有 CANCEL 行 → 有则拒绝

**排查步骤**(超卖 / 库存账不平时)
```sql
-- 1. 看三段计数是否对齐
SELECT phase, state, COUNT(*) FROM t_stock_tcc_log
 WHERE order_id IN (?) GROUP BY phase, state;

-- 2. 看单笔订单的三段顺序
SELECT * FROM t_stock_tcc_log WHERE order_id = ? ORDER BY id;

-- 3. 看库存表当前值
SELECT stock, locked_stock, stock + locked_stock AS total
  FROM t_product_stock WHERE product_id = ?;
```

**期望**
- `total` = 初始库存(账平不变量)
- `TRY 计数 = CONFIRM 计数 + 未付订单数 + 取消订单数`
- 若 Try 行存在但库存表未变 → DB 事务回滚了 TCC 三段日志未回滚(检查事务边界)

**验收报告**:[tcc-rollback-report.md](tcc-rollback-report.md)(7 个 case 全绿)

---

## 4. Redis / DB 库存漂移

**契约**([api-contracts §4.3](api-contracts.md))
> `redis-cli GET stock:product:{id}` == `DB.stock - DB.locked_stock`(可售量,非 DB.stock 本身)

**漂移成因**
- Redis 重启丢数据(默认无 AOF / RDB 持久化)
- TCC Cancel 漏调 Redis 回补(代码 bug,在 `StockTccServiceImpl` 检查 cancel 分支)
- 双写时序:先扣 Redis 再 DB,DB 失败但 Redis 未回滚 → Redis 偏少
- Lua 脚本异常被吞(检查 RedisTemplate.execute 是否捕获异常)

**应急修复**
```bash
# 容器内执行(redis 没暴露宿主机时)
docker exec shopsphere-redis redis-cli SET stock:product:2001 <DB.stock - DB.locked_stock>

# 同时刷其他热门商品(批量)
docker exec shopsphere-mysql mysql -uroot -p${MYSQL_ROOT_PASSWORD} -Bse \
  "SELECT CONCAT('SET stock:product:', product_id, ' ', stock - locked_stock) FROM shopsphere_product.t_product_stock" \
  | docker exec -i shopsphere-redis redis-cli --pipe
```

**详细设计**:[stock-redis.md](stock-redis.md)

---

## 5. MQ 延迟消息插件未启用

**现象**
- `order.payment.timeout` 消息**立刻**触发(没等 30 分钟)
- Order 收到 cancel 调用,日志显示订单刚创建几秒就被自动取消

**根因**
- RabbitMQ 没装 `rabbitmq_delayed_message_exchange` 插件 → x-delayed-message 类型 exchange 退化成普通 fanout/topic

**排查**
```bash
docker exec shopsphere-rabbitmq rabbitmq-plugins list | grep delayed
# 期望:[E*] rabbitmq_delayed_message_exchange
```

**修复**
- 使用本仓库 `rabbitmq/Dockerfile`(已预装)
- 自建镜像时:`rabbitmq-plugins enable rabbitmq_delayed_message_exchange`
- 详细 exchange 配置:[mq-topology.md](mq-topology.md)

---

## 6. Python(推荐)注册 IP 不可达

**现象**
- Gateway 路由 `/api/recommend/**` 报 `Connection refused` / `503`
- Nacos 服务列表显示 reco 已注册,IP 是 `172.x.x.x`(容器内 IP)

**根因**
- Python 服务通过 `nacos-sdk-python` 注册时,如果不显式指定 IP,会自动取容器 eth0 IP(`172.x.x.x`),宿主机 Gateway 无法直连(网段隔离)

**修复**
```bash
# 在 .env 设置:
NACOS_REGISTER_IP=host.docker.internal      # macOS / Windows
# 或宿主机网卡 IP(Linux):
NACOS_REGISTER_IP=192.168.x.x
```

`shopsphere-recommendation/app/main.py` 启动时读 `NACOS_REGISTER_IP`,优先于自动检测。

**详细说明**:[deployment.md §6](deployment.md)

---

## 7. JWT 校验 401(Gateway 1001)

**现象**
- 网关返回 `{"code": 1001, "message": "Unauthorized"}`
- 业务服务零错误日志(因为请求被 Gateway 拦下,根本没透传)

**排查**
1. Header 名字 `Authorization`,值前缀 `Bearer `(大小写要对,**`Bearer` 后有一个空格**)
2. Token 未过期(默认 7 天,见 `shopsphere-user/.../JwtConfig.java`)
3. 公钥(`scripts/gen-jwt-keys.sh` 产物)在 Gateway 与签发方 User 服务一致
4. **绝不允许业务服务自验 JWT**(违反 [ADR-002](adr/ADR-002-jwt-at-gateway.md))— 业务只读 `X-User-Id` header

**业务侧测试**
```bash
# 模拟网关注入,直接打业务服务(跳过 Gateway 鉴权)
curl -H "X-User-Id: 1001" -H "X-User-Name: tester" http://localhost:8081/api/user/me
```

---

## 8. Sentinel 限流命中

**现象**
- 返回 `{"code": 1003, "message": "Rate Limited"}`
- JMeter aggregate.csv 错误率上涨,但订单库 / 库存账平

**排查**
1. Sentinel 控制台 `http://localhost:8858` → 看实时 QPS / 拒绝
2. Nacos 配置 `sentinel-rules-{gateway,user,order}-flow.json` — 当前生效规则
3. 业务侧 `@SentinelResource` fallback 是否被命中(看日志)

**调整规则**(热加载,无需重启)
- 直接在 Nacos 控制台编辑 `sentinel-rules-*-flow.json`,保存即生效
- 验证:再次 JMeter,看错误率桶

**详细规则**:[sentinel-rules.md](sentinel-rules.md)

---

## 附:错误码速查表

| code | 含义 | 来源 | 处置 |
|---|---|---|---|
| 1000 | 参数非法 | 业务 | 看响应 message 修正请求 |
| 1001 | 鉴权失败 | Gateway | 检查 JWT |
| 1003 | 限流 | Sentinel | §8 |
| 1004 | 路由不存在 | Gateway 独占 | 检查 Nacos 服务列表 |
| 2001-2003 | 用户域 | User | 看 ErrorCode.java |
| 3001-3003 | 商品域 | Product | `3002=STOCK_NOT_ENOUGH`(下单超卖正常拒绝码) |
| 4001-4003 | 订单域 | Order | `4001=ORDER_NOT_FOUND, 4002=ORDER_STATUS_INVALID` |
| 5xx | 框架/未捕获 | 任意 | **不应出现**;出现即看服务日志,补全异常处理 |
