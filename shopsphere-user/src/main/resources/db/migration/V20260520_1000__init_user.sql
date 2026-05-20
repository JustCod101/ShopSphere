-- T1.3 初始化：User 服务核心表（docs/architecture.md §2.1）
-- 时间列 DATETIME(3)；Java 侧用 OffsetDateTime + UTC TypeHandler，DB 永远存 UTC 物理时刻（契约 §1.1）。
-- 关键索引：username 唯一（注册冲突依赖）；email/phone 单列检索（后续找回密码）。

CREATE TABLE IF NOT EXISTS t_user (
  id              BIGINT          NOT NULL COMMENT '雪花 ID',
  username        VARCHAR(64)     NOT NULL COMMENT '用户名（登录态）',
  password_hash   VARCHAR(100)    NOT NULL COMMENT 'BCrypt 哈希（strength=10，60 字符；100 留版本切换余量）',
  email           VARCHAR(128)    NULL COMMENT '邮箱',
  phone           VARCHAR(32)     NULL COMMENT '手机号',
  status          TINYINT         NOT NULL DEFAULT 1 COMMENT '1=正常 0=禁用',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username),
  KEY idx_phone (phone),
  KEY idx_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户基本表';

CREATE TABLE IF NOT EXISTS t_user_profile (
  user_id     BIGINT       NOT NULL COMMENT 't_user.id 外键',
  nickname    VARCHAR(64)  NULL COMMENT '昵称（注册时默认 = username）',
  avatar      VARCHAR(255) NULL,
  gender      TINYINT      NULL COMMENT '0=未知 1=男 2=女',
  birthday    DATE         NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id),
  CONSTRAINT fk_profile_user FOREIGN KEY (user_id) REFERENCES t_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户扩展资料';
