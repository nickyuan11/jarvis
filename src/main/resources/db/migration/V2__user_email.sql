-- 2026-11-02：用户表增加邮箱和邮件提醒开关
-- 已经建好库的环境执行这个脚本；全新环境直接执行 schema.sql 即可（已包含这两列）
ALTER TABLE `user`
    ADD COLUMN `email`          VARCHAR(100) DEFAULT NULL COMMENT '接收提醒的邮箱' AFTER `nickname`,
    ADD COLUMN `email_reminder` TINYINT      NOT NULL DEFAULT 0 COMMENT '提醒到点时是否发送邮件：0 否，1 是' AFTER `email`;
