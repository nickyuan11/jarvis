-- 2026-11-02：邮件发件箱
-- 已经建好库的环境执行这个脚本；全新环境直接执行 schema.sql 即可（已包含这张表）
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
