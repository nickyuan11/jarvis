-- Jarvis 业务库（MySQL）的完整表结构，由 Flyway 在应用启动时自动执行。
--
-- 为什么叫 V5、而且是一个完整的建表脚本？
-- 接入 Flyway 之前，表结构变更靠手动执行 V2～V5 四个脚本（已存档到 docs/migrations-before-flyway/）。
-- 接入时把它们“压缩”（squash）成这一个完整脚本，版本号沿用 5，和开发日志里的记录对得上：
-- - 全新的空库：Flyway 执行这个脚本，建好所有表；
-- - 已经手动执行过 V2～V5 的库：application.yml 里 baseline-version 设为 5，Flyway 只记下“已经是第 5 版”，不会执行它。
--
-- 以后的表结构变更：新建 V6__xxx.sql、V7__xxx.sql……不要再修改这个文件，也不要修改任何已经执行过的脚本
-- （Flyway 会校验已执行脚本的内容，被改过就拒绝启动）。
-- 约定：表名、字段名用下划线（snake_case），Java 里用驼峰（camelCase），MyBatis-Plus 会自动对应

CREATE TABLE IF NOT EXISTS `user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`    VARCHAR(50)  NOT NULL COMMENT '登录名',
    `password`    VARCHAR(100) NOT NULL COMMENT '加密后的密码，绝不存明文',
    `nickname`    VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    `email`          VARCHAR(100) DEFAULT NULL COMMENT '接收提醒的邮箱',
    `email_reminder` TINYINT      NOT NULL DEFAULT 0 COMMENT '提醒到点时是否发送邮件：0 否，1 是',
    `create_time` DATETIME     NOT NULL COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常，1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户';

CREATE TABLE IF NOT EXISTS `conversation` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NOT NULL COMMENT '所属用户',
    `title`       VARCHAR(100) NOT NULL DEFAULT '新对话' COMMENT '会话标题',
    `create_time` DATETIME     NOT NULL COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL COMMENT '最后活跃时间，用于会话列表排序',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_update` (`user_id`, `update_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '会话';

CREATE TABLE IF NOT EXISTS `message` (
    `id`                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键，自增，同一会话内可用来排序',
    `conversation_id`   BIGINT      NOT NULL COMMENT '所属会话',
    `role`              VARCHAR(20) NOT NULL COMMENT '角色：user / assistant / system / tool',
    `content`           TEXT        NOT NULL COMMENT '消息内容',
    `model`             VARCHAR(50) DEFAULT NULL COMMENT '生成这条回复的模型，用户消息为空',
    `prompt_tokens`     INT         DEFAULT NULL COMMENT '输入 token 数',
    `completion_tokens` INT         DEFAULT NULL COMMENT '输出 token 数',
    `create_time`       DATETIME    NOT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_conversation_id` (`conversation_id`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '消息';

-- ---------------------------------------------------------------------
-- 第 3 周：提醒事项、通知
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `reminder` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NOT NULL COMMENT '所属用户',
    `content`     VARCHAR(500) NOT NULL COMMENT '提醒内容',
    `remind_at`   DATETIME     NOT NULL COMMENT '下一次提醒时间；循环提醒每次触发后往后推',
    `repeat_type` VARCHAR(20)  NOT NULL DEFAULT 'NONE' COMMENT '重复方式：NONE / DAILY / WEEKLY / MONTHLY',
    `status`      VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING 待提醒 / DONE 已提醒 / CANCELLED 已取消',
    `create_time` DATETIME     NOT NULL COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 定时任务每 30 秒查一次“到期的待提醒记录”，靠这个索引避免全表扫描
    KEY `idx_status_remind_at` (`status`, `remind_at`),
    KEY `idx_user_status` (`user_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '提醒事项';

CREATE TABLE IF NOT EXISTS `notification` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NOT NULL COMMENT '接收用户',
    `content`     VARCHAR(500) NOT NULL COMMENT '通知内容',
    `read_at`     DATETIME     DEFAULT NULL COMMENT '已读时间，NULL 表示未读',
    `create_time` DATETIME     NOT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_read` (`user_id`, `read_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '站内通知';

CREATE TABLE IF NOT EXISTS `email_outbox` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`         BIGINT       DEFAULT NULL COMMENT '关联用户，只用于排查；发给谁以 to_address 为准',
    `to_address`      VARCHAR(100) NOT NULL COMMENT '收件地址，写入发件箱时的快照',
    `subject`         VARCHAR(600) NOT NULL COMMENT '邮件主题',
    `content`         TEXT         NOT NULL COMMENT '邮件正文',
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING 待发送 / SENT 已发送 / FAILED 已放弃',
    `attempts`        INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数，同时充当抢占时的版本号',
    `next_attempt_at` DATETIME     NOT NULL COMMENT '下一次可以尝试的时间；抢占时先推后 5 分钟作为租约',
    `last_error`      VARCHAR(500) DEFAULT NULL COMMENT '最近一次失败的原因',
    `sent_at`         DATETIME     DEFAULT NULL COMMENT '发送成功的时间',
    `create_time`     DATETIME     NOT NULL COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 定时任务每分钟查一次“到期的待发送邮件”
    KEY `idx_status_next_attempt` (`status`, `next_attempt_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '邮件发件箱';

-- ---------------------------------------------------------------------
-- 第 4 周：个人知识库
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `knowledge_document` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`         BIGINT       NOT NULL COMMENT '所属用户',
    `filename`        VARCHAR(255) NOT NULL COMMENT '上传时的文件名，回答里注明出处时显示',
    `size_bytes`      INT          NOT NULL COMMENT '文件大小（字节）',
    `content`         MEDIUMTEXT   NOT NULL COMMENT '原文（UTF-8），重试、恢复时要重新切块和向量化',
    `content_hash`    CHAR(64)     NOT NULL COMMENT '原文的 SHA-256，防止同一个用户重复上传同一份资料',
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING' COMMENT '状态：PROCESSING 处理中 / READY 可检索 / FAILED 处理失败 / DELETED 在回收站',
    `chunk_count`     INT          NOT NULL DEFAULT 0 COMMENT '切成了多少块',
    `attempts`        INT          NOT NULL DEFAULT 0 COMMENT '已尝试处理的次数，抢占时兼做版本号',
    `next_attempt_at` DATETIME     NOT NULL COMMENT '下一次可以处理的时间；抢占时推后 10 分钟作为租约',
    `last_error`      VARCHAR(500) DEFAULT NULL COMMENT '最近一次处理失败的原因',
    `deleted_at`      DATETIME     DEFAULT NULL COMMENT '移入回收站的时间，NULL 表示未删除；满 7 天后物理删除。和 status = DELETED 总是在同一条 UPDATE 里一起修改',
    `create_time`     DATETIME     NOT NULL COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 同一个用户不能重复上传同一份资料；并发上传时由它兜底
    UNIQUE KEY `uk_user_hash` (`user_id`, `content_hash`),
    -- 资料列表、回收站：按用户查
    KEY `idx_user_deleted` (`user_id`, `deleted_at`),
    -- 定时任务：找到期该处理的
    KEY `idx_status_next_attempt` (`status`, `next_attempt_at`),
    -- 定时任务：找回收站里满 7 天的
    KEY `idx_deleted_at` (`deleted_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '知识库资料';
