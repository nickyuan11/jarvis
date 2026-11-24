-- 已有提醒按原来的上海当地时间重复。新提醒保留创建时的 IANA 时区。
ALTER TABLE reminder ADD COLUMN time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai';
