-- 旧版 DATETIME 记录上海时间（UTC+8）。停掉旧版应用后再迁移。
-- 纯 DML 单独成一个事务，避免迁移中断时部分表重复减八小时。
START TRANSACTION;

UPDATE `user`
SET create_time = create_time - INTERVAL 8 HOUR,
    update_time = update_time - INTERVAL 8 HOUR;

UPDATE `conversation`
SET create_time = create_time - INTERVAL 8 HOUR,
    update_time = update_time - INTERVAL 8 HOUR;

UPDATE `message`
SET create_time = create_time - INTERVAL 8 HOUR;

UPDATE `reminder`
SET remind_at = remind_at - INTERVAL 8 HOUR,
    create_time = create_time - INTERVAL 8 HOUR,
    update_time = update_time - INTERVAL 8 HOUR;

UPDATE `notification`
SET read_at = read_at - INTERVAL 8 HOUR,
    create_time = create_time - INTERVAL 8 HOUR;

UPDATE `email_outbox`
SET next_attempt_at = next_attempt_at - INTERVAL 8 HOUR,
    sent_at = sent_at - INTERVAL 8 HOUR,
    create_time = create_time - INTERVAL 8 HOUR,
    update_time = update_time - INTERVAL 8 HOUR;

UPDATE `knowledge_document`
SET next_attempt_at = next_attempt_at - INTERVAL 8 HOUR,
    deleted_at = deleted_at - INTERVAL 8 HOUR,
    create_time = create_time - INTERVAL 8 HOUR,
    update_time = update_time - INTERVAL 8 HOUR;

UPDATE `user_memory`
SET create_time = create_time - INTERVAL 8 HOUR,
    update_time = update_time - INTERVAL 8 HOUR;

COMMIT;
