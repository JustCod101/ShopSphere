#!/bin/sh
# T5.1 — 容器启动入口:先 alembic upgrade,再起 uvicorn。
# alembic 失败 → 容器退出 → compose `restart: on-failure` 重试。
# alembic 幂等,反复跑无害(已应用版本会被跳过)。
set -e

echo "[entrypoint] running alembic upgrade head ..."
alembic upgrade head

echo "[entrypoint] starting uvicorn on 0.0.0.0:8000 ..."
exec uvicorn app.main:app --host 0.0.0.0 --port 8000
