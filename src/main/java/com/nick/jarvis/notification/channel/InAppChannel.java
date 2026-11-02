package com.nick.jarvis.notification.channel;

import org.springframework.stereotype.Component;

import com.nick.jarvis.notification.NotificationService;

/**
 * 站内通知：写入 notification 表，网页轮询后弹出。所有用户都会收到。
 */
@Component
public class InAppChannel implements NotificationChannel {

    private final NotificationService notificationService;

    public InAppChannel(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public String name() {
        return "in-app";
    }

    @Override
    public void send(Long userId, String title, String content) {
        notificationService.send(userId, title);
    }
}
