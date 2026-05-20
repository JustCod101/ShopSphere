-- T2.1 初始化：Product 服务核心表（docs/architecture.md §2.2 / docs/api-contracts.md §6.2 §4.3）
-- 时间列 DATETIME(3)；Java 侧 OffsetDateTime + UTC TypeHandler，DB 永远存 UTC 物理时刻（契约 §1.1）。
-- status 列由 MyBatis-Plus 全局逻辑删除接管（1=有效 / 0=已删除），t_product_stock 无 status 不参与。

-- t_category：扁平类目，parent_id=0 为根；sort 同层排序
CREATE TABLE IF NOT EXISTS t_category (
  id          BIGINT      NOT NULL COMMENT '雪花 ID',
  name        VARCHAR(64) NOT NULL,
  parent_id   BIGINT      NOT NULL DEFAULT 0 COMMENT '0=根类目',
  sort        INT         NOT NULL DEFAULT 0 COMMENT '同层排序，升序',
  status      TINYINT     NOT NULL DEFAULT 1 COMMENT '1=有效 0=逻辑删除',
  PRIMARY KEY (id),
  KEY idx_parent (parent_id),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品类目';

-- t_product：商品主表
CREATE TABLE IF NOT EXISTS t_product (
  id          BIGINT          NOT NULL COMMENT '雪花 ID',
  name        VARCHAR(128)    NOT NULL,
  category_id BIGINT          NOT NULL,
  price       DECIMAL(10,2)   NOT NULL,
  main_image  VARCHAR(255)    NULL,
  description TEXT            NULL,
  status      TINYINT         NOT NULL DEFAULT 1 COMMENT '1=上架 0=逻辑删除',
  created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_category_status (category_id, status),
  KEY idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品';

-- t_product_stock：库存（拆出便于 TCC 事务、热点行行锁）
-- 无 status 列：不参与 MP 全局逻辑删除（缺失列 MP 自动跳过）
CREATE TABLE IF NOT EXISTS t_product_stock (
  product_id   BIGINT NOT NULL COMMENT '与 t_product.id 同源',
  stock        INT    NOT NULL DEFAULT 0 COMMENT '真实总量',
  locked_stock INT    NOT NULL DEFAULT 0 COMMENT 'TCC-Try 预留（契约 §4.3）',
  version      INT    NOT NULL DEFAULT 0 COMMENT '乐观锁',
  PRIMARY KEY (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品库存（独立表）';

-- ============================================================================
-- 种子数据：5 类目 + 20 商品 + 20 库存行（stock=100, locked_stock=0）
-- ID 固定，便于 TCC / E2E 测试引用具体 id（Phase 5 治理时清理）
-- ============================================================================
INSERT INTO t_category (id, name, parent_id, sort, status) VALUES
  (1001, '数码',     0, 10, 1),
  (1002, '服饰',     0, 20, 1),
  (1003, '家居',     0, 30, 1),
  (1004, '食品',     0, 40, 1),
  (1005, '图书',     0, 50, 1);

INSERT INTO t_product (id, name, category_id, price, main_image, description, status) VALUES
  (2001, 'MacBook Pro 14',     1001, 14999.00, 'https://cdn.shop/p2001.jpg', '高性能笔记本，M3 芯片。', 1),
  (2002, 'iPhone 15 Pro',      1001,  7999.00, 'https://cdn.shop/p2002.jpg', '钛金属机身。',           1),
  (2003, 'AirPods Pro',        1001,  1899.00, 'https://cdn.shop/p2003.jpg', '主动降噪。',             1),
  (2004, 'iPad Air',           1001,  4799.00, 'https://cdn.shop/p2004.jpg', '轻薄平板。',             1),
  (2005, '机械键盘 87',         1001,   599.00, 'https://cdn.shop/p2005.jpg', '青轴热插拔。',           1),
  (2006, '纯棉 T 恤',           1002,    99.00, 'https://cdn.shop/p2006.jpg', '基础款。',               1),
  (2007, '羽绒服',              1002,   799.00, 'https://cdn.shop/p2007.jpg', '90% 白鸭绒。',           1),
  (2008, '牛仔裤',              1002,   299.00, 'https://cdn.shop/p2008.jpg', '直筒。',                 1),
  (2009, '休闲运动鞋',          1002,   499.00, 'https://cdn.shop/p2009.jpg', '透气网面。',             1),
  (2010, '智能台灯',            1003,   289.00, 'https://cdn.shop/p2010.jpg', '色温可调。',             1),
  (2011, '记忆棉枕',            1003,   159.00, 'https://cdn.shop/p2011.jpg', '颈椎友好。',             1),
  (2012, '空气炸锅 5L',         1003,   449.00, 'https://cdn.shop/p2012.jpg', '无油健康。',             1),
  (2013, '蓝山咖啡豆 250g',     1004,    89.00, 'https://cdn.shop/p2013.jpg', '中度烘焙。',             1),
  (2014, '盒装牛奶 1L*12',      1004,   119.00, 'https://cdn.shop/p2014.jpg', '保质 6 个月。',          1),
  (2015, '坚果礼盒',            1004,   199.00, 'https://cdn.shop/p2015.jpg', '8 种混装。',             1),
  (2016, '《设计模式》',         1005,    79.00, 'https://cdn.shop/p2016.jpg', 'GoF 经典。',             1),
  (2017, '《深入理解 JVM》',     1005,    99.00, 'https://cdn.shop/p2017.jpg', '周志明著。',             1),
  (2018, '《算法导论》',         1005,   139.00, 'https://cdn.shop/p2018.jpg', 'MIT 教材。',             1),
  (2019, '《代码大全》',         1005,   119.00, 'https://cdn.shop/p2019.jpg', 'Steve McConnell。',     1),
  (2020, '《重构》',             1005,    99.00, 'https://cdn.shop/p2020.jpg', 'Martin Fowler。',       1);

INSERT INTO t_product_stock (product_id, stock, locked_stock, version)
SELECT id, 100, 0, 0 FROM t_product;
