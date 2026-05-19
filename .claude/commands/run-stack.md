启动本地开发环境：
1. docker compose -f docker-compose.infra.yml up -d
2. 等待 nacos 健康检查通过（curl localhost:8848/nacos/v1/console/health/readiness）
3. 按依赖顺序启动：gateway → user → product → order → recommendation
4. 健康检查每个服务的 /actuator/health