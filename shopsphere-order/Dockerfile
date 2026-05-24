# T5.1 — shopsphere-order 多阶段构建
# build context 必须为项目根:
#   docker build -f shopsphere-order/Dockerfile -t shopsphere/order .

# ---------- builder ----------
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

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

RUN mvn -pl :shopsphere-order -am dependency:go-offline -B -DskipTests || true

COPY shopsphere-common/src shopsphere-common/src
COPY shopsphere-api shopsphere-api
COPY shopsphere-order/src shopsphere-order/src
RUN mvn -pl :shopsphere-order -am -DskipTests -B clean package

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
COPY --from=builder /build/shopsphere-order/target/shopsphere-order.jar app.jar
RUN mkdir -p /app/logs && chown -R app:app /app

USER app
EXPOSE 8083

HEALTHCHECK --interval=15s --timeout=5s --retries=10 --start-period=60s \
  CMD wget -qO- http://127.0.0.1:8083/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
