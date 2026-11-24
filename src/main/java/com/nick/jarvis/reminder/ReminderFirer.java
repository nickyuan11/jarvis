package com.nick.jarvis.reminder;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.nick.jarvis.entity.Reminder;
import com.nick.jarvis.mapper.ReminderMapper;
import com.nick.jarvis.notification.channel.NotificationDispatcher;

/**
 * 触发一条到期的提醒：抢占提醒 + 分发通知，在同一个事务里完成。
 *
 * 为什么单独成一个类？@Transactional 和 @Async 一样靠代理对象生效。
 * 如果写在 ReminderService 里、由 fireDue 用 this 调用，会绕过代理，事务根本不会开启。
 *
 * 事务里只做写数据库、能回滚的事：抢占提醒、写站内通知、写入邮件发件箱。
 * 任何一步失败都整体回滚，提醒保持“待提醒”，下一轮（30 秒后）重新触发，
 * 不会出现“提醒已经标记完成，邮件却没进发件箱”的情况。
 * 真正发邮件要等事务提交之后（见 EmailOutboxService.onQueued），因为发出去的邮件收不回来。
 */
@Component
public class ReminderFirer {

    private final ReminderMapper reminderMapper;
    private final NotificationDispatcher dispatcher;

    public ReminderFirer(ReminderMapper reminderMapper, NotificationDispatcher dispatcher) {
        this.reminderMapper = reminderMapper;
        this.dispatcher = dispatcher;
    }

    /**
     * @return true 表示抢到并分发了通知；false 表示被别人抢先了，什么也没做
     */
    @Transactional
    public boolean fire(Reminder reminder, LocalDateTime now) {
        if (!claim(reminder, now)) {
            return false;
        }
        dispatcher.dispatch(reminder.getUserId(),
                "⏰ 提醒：" + reminder.getContent(),
                "提醒时间：" + ReminderService.format(reminder.getRemindAt(), ZoneId.of(reminder.getTimeZone())) + "\n事项：" + reminder.getContent()
                        + "\n\n—— Jarvis");
        return true;
    }

    /**
     * “抢占”一条提醒：只有更新成功（影响 1 行）的那次才发送通知。
     *
     * 条件里带上 status = PENDING 和原来的 remind_at，相当于乐观锁：
     * 如果以后部署了多台服务器，几台同时扫到同一条提醒，也只有一台能更新成功，用户不会收到重复提醒。
     */
    private boolean claim(Reminder reminder, LocalDateTime now) {
        RepeatType repeat = RepeatType.parse(reminder.getRepeatType());
        LambdaUpdateWrapper<Reminder> update = new LambdaUpdateWrapper<Reminder>()
                .set(Reminder::getUpdateTime, now)
                .eq(Reminder::getId, reminder.getId())
                .eq(Reminder::getStatus, Reminder.STATUS_PENDING)
                .eq(Reminder::getRemindAt, reminder.getRemindAt());
        if (repeat == RepeatType.NONE) {
            update.set(Reminder::getStatus, Reminder.STATUS_DONE);
        } else {
            // 循环提醒：状态保持 PENDING，把提醒时间推到下一个周期
            update.set(Reminder::getRemindAt, repeat.nextAfter(reminder.getRemindAt(), now, ZoneId.of(reminder.getTimeZone())));
        }
        return reminderMapper.update(null, update) == 1;
    }
}
