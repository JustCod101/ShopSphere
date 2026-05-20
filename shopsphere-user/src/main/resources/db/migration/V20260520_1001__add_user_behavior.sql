-- T1.4 行为埋点表（契约 §6.1 / §7 / §8）
-- 自审计兼"对照 MQ 重投"的本地副本；推荐服务由 MQ shopsphere.behavior 接到自有库 shopsphere_reco。
-- 不加 user_id FK：高频写入场景 FK 校验开销不值；userId 由 Gateway+JWT 保证有效。

CREATE TABLE IF NOT EXISTS t_user_behavior (
  id          BIGINT       NOT NULL COMMENT '雪花 ID',
  event_id    CHAR(32)     NOT NULL COMMENT 'UUID 去横线 32hex；与 MQ payload.eventId 同源（消费侧幂等键）',
  user_id     BIGINT       NOT NULL,
  item_id     BIGINT       NOT NULL,
  action_type VARCHAR(16)  NOT NULL COMMENT 'view / cart / order（小写枚举名）',
  extra       JSON         NULL COMMENT '可选扩展上下文（来源页、关键词等）',
  ts          DATETIME(3)  NOT NULL COMMENT '事件时刻；与 MQ payload.ts 同源，UTC 物理时刻',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_event_id (event_id),
  KEY idx_user_ts (user_id, ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户行为（User 自审计；推荐侧由 MQ 接 shopsphere_reco）';
