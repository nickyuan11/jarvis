-- 保留用户说“今天”时所在的时区，后台提取记忆时仍可解释这段原话。
-- 旧消息沿用原来的上海时间；助手消息无需填写。
ALTER TABLE message ADD COLUMN time_zone VARCHAR(64) NULL;
