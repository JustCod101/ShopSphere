# T5.1 — shopsphere-gateway 多阶段构建
# build context 必须为项目根(父 pom + common/api 依赖):
#   docker build -f shopsphere-gateway/Dockerfile -t shopsphere/gateway .
# docker-compose 中 build.context: .  dockerfile: shopsphere-gateway/Dockerfile

# ---------- builder ----------
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 先 COPY 全部 pom,触发 Docker layer 缓存(源码未改时跳过 deps 拉取)
COPY pom.xml ./
# 父 pom 列了 6 个 modules,reactor 启动需要全部 module 目录存在(即便本服务不编)
COPY shopsphere-common/pom.xml shopsphere-common/
COPY shopsphere-api/pom.xml shopsphere-api/
COPY shopsphere-api/user-api/pom.xml shopsphere-api/user-api/
COPY shopsphere-api/product-api/pom.xml shopsphere-api/product-api/
COPY shopsphere-api/order-api/pom.xml shopsphere-api/order-api/
COPY shopsphere-gateway/pom.xml shopsphere-gateway/
COPY shopsphere-user/pom.xml shopsphere-user/
COPY shopsphere-product/pom.xml shopsphere-product/
COPY shopsphere-order/pom.xml shopsphere-order/

# 预拉依赖(失败容忍:某些插件需要源码才能解析,真正编译在下一步)
RUN mvn -pl :shopsphere-gateway -am dependency:go-offline -B -DskipTests || true

# COPY 源码 + 编译(只编译 gateway 链路:common + 自身,不需 api 子模块)
COPY shopsphere-common/src shopsphere-common/src
COPY shopsphere-gateway/src shopsphere-gateway/src
RUN mvn -pl :shopsphere-gateway -am -DskipTests -B clean package

# ---------- runtime ----------
# Eclipse Temurin Alpine 变体不发布 arm64,改 jammy(Ubuntu 22.04 LTS,multiarch)
FROM eclipse-temurin:17-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r app && useradd -r -g app -d /app -s /sbin/nologin app

ENV TZ=UTC \
    JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -Duser.timezone=UTC"

WORKDIR /app
COPY --from=builder /build/shopsphere-gateway/target/shopsphere-gateway.jar app.jar
RUN mkdir -p /app/logs && chown -R app:app /app

USER app
EXPOSE 8080

# 管理端口 8090 绑定 127.0.0.1,容器内健康探针走管理端口
HEALTHCHECK --interval=15s --timeout=5s --retries=10 --start-period=40s \
  CMD wget -qO- http://127.0.0.1:8090/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
