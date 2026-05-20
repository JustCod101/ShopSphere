# shopsphere-user

用户服务（注册 / 登录 / 查询）+ JWT **签发侧**（RS256，与 Gateway 验签侧 T1.2 闭环）。

- 服务名：`shopsphere-user`
- 端口：`8081`
- 数据库：`shopsphere_user`
- 接口契约：`docs/api-contracts.md §6.1`
- 防爆破：`docs/api-contracts.md §10`(Phase 1 已落地)

## 1. 接口

| 方法 | 路径 | 鉴权 | 错误码 |
|---|---|---|---|
| `POST` | `/api/user/register` | 公开 | `2001` 用户名已存在 / `1000` 校验 |
| `POST` | `/api/user/login`    | 公开 | `2003` 用户不存在 / `2002` 密码错 / `2002` + "账号已临时锁定，请稍后再试" |
| `GET`  | `/api/user/me`       | 需登录 | `1001` 未认证 |
| `GET`  | `/internal/user/{id}` | 内部 Feign（Gateway 已拒外部） | `2003` 不存在 |

## 2. 密钥初始化（**只做一次**）

### 2.1 生成 RSA 密钥对

```bash
mvn -q -pl shopsphere-user -am compile
java -cp shopsphere-user/target/classes:shopsphere-common/target/classes \
     com.shopsphere.user.cli.KeyGenCli > /tmp/keys.pem
# 拆出 私钥 / 公钥
awk '/BEGIN PRIVATE/,/END PRIVATE/' /tmp/keys.pem > data/jwt/jwt-private-key.pem
awk '/BEGIN PUBLIC/,/END PUBLIC/'   /tmp/keys.pem > docs/nacos/shopsphere-jwt-public-key.pem
shred -u /tmp/keys.pem
```

> `data/jwt/` 已被 `/data/` 整目录 `.gitignore`；`docs/nacos/shopsphere-jwt-public-key.pem` 为公钥，入库无碍。

### 2.2 上传公钥到 Nacos

Nacos console → namespace `dev` → `dataId=shopsphere-jwt-public-key.pem` → 粘贴 `docs/nacos/shopsphere-jwt-public-key.pem` 全文（含 BEGIN/END 行）→ 发布。

### 2.3 设置 Jasypt 主密钥（仅本机）

```bash
echo "JASYPT_ENCRYPTOR_PASSWORD=$(openssl rand -base64 32)" >> .env
source .env && export JASYPT_ENCRYPTOR_PASSWORD
```

### 2.4 加密私钥并贴 Nacos

```bash
mvn -q com.github.ulisesbocchio:jasypt-maven-plugin:3.0.5:encrypt-value \
    -Djasypt.encryptor.password="$JASYPT_ENCRYPTOR_PASSWORD" \
    -Djasypt.plugin.value="$(tr -d '\n' < data/jwt/jwt-private-key.pem)"
# 输出 ENC(xxx==) → 粘贴到 Nacos dataId=shopsphere-user-dev.yaml 的 jwt.private-key 字段
```

模板见 `docs/nacos/shopsphere-user-dev.yaml.template`。加密完成后**销毁本地明文私钥**：

```bash
shred -u data/jwt/jwt-private-key.pem
```

### 2.5 上传共享配置

Nacos console → `dataId=shopsphere-user.yaml` → 粘贴 `docs/nacos/shopsphere-user.yaml` → 发布。

## 3. 启动

```bash
docker compose up -d mysql redis nacos
export JASYPT_ENCRYPTOR_PASSWORD=...  # 与 2.3 同
mvn -q -pl shopsphere-user -am spring-boot:run
# Flyway 自动执行 V20260520_1000__init_user.sql
```

健康检查：`curl localhost:8081/actuator/health`(服务端非公网，仅供内部探活)

## 4. Nacos dataId 清单

| dataId | 内容 | 谁来填 |
|---|---|---|
| `shopsphere-user.yaml` | jwt.expire-seconds、防爆破阈值 | 仓库 `docs/nacos/shopsphere-user.yaml` 镜像 |
| `shopsphere-user-dev.yaml` | jwt.private-key(Jasypt)、DB / Redis 凭据 | 部署人手工，对照 `.template` |
| `shopsphere-jwt-public-key.pem` | 公钥裸 PEM | 仓库 `docs/nacos/shopsphere-jwt-public-key.pem` 镜像（T1.2 已上传） |

## 5. JWT Claims 契约

固定 `{userId: Long, userName: String}` + JJWT 内置 iat / exp(RS256)。
**禁止**改用标准 `sub` / `name` —— 与 Gateway `JwtAuthFilter`(T1.2) 解析键对齐。

## 6. 端到端验证

```bash
curl -X POST localhost:8080/api/user/register -H 'Content-Type: application/json' \
     -d '{"username":"alice","password":"Aa12345678","email":"a@x.com","phone":"13800000000"}'
# code=0

curl -X POST localhost:8080/api/user/register -H 'Content-Type: application/json' \
     -d '{"username":"alice","password":"Aa12345678"}'
# code=2001

for i in $(seq 1 5); do
  curl -X POST localhost:8080/api/user/login -H 'Content-Type: application/json' \
       -d '{"username":"alice","password":"WRONG_PWD"}'
done
# 前 4 次 code=2002 密码错误；第 5 次起 code=2002 message="账号已临时锁定，请稍后再试"

# 等 30min 或手动 redis-cli DEL user:login:lock:alice
TOKEN=$(curl -sX POST localhost:8080/api/user/login -H 'Content-Type: application/json' \
        -d '{"username":"alice","password":"Aa12345678"}' | jq -r .data.token)
curl localhost:8080/api/user/me -H "Authorization: Bearer $TOKEN"
# code=0; data 无 password_hash 字段
```
