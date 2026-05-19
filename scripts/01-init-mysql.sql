-- ShopSphere 基础设施库初始化（T0.2）
-- 对齐 docs/api-contracts.md §5（库名）/ §7（推荐自有库 shopsphere_reco）
-- MySQL initdb 按文件名字典序执行：01(建库) → 02(nacos schema) → 03(seata server 表)
-- 全部 utf8mb4 / utf8mb4_unicode_ci

CREATE DATABASE IF NOT EXISTS `shopsphere_user`
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `shopsphere_product`
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `shopsphere_order`
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 推荐服务自有库（§7 事件驱动方案拍板，推荐服务不直连 User 库）
CREATE DATABASE IF NOT EXISTS `shopsphere_reco`
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Seata Server store.mode=db 用（global_table/branch_table/lock_table/distributed_lock）
CREATE DATABASE IF NOT EXISTS `shopsphere_seata`
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Nacos 配置/服务持久化（MySQL，避免 derby 单机不可复现）
CREATE DATABASE IF NOT EXISTS `nacos_config`
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
