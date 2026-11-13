-- 2026-11-04：资料移入回收站时，status 改为 DELETED（之前只设置 deleted_at，status 保持 READY）
-- 已执行过的 V4 不再修改，变更写在新的脚本里
ALTER TABLE `knowledge_document`
    MODIFY COLUMN `status` VARCHAR(20) NOT NULL DEFAULT 'PROCESSING'
        COMMENT '状态：PROCESSING 处理中 / READY 可检索 / FAILED 处理失败 / DELETED 在回收站';
-- 把已经在回收站里的资料也改成 DELETED，和新逻辑保持一致
UPDATE `knowledge_document` SET `status` = 'DELETED', `chunk_count` = 0 WHERE `deleted_at` IS NOT NULL;
