-- Jarvis 建表脚本：在 jarvis 库里手动执行一次
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
