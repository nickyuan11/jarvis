package com.nick.jarvis.notification.channel;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 把一条通知交给所有渠道。
 *
 * Spring 会把所有 NotificationChannel 的实现自动注入到这个 List 里，
 * 新增渠道时这里不用改（开闭原则：对扩展开放，对修改关闭）。
 *
 * 这里故意不 catch 单个渠道的异常。现在每个渠道都只写数据库（站内通知表、邮件发件箱），
 * 真正容易失败的外部发送已经交给发件箱去隔离和重试了。
 * 写库失败时要让异常抛出去，调用方的事务整体回滚，稍后重来；
 * 如果在这里把异常吞掉，事务会照常提交，提醒被标记成完成，通知却悄悄丢了。
 */
@Component
public class NotificationDispatcher {

    private final List<NotificationChannel> channels;

    public NotificationDispatcher(List<NotificationChannel> channels) {
        this.channels = channels;
    }

    public void dispatch(Long userId, String title, String content) {
        for (NotificationChannel channel : channels) {
            channel.send(userId, title, content);
        }
    }
}
