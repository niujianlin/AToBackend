-- ============================================================
-- 阿拓 v1.0.0 账号/登录域 DDL（冻结版）
-- 依据：docs/atot-backend-decisions.md
-- 环境：MySQL 8.0 / InnoDB / utf8mb4
-- 约定：
--   1. 主键与 user_id 均为雪花 int64，不使用 AUTO_INCREMENT
--   2. 时间字段统一存 UTC
--   3. identifier 存 AES-256-GCM 信封密文，等值查询走 identifier_hash
--   4. refresh_token 只存 SHA-256 哈希，明文不落库
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1. user_info 用户信息表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `user_info`;
CREATE TABLE `user_info` (
  `user_id`        bigint       NOT NULL               COMMENT '雪花ID，用户唯一标识（HTTP 序列化为字符串）',
  `nickname`       varchar(32)  NOT NULL               COMMENT '昵称',
  `avatar`         varchar(255) NOT NULL DEFAULT ''    COMMENT '头像URL，空串=无头像',
  `gender`         tinyint      NOT NULL DEFAULT 0     COMMENT '0未知 1男 2女',
  `bio`            varchar(255) NOT NULL DEFAULT ''    COMMENT '个人简介',
  `account_status` tinyint      NOT NULL DEFAULT 1     COMMENT '1正常 0封禁 2异常 3已注销',
  `deleted_at`     datetime     DEFAULT NULL           COMMENT '注销时间，正常为NULL',
  `created_at`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
  `updated_at`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '资料修改时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户信息表';

-- ------------------------------------------------------------
-- 2. auth_credential 登录凭证表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `auth_credential`;
CREATE TABLE `auth_credential` (
  `id`              bigint       NOT NULL               COMMENT '雪花ID',
  `user_id`         bigint       NOT NULL               COMMENT '关联 user_info.user_id',
  `auth_type`       varchar(32)  NOT NULL               COMMENT 'wechat / apple / phone_code',
  `identifier`      varchar(512) NOT NULL DEFAULT ''    COMMENT 'AES-256-GCM信封密文（key_id:iv:ct:tag base64），注销/解绑后清空',
  `identifier_hash` varchar(64)  NOT NULL               COMMENT 'HMAC-SHA256(auth_type:明文identifier)，等值查询与唯一约束',
  `ext_info`        varchar(512) NOT NULL DEFAULT ''    COMMENT '第三方附加信息JSON（Apple邮箱、微信昵称头像等）',
  `is_invalid`      tinyint      NOT NULL DEFAULT 0     COMMENT '0正常 1已失效（解绑/注销）',
  `created_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间',
  `updated_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_type_identifier_hash` (`auth_type`,`identifier_hash`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录凭证表';

-- ------------------------------------------------------------
-- 3. auth_session 登录会话表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `auth_session`;
CREATE TABLE `auth_session` (
  `id`                 bigint       NOT NULL            COMMENT '雪花ID，对外 session_id',
  `user_id`            bigint       NOT NULL            COMMENT '关联 user_info.user_id',
  `credential_id`      bigint       DEFAULT NULL        COMMENT '关联 auth_credential.id，可为NULL',
  `refresh_token_hash` char(64)     NOT NULL            COMMENT 'refresh_token 的 SHA-256 十六进制',
  `platform`           varchar(16)  NOT NULL DEFAULT 'mobile' COMMENT 'mobile / pad / web / desktop',
  `auth_type`          varchar(32)  NOT NULL            COMMENT 'wechat / apple / phone_code',
  `device_id`          varchar(128) NOT NULL DEFAULT '' COMMENT '设备唯一标识',
  `device_name`        varchar(128) NOT NULL DEFAULT '' COMMENT '设备名称，如 iPhone 15',
  `ip_addr`            varchar(64)  NOT NULL DEFAULT '' COMMENT '登录IP',
  `expire_at`          datetime     NOT NULL            COMMENT '会话过期时间',
  `revoked`            tinyint      NOT NULL DEFAULT 0  COMMENT '0正常 1已吊销',
  `last_refresh_at`    datetime     DEFAULT NULL        COMMENT '最后一次刷新时间',
  `created_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
  `updated_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refresh_token_hash` (`refresh_token_hash`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_user_platform` (`user_id`,`platform`,`revoked`),
  KEY `idx_expire_revoked` (`expire_at`,`revoked`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录会话表';

-- ------------------------------------------------------------
-- 关键查询对应的索引说明
-- ------------------------------------------------------------
-- 登录：SELECT ... FROM auth_credential WHERE auth_type=? AND identifier_hash=?   → uk_type_identifier_hash
-- 绑定冲突检测：同上
-- 账号凭证列表：WHERE user_id=? AND is_invalid=0                                  → idx_user_id
-- 同端踢除：UPDATE auth_session ... WHERE user_id=? AND platform=? AND revoked=0   → idx_user_platform
-- 刷新：SELECT ... WHERE refresh_token_hash=? FOR UPDATE                           → uk_refresh_token_hash
-- 设备列表：WHERE user_id=? AND revoked=0 AND expire_at>now()                      → idx_user_platform
-- 过期清理：DELETE WHERE expire_at<?  （分批）                                      → idx_expire_revoked

-- ------------------------------------------------------------
-- 初始化种子（可选，生产请删除）
-- ------------------------------------------------------------
-- INSERT INTO user_info(user_id,nickname) VALUES (100001,'阿拓用户100001');
