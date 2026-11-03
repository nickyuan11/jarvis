package com.nick.jarvis.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.nick.jarvis.notification.channel.NotificationChannel;
import com.nick.jarvis.notification.channel.NotificationDispatcher;

class NotificationDispatcherTest {

    @Test
    void sendsToEveryChannel() {
        NotificationChannel inApp = mock(NotificationChannel.class);
        NotificationChannel email = mock(NotificationChannel.class);

        new NotificationDispatcher(List.of(inApp, email)).dispatch(1L, "标题", "正文");

        verify(inApp).send(1L, "标题", "正文");
        verify(email).send(1L, "标题", "正文");
    }

    @Test
    void channelFailureIsNotSwallowedSoTheTransactionCanRollBack() {
        NotificationChannel inApp = mock(NotificationChannel.class);
        NotificationChannel email = mock(NotificationChannel.class);
        // 写入发件箱失败（比如数据库出了问题）
        doThrow(new RuntimeException("insert failed")).when(email).send(1L, "标题", "正文");

        // 异常必须抛出去：被吞掉的话，事务会照常提交，邮件就悄悄丢了
        assertThatThrownBy(() -> new NotificationDispatcher(List.of(inApp, email)).dispatch(1L, "标题", "正文"))
                .hasMessage("insert failed");
    }
}
