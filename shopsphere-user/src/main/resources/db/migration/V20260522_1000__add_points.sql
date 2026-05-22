-- T3.4 用户积分（消费 MQ order.created 发放，契约 §8）
-- t_user_points：每用户一行的积分累计；t_points_log：发放流水 + 消费幂等键（order_id UNIQUE）。
-- 幂等键写入（t_points_log）与积分累加（t_user_points）由 PointsServiceImpl 在同一本地事务内完成。

CREATE TABLE IF NOT EXISTS t_user_points (
  user_id    BIGINT       NOT NULL COMMENT '用户 ID（业务主键，非雪花）',
  points     BIGINT       NOT NULL DEFAULT 0 COMMENT '积分累计余额',
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
  PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户积分余额';

CREATE TABLE IF NOT EXISTS t_points_log (
  id         BIGINT       NOT NULL COMMENT '雪花 ID',
  order_id   BIGINT       NOT NULL COMMENT '来源订单 ID；消费侧幂等键',
  user_id    BIGINT       NOT NULL COMMENT '获得积分的用户 ID',
  points     INT          NOT NULL COMMENT '本次发放积分',
  created_at DATETIME(3)  NOT NULL COMMENT '发放时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_order (order_id),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分发放流水（order_id 唯一保证幂等）';
