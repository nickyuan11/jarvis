package com.nick.jarvis.notification.channel;

/**
 * 通知渠道。站内通知、邮件各是一个实现；以后加短信、微信推送，只需要再写一个实现类。
 */
public interface NotificationChannel {

    /** 渠道名，用于日志 */
    String name();

    /**
     * 发送一条通知。渠道自己判断该不该发（比如用户没开邮件提醒就跳过）。
     *
     * @param title   标题，比如邮件主题
     * @param content 正文
     */
    void send(Long userId, String title, String content);
}
