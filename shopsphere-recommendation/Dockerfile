# T5.1 — Python 推荐服务镜像:多阶段 + 非 root + entrypoint(alembic + uvicorn)

# ---------- builder ----------
FROM python:3.11-slim AS builder
WORKDIR /build
RUN apt-get update && apt-get install -y --no-install-recommends gcc g++ \
    && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir --prefix=/install -r requirements.txt

# ---------- runtime ----------
FROM python:3.11-slim

# wget 用于 HEALTHCHECK;tzdata 让 TZ=UTC 生效(APScheduler/log timestamps)
RUN apt-get update && apt-get install -y --no-install-recommends wget tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r app && useradd -r -g app -d /app -s /sbin/nologin app

ENV TZ=UTC

WORKDIR /app
COPY --from=builder /install /usr/local
COPY app/ ./app/
COPY alembic/ ./alembic/
COPY alembic.ini ./
COPY entrypoint.sh ./entrypoint.sh
RUN chmod +x entrypoint.sh && mkdir -p /app/logs && chown -R app:app /app

USER app
EXPOSE 8000

HEALTHCHECK --interval=15s --timeout=5s --retries=10 --start-period=30s \
  CMD wget -qO- http://127.0.0.1:8000/api/recommend/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["./entrypoint.sh"]
