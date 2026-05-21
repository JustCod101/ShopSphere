-- T2.4 库存 TCC 幂等/空回滚日志（docs/api-contracts.md §4.3）
-- 幂等键 = (order_id, product_id, phase)；xid 不入表（仅日志关联，§4.3 拍板）。
-- quantity：TRY 行记录扣减量，Confirm/Cancel 按 order_id 查 TRY 行取 qty。
-- 完整 Seata TCC / 空回滚 / 防悬挂语义在 T3.3 落地（state 列已为 0=空回滚标记预留）。

CREATE TABLE IF NOT EXISTS t_stock_tcc_log (
  id          BIGINT                          NOT NULL COMMENT '雪花 ID',
  order_id    BIGINT                          NOT NULL COMMENT '订单 ID',
  product_id  BIGINT                          NOT NULL COMMENT '商品 ID',
  phase       ENUM('TRY','CONFIRM','CANCEL')  NOT NULL COMMENT 'TCC 阶段',
  state       TINYINT                         NOT NULL DEFAULT 1 COMMENT '1=成功 0=空回滚标记',
  quantity    INT                             NOT NULL COMMENT '该 (order,product) 扣减/回补数量',
  created_at  DATETIME(3)                     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_product_phase (order_id, product_id, phase),
  KEY idx_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存 TCC 幂等/空回滚日志';
