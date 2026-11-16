-- 2026-11-13：长期记忆。跨会话记住关于用户本人、长期有效的信息
-- 由 Flyway 在应用启动时自动执行
CREATE TABLE `user_memory` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`         BIGINT       NOT NULL COMMENT '所属用户',
    `content`         VARCHAR(100) NOT NULL COMMENT '记忆内容，一两句话，例如“对花生过敏”',
    `type`            VARCHAR(20)  NOT NULL COMMENT '类型：PREFERENCE 对 Jarvis 回答方式的要求（每次都遵守）/ FACT 关于用户的事实（相关时参考）',
    `source`          VARCHAR(20)  NOT NULL COMMENT '来源：EXPLICIT 用户明确要求记住 / EXTRACTED 后台从对话中提取',
    `conversation_id` BIGINT       DEFAULT NULL COMMENT '来自哪个会话，排查和页面展示用',
    `create_time`     DATETIME     NOT NULL COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL COMMENT '更新时间。交给模型时带上它，让模型判断记忆新旧',
    PRIMARY KEY (`id`),
    KEY `idx_user_update` (`user_id`, `update_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户的长期记忆';
