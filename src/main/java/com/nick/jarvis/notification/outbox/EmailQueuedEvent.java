package com.nick.jarvis.notification.outbox;

import com.nick.jarvis.entity.EmailOutbox;

/**
 * 一封邮件写入了发件箱。
 * 写入它的事务提交之后，EmailOutboxService.onQueued 收到这个事件，立即试发一次。
 */
public record EmailQueuedEvent(EmailOutbox mail) {
}
