package com.nick.jarvis.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每 30 秒检查一次有没有到期的提醒。
 * fixedDelay：上一次执行结束后再等 30 秒，不会出现两次执行重叠的情况。
 */
@Component
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ReminderService reminderService;

    public ReminderScheduler(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public void fireDueReminders() {
        try {
            reminderService.fireDue();
        } catch (Exception e) {
            // 定时任务里的异常要自己兜住，避免一次失败影响后续执行，也方便排查
            log.error("Failed to fire due reminders", e);
        }
    }
}
