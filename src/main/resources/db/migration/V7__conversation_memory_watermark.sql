-- 2026-11-16：长期记忆的后台提取，需要记住每个会话“已经提取到哪一条消息”（水位线）
ALTER TABLE `conversation`
    ADD COLUMN `memory_extracted_message_id` BIGINT NOT NULL DEFAULT 0
        COMMENT '长期记忆已提取到的消息 id（水位线），只提取 id 比它大的消息' AFTER `title`;

-- 已有的会话：水位线设为当前最后一条消息，只提取这之后的新消息，不回头翻历史对话
UPDATE `conversation` c
SET c.`memory_extracted_message_id` =
        (SELECT COALESCE(MAX(m.`id`), 0) FROM `message` m WHERE m.`conversation_id` = c.`id`);
