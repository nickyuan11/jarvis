-- 2026-11-04：个人知识库的资料表
-- 已经建好库的环境执行这个脚本；全新环境直接执行 schema.sql 即可（已包含这张表）
-- 向量块不在这里，存在 PostgreSQL 的 vector_store 表里，通过元数据里的 documentId 关联
CREATE TABLE IF NOT EXISTS `knowledge_document` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`         BIGINT       NOT NULL COMMENT '所属用户',
    `filename`        VARCHAR(255) NOT NULL COMMENT '上传时的文件名，回答里注明出处时显示',
    `size_bytes`      INT          NOT NULL COMMENT '文件大小（字节）',
    `content`         MEDIUMTEXT   NOT NULL COMMENT '原文（UTF-8），重试、恢复时要重新切块和向量化',
    `content_hash`    CHAR(64)     NOT NULL COMMENT '原文的 SHA-256，防止同一个用户重复上传同一份资料',
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING' COMMENT '状态：PROCESSING 处理中 / READY 可检索 / FAILED 处理失败',
    `chunk_count`     INT          NOT NULL DEFAULT 0 COMMENT '切成了多少块',
    `attempts`        INT          NOT NULL DEFAULT 0 COMMENT '已尝试处理的次数，抢占时兼做版本号',
    `next_attempt_at` DATETIME     NOT NULL COMMENT '下一次可以处理的时间；抢占时推后 10 分钟作为租约',
    `last_error`      VARCHAR(500) DEFAULT NULL COMMENT '最近一次处理失败的原因',
    `deleted_at`      DATETIME     DEFAULT NULL COMMENT '移入回收站的时间，NULL 表示未删除；满 7 天后物理删除',
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
