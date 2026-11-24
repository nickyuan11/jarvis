package com.nick.jarvis.notification;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.nick.jarvis.entity.Notification;
import com.nick.jarvis.mapper.NotificationMapper;
import com.nick.jarvis.support.JarvisTime;

/**
 * 站内通知。提醒到点时写入一条通知，前端定时拉取未读通知并弹出。
 */
@Service
public class NotificationService {

    private final NotificationMapper notificationMapper;
    private final JarvisTime time;

    public NotificationService(NotificationMapper notificationMapper, JarvisTime time) {
        this.notificationMapper = notificationMapper;
        this.time = time;
    }

    public void send(Long userId, String content) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setContent(content);
        notificationMapper.insert(notification);
    }

    public List<NotificationView> unread(Long userId) {
        return notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, userId)
                        .isNull(Notification::getReadAt)
                        .orderByAsc(Notification::getId)
                        .last("LIMIT 20"))
                .stream()
                .map(n -> new NotificationView(n.getId(), n.getContent(), JarvisTime.instant(n.getCreateTime())))
                .toList();
    }

    /** 只会标记属于这个用户的通知，条件里带上 user_id，防止越权 */
    public void markRead(Long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .set(Notification::getReadAt, time.now())
                .eq(Notification::getUserId, userId)
                .in(Notification::getId, ids)
                .isNull(Notification::getReadAt));
    }

    public record NotificationView(Long id, String content, java.time.Instant createTime) {
    }
}
