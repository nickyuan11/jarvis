package com.nick.jarvis.notification.channel;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.nick.jarvis.entity.User;
import com.nick.jarvis.mapper.UserMapper;

/**
 * 邮件通知：只发给填了邮箱并打开了邮件提醒的用户。
 *
 * @Async：在单独的线程池里发送。连接邮件服务器可能要好几秒，
 * 如果同步发送，定时任务会被卡住，其他用户的提醒也会跟着延迟。
 */
@Component
public class EmailChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);
    private static final int MAX_ATTEMPTS = 3;

    private final JavaMailSender mailSender;
    private final UserMapper userMapper;
    private final String from;

    public EmailChannel(JavaMailSender mailSender, UserMapper userMapper,
                        @Value("${spring.mail.username:}") String from) {
        this.mailSender = mailSender;
        this.userMapper = userMapper;
        this.from = from;
    }

    @Override
    public String name() {
        return "email";
    }

    public boolean configured() {
        return from != null && !from.isBlank();
    }

    @Async
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
        // 网络超时这类“临时性”错误，过几秒重试通常就能成功。
        // 最多尝试 3 次，间隔逐渐拉长（退避），避免对本来就忙的邮件服务器连续施压
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                sendTo(user.getEmail(), title, content);
                if (attempt > 1) {
                    log.info("Reminder email to user {} sent on attempt {}", userId, attempt);
                }
                return;
            } catch (MailException e) {
                if (attempt == MAX_ATTEMPTS) {
                    // 异步方法里的异常不会传回调用方，必须自己记录下来，否则发送失败了也没人知道
                    log.error("Failed to send reminder email to user {} after {} attempts", userId, attempt, e);
                    return;
                }
                log.warn("Reminder email to user {} failed on attempt {}, retrying: {}", userId, attempt, e.getMessage());
                if (!sleep(Duration.ofSeconds(5L * attempt))) {
                    return;
                }
            }
        }
    }

    /** 等待一段时间；线程被中断（比如应用正在关闭）时返回 false，不再重试 */
    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            // 恢复中断标记，让线程池知道这个线程被要求停止
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 同步发送，失败直接抛出异常。发送测试邮件时用，好把错误原因告诉用户 */
    public void sendTo(String to, String title, String content) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(title);
        message.setText(content);
        mailSender.send(message);
    }
}
