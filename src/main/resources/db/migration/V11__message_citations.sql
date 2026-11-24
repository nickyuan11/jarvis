-- 正文和引用在同一次 INSERT 中写入。旧消息没有来源记录，保留 NULL。
ALTER TABLE message ADD COLUMN citations JSON NULL COMMENT '本条回复实际引用的编号、资料 id 和原文范围';
