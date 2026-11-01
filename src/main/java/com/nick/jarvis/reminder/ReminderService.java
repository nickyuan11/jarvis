package com.nick.jarvis.reminder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.nick.jarvis.entity.Reminder;
import com.nick.jarvis.mapper.ReminderMapper;
import com.nick.jarvis.notification.NotificationService;
import com.nick.jarvis.support.JarvisTime;

/**
 * 提醒事项的业务逻辑。
 * 校验失败时抛出 IllegalArgumentException，消息写得让大模型也能看懂，
 * 工具层会把消息原样交给大模型，由它向用户解释或者修正参数后重试。
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    public static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_CONTENT = 500;
    private static final int MAX_PENDING_PER_USER = 100;

    private final ReminderMapper reminderMapper;
    private final NotificationService notificationService;
    private final JarvisTime time;

    public ReminderService(ReminderMapper reminderMapper, NotificationService notificationService, JarvisTime time) {
        this.reminderMapper = reminderMapper;
        this.notificationService = notificationService;
        this.time = time;
    }

    public Reminder create(Long userId, String content, LocalDateTime remindAt, RepeatType repeatType) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("提醒内容不能为空");
        }
        if (content.strip().length() > MAX_CONTENT) {
            throw new IllegalArgumentException("提醒内容不能超过 " + MAX_CONTENT + " 个字");
        }
        LocalDateTime now = time.now();
        if (remindAt == null || remindAt.isBefore(now.minusMinutes(1))) {
            throw new IllegalArgumentException("提醒时间 " + format(remindAt) + " 已经过去了，当前时间是 " + format(now));
        }
        Long pending = reminderMapper.selectCount(new LambdaQueryWrapper<Reminder>()
                .eq(Reminder::getUserId, userId)
                .eq(Reminder::getStatus, Reminder.STATUS_PENDING));
        if (pending >= MAX_PENDING_PER_USER) {
            throw new IllegalArgumentException("待提醒事项已达上限 " + MAX_PENDING_PER_USER + " 条，请先取消一些");
        }

        Reminder reminder = new Reminder();
        reminder.setUserId(userId);
        reminder.setContent(content.strip());
        reminder.setRemindAt(remindAt.withSecond(0).withNano(0));
        reminder.setRepeatType(repeatType.name());
        reminder.setStatus(Reminder.STATUS_PENDING);
        reminderMapper.insert(reminder);
        return reminder;
    }

    public List<Reminder> listPending(Long userId) {
        return reminderMapper.selectList(new LambdaQueryWrapper<Reminder>()
                .eq(Reminder::getUserId, userId)
                .eq(Reminder::getStatus, Reminder.STATUS_PENDING)
                .orderByAsc(Reminder::getRemindAt));
    }

    /** 最近一段时间内已经提醒过或已取消的事项，最新的在前 */
    public List<Reminder> listRecentlyFinished(Long userId, LocalDateTime since) {
        return reminderMapper.selectList(new LambdaQueryWrapper<Reminder>()
                .eq(Reminder::getUserId, userId)
                .in(Reminder::getStatus, Reminder.STATUS_DONE, Reminder.STATUS_CANCELLED)
                .ge(Reminder::getUpdateTime, since)
                .orderByDesc(Reminder::getUpdateTime)
                .last("LIMIT 20"));
    }

    public Reminder cancel(Long userId, Long reminderId) {
        Reminder reminder = reminderMapper.selectById(reminderId);
        if (reminder == null || !userId.equals(reminder.getUserId())) {
            throw new IllegalArgumentException("找不到编号为 " + reminderId + " 的提醒");
        }
        if (!Reminder.STATUS_PENDING.equals(reminder.getStatus())) {
            throw new IllegalArgumentException("提醒 #" + reminderId + " 已经不是待提醒状态，无需取消");
        }
        reminderMapper.update(null, new LambdaUpdateWrapper<Reminder>()
                .set(Reminder::getStatus, Reminder.STATUS_CANCELLED)
                .set(Reminder::getUpdateTime, time.now())
                .eq(Reminder::getId, reminderId)
                .eq(Reminder::getStatus, Reminder.STATUS_PENDING));
        reminder.setStatus(Reminder.STATUS_CANCELLED);
        return reminder;
    }

    /**
     * 处理所有到期的提醒，由定时任务调用。返回本次实际发出的提醒数量。
     */
    public int fireDue() {
        LocalDateTime now = time.now();
        List<Reminder> due = reminderMapper.selectList(new LambdaQueryWrapper<Reminder>()
                .eq(Reminder::getStatus, Reminder.STATUS_PENDING)
                .le(Reminder::getRemindAt, now)
                .orderByAsc(Reminder::getRemindAt)
                .last("LIMIT 100"));

        int fired = 0;
        for (Reminder reminder : due) {
            if (claim(reminder, now)) {
                notificationService.send(reminder.getUserId(), "⏰ 提醒：" + reminder.getContent());
                fired++;
            }
        }
        if (fired > 0) {
            log.info("Fired {} reminders", fired);
        }
        return fired;
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
            update.set(Reminder::getRemindAt, repeat.nextAfter(reminder.getRemindAt(), now));
        }
        return reminderMapper.update(null, update) == 1;
    }

    public static String format(LocalDateTime dateTime) {
        return dateTime == null ? "（空）" : dateTime.format(FORMAT);
    }

    /** 给大模型看的一行描述 */
    public static String describe(Reminder r) {
        RepeatType repeat = RepeatType.parse(r.getRepeatType());
        String repeatText = repeat == RepeatType.NONE ? "" : "，" + repeat.label() + "重复";
        String statusText = switch (r.getStatus()) {
            case Reminder.STATUS_DONE -> "（已于 " + format(r.getUpdateTime()) + " 提醒）";
            case Reminder.STATUS_CANCELLED -> "（已于 " + format(r.getUpdateTime()) + " 取消）";
            default -> "";
        };
        return "#" + r.getId() + " " + format(r.getRemindAt()) + " " + r.getContent() + repeatText + statusText;
    }
}
