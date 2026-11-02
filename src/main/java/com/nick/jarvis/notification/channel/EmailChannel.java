package com.nick.jarvis.notification.channel;

import org.springframework.stereotype.Component;

import com.nick.jarvis.entity.EmailOutbox;
import com.nick.jarvis.entity.User;
import com.nick.jarvis.mapper.UserMapper;
import com.nick.jarvis.notification.outbox.EmailOutboxService;
import com.nick.jarvis.notification.outbox.SmtpMailer;

/**
 * 邮件通知：只发给填了邮箱并打开了邮件提醒的用户。
 *
 * 不在这里直接连邮件服务器，而是写进发件箱（EmailOutboxService）：
 * 先落库，再在后台线程立即试发一次；失败了由定时任务间隔越来越长地重试。
 */
@Component
public class EmailChannel implements NotificationChannel {

    private final SmtpMailer mailer;
    private final UserMapper userMapper;
    private final EmailOutboxService outbox;

    public EmailChannel(SmtpMailer mailer, UserMapper userMapper, EmailOutboxService outbox) {
        this.mailer = mailer;
        this.userMapper = userMapper;
        this.outbox = outbox;
    }

    @Override
    public String name() {
        return "email";
    }

    public boolean configured() {
        return mailer.configured();
    }

    /**
     * 这个方法本身不再是 @Async：查用户、插入一行都只要几毫秒，
     * 同步做完能保证“定时任务处理完这条提醒时，邮件已经在表里了”。
     * 真正耗时的发送在 outbox.sendSoon 里异步进行。
     */
    @Override
    public void send(Long userId, String title, String content) {
        if (!configured()) {
            return;
        }
        User user = userMapper.selectById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEmailReminder())
                || user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }
        EmailOutbox mail = outbox.enqueue(userId, user.getEmail(), title, content);
        outbox.sendSoon(mail);
    }

    /**
     * 同步发送，失败直接抛出异常。发送测试邮件时用：
     * 用户正在页面上等结果，要立刻告诉他成功没有，不适合放进发件箱慢慢重试。
     */
    public void sendTo(String to, String title, String content) {
        mailer.send(to, title, content);
    }
}
