package com.nick.jarvis.notification;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.nick.jarvis.notification.channel.NotificationChannel;
import com.nick.jarvis.notification.channel.NotificationDispatcher;

class NotificationDispatcherTest {

    @Test
    void oneChannelFailingDoesNotStopTheOthers() {
        NotificationChannel email = mock(NotificationChannel.class);
        NotificationChannel inApp = mock(NotificationChannel.class);
        when(email.name()).thenReturn("email");
        // 邮件服务器连不上
        doThrow(new RuntimeException("SMTP down")).when(email).send(1L, "标题", "正文");

        new NotificationDispatcher(List.of(email, inApp)).dispatch(1L, "标题", "正文");

        // 排在后面的站内通知依然要发出去
        verify(inApp).send(1L, "标题", "正文");
    }
}
