-- T3.3 修正 t_product_stock.stock 列语义注释。
-- §4.3 的 TCC：Try `stock-=q, locked_stock+=q` —— stock 是「可售库存池」而非「真实总量」。
-- 真实总量 = stock + locked_stock。Redis stock:product:{id} 镜像 stock。
ALTER TABLE t_product_stock
  MODIFY COLUMN stock INT NOT NULL DEFAULT 0
  COMMENT '可售库存池（available；真实总量 = stock + locked_stock）';
