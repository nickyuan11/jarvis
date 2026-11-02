package com.nick.jarvis.notification.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 真正连接 SMTP 服务器发信的地方。只管“发一封”，不管重试。
 * 失败时抛出 MailException，由调用方决定怎么处理。
 */
@Component
public class SmtpMailer {

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpMailer(JavaMailSender mailSender, @Value("${spring.mail.username:}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    /** 服务器是否配置了发件邮箱 */
    public boolean configured() {
        return from != null && !from.isBlank();
    }

    public void send(String to, String subject, String content) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(content);
        mailSender.send(message);
    }
}
