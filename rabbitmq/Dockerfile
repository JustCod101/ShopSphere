# RabbitMQ 3.13 + 延迟消息插件（M4：order.payment.timeout 延迟消息依赖此插件）
# 插件 .ez 已 vendor 进仓库（rabbitmq/plugins/），离线可复现，版本匹配 broker 3.13
FROM rabbitmq:3.13-management

# 社区插件放入官方镜像插件目录
COPY plugins/rabbitmq_delayed_message_exchange-3.13.0.ez /opt/rabbitmq/plugins/

# 启用 management + delayed_message_exchange
COPY enabled_plugins /etc/rabbitmq/enabled_plugins
