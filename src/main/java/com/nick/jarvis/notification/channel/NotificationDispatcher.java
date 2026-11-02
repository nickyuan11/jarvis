package com.nick.jarvis.notification.channel;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把一条通知交给所有渠道发送。
 *
 * Spring 会把所有 NotificationChannel 的实现自动注入到这个 List 里，
 * 新增渠道时这里不用改（开闭原则：对扩展开放，对修改关闭）。
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final List<NotificationChannel> channels;

    public NotificationDispatcher(List<NotificationChannel> channels) {
        this.channels = channels;
    }

    public void dispatch(Long userId, String title, String content) {
        for (NotificationChannel channel : channels) {
            try {
                channel.send(userId, title, content);
            } catch (Exception e) {
                // 一个渠道失败（比如邮件服务器连不上），不能影响其他渠道
                log.error("Channel {} failed for user {}", channel.name(), userId, e);
            }
        }
    }
}
