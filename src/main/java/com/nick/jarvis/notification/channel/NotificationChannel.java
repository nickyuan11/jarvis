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
     * 调用方通常在事务里，所以实现里只应该写数据库。需要连外部服务的（邮件、以后的短信），
     * 先写进发件箱，等事务提交后再发：发出去的消息没法跟着事务回滚。
     *
     * @param title   标题，比如邮件主题
     * @param content 正文
     */
    void send(Long userId, String title, String content);
}
