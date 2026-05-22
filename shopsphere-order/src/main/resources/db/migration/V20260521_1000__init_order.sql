-- T3.1 订单服务核心表（docs/architecture.md §2.3 / docs/api-contracts.md §6.3）
-- 雪花 PK 由 MyBatis-Plus ASSIGN_ID 提供（无 AUTO_INCREMENT）；时间列 DATETIME(3) UTC。
-- undo_log 见 V20260521_1001__add_undo_log.sql。

CREATE TABLE IF NOT EXISTS t_order (
  id            BIGINT          NOT NULL COMMENT '雪花 ID',
  order_no      VARCHAR(32)     NOT NULL COMMENT '订单号（业务可读唯一）',
  user_id       BIGINT          NOT NULL COMMENT '下单用户 ID',
  total_amount  DECIMAL(12,2)   NOT NULL COMMENT '订单总额',
  status        TINYINT         NOT NULL DEFAULT 0 COMMENT '0=CREATED 1=PAID 2=SHIPPED 3=COMPLETED 4=CANCELLED',
  remark        VARCHAR(255)             DEFAULT NULL COMMENT '订单备注',
  pay_expire_at DATETIME(3)              DEFAULT NULL COMMENT '支付超时时间（UTC）',
  paid_at       DATETIME(3)              DEFAULT NULL COMMENT '支付完成时间（UTC）',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单主表';

CREATE TABLE IF NOT EXISTS t_order_item (
  id           BIGINT         NOT NULL COMMENT '雪花 ID',
  order_id     BIGINT         NOT NULL COMMENT '订单 ID',
  product_id   BIGINT         NOT NULL COMMENT '商品 ID',
  product_name VARCHAR(255)   NOT NULL COMMENT '下单时商品名快照',
  price        DECIMAL(12,2)  NOT NULL COMMENT '下单时单价快照',
  quantity     INT            NOT NULL COMMENT '购买数量',
  PRIMARY KEY (id),
  KEY idx_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细';

CREATE TABLE IF NOT EXISTS t_order_request (
  id         BIGINT       NOT NULL COMMENT '雪花 ID',
  user_id    BIGINT       NOT NULL COMMENT '用户 ID',
  request_id VARCHAR(64)  NOT NULL COMMENT '客户端幂等 ID（X-Request-Id）',
  order_id   BIGINT                DEFAULT NULL COMMENT '首次成功创建的订单 ID',
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_req (user_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='下单幂等表（S5）';

CREATE TABLE IF NOT EXISTS t_local_message (
  id            BIGINT        NOT NULL COMMENT '雪花 ID',
  biz_key       VARCHAR(64)   NOT NULL COMMENT '业务键（如订单 ID）',
  exchange      VARCHAR(128)  NOT NULL COMMENT 'MQ exchange',
  routing_key   VARCHAR(128)  NOT NULL COMMENT 'MQ routing key',
  payload       JSON          NOT NULL COMMENT '消息体',
  status        TINYINT       NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=SENT 2=CONFIRMED 3=FAILED',
  retry_count   INT           NOT NULL DEFAULT 0 COMMENT '已重试次数',
  next_retry_at DATETIME(3)            DEFAULT NULL COMMENT '下次重试时间（UTC）',
  created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
  updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
  PRIMARY KEY (id),
  KEY idx_status_next (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表（C3 outbox，与建单同本地事务）';
