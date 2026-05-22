-- T3.2 订单收货地址列（api-contracts §6.3 create 入参 addressId）。
-- t_order 由 T3.1 V20260521_1000 建、尚无数据，加 NOT NULL 列安全。
-- DOWN: ALTER TABLE t_order DROP COLUMN address_id;
ALTER TABLE t_order ADD COLUMN address_id BIGINT NOT NULL COMMENT '收货地址 ID' AFTER user_id;
