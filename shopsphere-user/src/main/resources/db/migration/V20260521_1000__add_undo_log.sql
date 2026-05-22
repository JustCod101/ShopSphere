-- T3.1 Seata AT 模式回滚日志表（Seata 1.8.0 官方 MySQL 脚本）。
-- 积分发放等以本地 AT 模式参与全局事务时需此表存放回滚快照。

CREATE TABLE IF NOT EXISTS undo_log (
  id            BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT 'increment id',
  branch_id     BIGINT(20)   NOT NULL COMMENT 'branch transaction id',
  xid           VARCHAR(100) NOT NULL COMMENT 'global transaction id',
  context       VARCHAR(128) NOT NULL COMMENT 'undo_log context, such as serialization',
  rollback_info LONGBLOB     NOT NULL COMMENT 'rollback info',
  log_status    INT(11)      NOT NULL COMMENT '0:normal status,1:defense status',
  log_created   DATETIME(6)  NOT NULL COMMENT 'create datetime',
  log_modified  DATETIME(6)  NOT NULL COMMENT 'modify datetime',
  PRIMARY KEY (id),
  UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='AT transaction mode undo table';
