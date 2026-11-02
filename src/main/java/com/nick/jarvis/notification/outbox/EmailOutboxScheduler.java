package com.nick.jarvis.notification.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每分钟扫一次发件箱，重试到期的邮件。
 * 最短的重试间隔是 1 分钟，扫得再勤也没有意义。
 */
@Component
public class EmailOutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(EmailOutboxScheduler.class);

    private final EmailOutboxService outboxService;

    public EmailOutboxScheduler(EmailOutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void retryDueEmails() {
        try {
            outboxService.processDue();
        } catch (Exception e) {
            log.error("Failed to process email outbox", e);
        }
    }
}
