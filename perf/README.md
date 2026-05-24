# ShopSphere 压测(T5.3)

下单链路 1000 并发对 500 库存,验证**不超卖 + TCC 三段一致**。

## 用法

### 一键

```bash
# 1. 起栈(含 prometheus + grafana,profile=java 默认带)
cp .env.example .env
docker compose up --build -d
bash scripts/wait-stack-healthy.sh

# 2. 跑全链(首次会预创建 1000 个用户,~2 分钟)
bash perf/run-all.sh
```

期望尾行:

```
=== PERF-PASS ===
HTML 报告:perf/results/html-report/index.html
一致性证据:perf/results/evidence.txt
Grafana:http://localhost:3000/d/order-perf(admin/admin123)
```

### 分步

```bash
bash perf/prepare-users.sh           # 首次;之后跳过(--force 强重建)
bash perf/reset-fixtures.sh          # 重置库存 + 清订单/TCC log

# 跑 JMeter:本机有 jmeter 直接用 cmd;否则 docker
jmeter -n -t perf/order-create.jmx -l perf/results/order-create.jtl \
       -e -o perf/results/html-report \
       -Jbase.url=http://localhost:8080 -Jusers=1000 -Jrampup=1
# 或
bash perf/jmeter-docker.sh

bash perf/verify-phase1.sh           # 不超卖 + 账平 + TRY 计数
bash perf/pay-phase.sh
bash perf/verify-phase2.sh           # CONFIRM 计数 + locked_stock
bash perf/cancel-sample.sh           # 默认 100 单
bash perf/verify-phase3.sh           # CANCEL + Redis 回补
```

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `USERS` | 1000 | 并发用户数(同 jmeter thread 数) |
| `INIT_STOCK` | 500 | 商品初始库存 |
| `PRODUCT_ID` | 2001 | 压测商品 ID(种子库存表内必有) |
| `RAMPUP` | 1 | jmeter rampup(秒) |
| `CANCEL_N` | 100 | 阶段 3 取消单数 |
| `GATEWAY` | http://localhost:8080 | 网关地址 |
| `MYSQL_ROOT_PASSWORD` | root123 | docker mysql 密码 |
| `MYSQL_CONT` / `REDIS_CONT` | shopsphere-mysql / shopsphere-redis | 容器名 |

## 工具依赖

- `docker`(MySQL/Redis 验证用 `docker exec`)
- `jq`(JSON 解析)
- `jmeter` 5.x **或** docker(脚本自动检测)
- bash 4+(macOS 默认 3.2 也兼容,无 `mapfile` 等用法)

## 验收指标

| 指标 | 验收 | 验证脚本 |
|---|---|---|
| 不超卖 | 成功订单数 ≤ 500 | verify-phase1.sh A |
| 账平 | DB.stock + locked_stock == 500 | verify-phase1.sh B |
| Redis/DB | Redis = DB.stock(契约 §4.3) | verify-phase1.sh C / 2 / 3 |
| TCC.TRY 计数 | == 成功订单数 | verify-phase1.sh D |
| 失败码合规 | 全 3002(无 5xx 等) | verify-phase1.sh E |
| TCC.CONFIRM | == 实际 paid | verify-phase2.sh F |
| TCC.CANCEL | == 实际 cancelled | verify-phase3.sh I |
| QPS | ≥ 200 | JMeter aggregate.csv |
| P99 | < 500ms | JMeter aggregate.csv |
| 错误率 | 扣 3002 后 < 0.1% | verify-phase1.sh E |

## 失败处理(BLOCKER)

`verify-phase*.sh` 失败 → 自动生成 `perf/results/blocker-snapshot.txt`,内含:
- t_product_stock 当前值
- t_stock_tcc_log 按 (phase,state) 计数
- Redis 当前 stock 值
- order 服务 / product 服务最近 100 行日志

**不要擅自修复源码**。把 snapshot 贴到 `docs/perf-tcc-report.md` §8 + 列 3 个根因假设,等拍板。

## 文件

```
perf/
├── order-create.jmx     # JMeter 测试计划
├── prepare-users.sh     # 预创建用户(幂等)
├── reset-fixtures.sh    # 重置 DB + Redis
├── verify-phase1.sh     # 下单后一致性
├── pay-phase.sh         # 批量支付
├── verify-phase2.sh     # 支付后一致性
├── cancel-sample.sh     # 抽样取消
├── verify-phase3.sh     # 取消后一致性
├── run-all.sh           # 一键编排
├── jmeter-docker.sh     # docker JMeter
└── results/             # 输出(.gitignored)
    ├── users.csv
    ├── orders-success.csv / oversold-rejected.csv / errors.csv
    ├── orders-paid.csv / pay-errors.csv
    ├── orders-cancelled.csv / cancel-errors.csv
    ├── aggregate.csv / order-create.jtl / jmeter.log
    ├── html-report/index.html
    ├── evidence.txt          # 三阶段实测值合集
    └── blocker-snapshot.txt  # 失败时才生成
```
