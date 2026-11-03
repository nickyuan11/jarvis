package com.nick.jarvis.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nick.jarvis.entity.EmailOutbox;
import com.nick.jarvis.mapper.EmailOutboxMapper;
import com.nick.jarvis.support.JarvisTime;

@ExtendWith(MockitoExtension.class)
class EmailOutboxServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 15, 20);

    @Mock
    private EmailOutboxMapper mapper;
    @Mock
    private SmtpMailer mailer;
    @Mock
    private ApplicationEventPublisher events;

    private EmailOutboxService service;

    @BeforeAll
    static void initTableInfo() {
        // LambdaUpdateWrapper 要靠实体的元数据把 EmailOutbox::getStatus 翻译成列名 status。
        // 在 Spring 里这一步启动时自动完成；纯单元测试没有启动 MyBatis-Plus，需要手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), EmailOutbox.class);
    }

    @BeforeEach
    void setUp() {
        JarvisTime time = new JarvisTime(Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
        service = new EmailOutboxService(mapper, mailer, time, events);
    }

    private static EmailOutbox pending(long id, int attempts) {
        EmailOutbox mail = new EmailOutbox();
        mail.setId(id);
        mail.setUserId(1L);
        mail.setToAddress("nick@example.com");
        mail.setSubject("标题");
        mail.setContent("正文");
        mail.setStatus(EmailOutbox.STATUS_PENDING);
        mail.setAttempts(attempts);
        mail.setNextAttemptAt(NOW.minusMinutes(1));
        return mail;
    }

    /** 取出每次 update 的 SET 和 WHERE 里用到的参数值 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Collection<Object>> updateValues(int expectedCalls) {
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, times(expectedCalls)).update(isNull(), captor.capture());
        return captor.getAllValues().stream()
                .map(w -> ((AbstractWrapper<?, ?, ?>) w).getParamNameValuePairs().values())
                .toList();
    }

    @Test
    void enqueueSavesTheMailAndAsksForAnImmediateSend() {
        EmailOutbox mail = service.enqueue(1L, "nick@example.com", "标题", "正文");

        verify(mapper).insert(mail);
        assertThat(mail.getStatus()).isEqualTo(EmailOutbox.STATUS_PENDING);
        assertThat(mail.getNextAttemptAt()).isEqualTo(NOW);
        // 发布事件，事务提交后由 onQueued 立即试发；enqueue 自己不发送
        verify(events).publishEvent(new EmailQueuedEvent(mail));
        verifyNoInteractions(mailer);
    }

    @Test
    void successfulSendIsMarkedSent() {
        when(mapper.update(isNull(), any())).thenReturn(1);

        assertThat(service.attempt(pending(1, 0))).isEqualTo(EmailOutboxService.Result.SENT);

        verify(mailer).send("nick@example.com", "标题", "正文");
        // 第 1 次 update 是抢占：租约推后 5 分钟，次数 +1
        List<Collection<Object>> updates = updateValues(2);
        assertThat(updates.get(0)).contains(NOW.plus(RetryPolicy.LEASE), 1);
        // 第 2 次 update 是标记成功
        assertThat(updates.get(1)).contains(EmailOutbox.STATUS_SENT);
    }

    @Test
    void doesNotSendWhenSomeoneElseClaimedIt() {
        // 抢占的 UPDATE 影响 0 行：被别的线程或服务器抢先了
        when(mapper.update(isNull(), any())).thenReturn(0);

        assertThat(service.attempt(pending(1, 0))).isEqualTo(EmailOutboxService.Result.SKIPPED);

        verifyNoInteractions(mailer);
    }

    @Test
    void failureSchedulesRetryWithBackoff() {
        when(mapper.update(isNull(), any())).thenReturn(1);
        doThrow(new MailSendException("timeout")).when(mailer).send(anyString(), anyString(), anyString());

        // 之前已经失败过 2 次，这是第 3 次：应该等 4 分钟再试
        assertThat(service.attempt(pending(1, 2))).isEqualTo(EmailOutboxService.Result.FAILED);

        Collection<Object> failure = updateValues(2).get(1);
        assertThat(failure).contains(NOW.plusMinutes(4));
        assertThat(failure).doesNotContain(EmailOutbox.STATUS_FAILED);
    }

    @Test
    void givesUpOnTheLastAttempt() {
        when(mapper.update(isNull(), any())).thenReturn(1);
        doThrow(new MailSendException("timeout")).when(mailer).send(anyString(), anyString(), anyString());

        service.attempt(pending(1, RetryPolicy.MAX_ATTEMPTS - 1));

        assertThat(updateValues(2).get(1)).contains(EmailOutbox.STATUS_FAILED);
    }

    @Test
    void stopsTheRoundAfterAFailure() {
        when(mailer.configured()).thenReturn(true);
        when(mapper.selectList(any())).thenReturn(List.of(pending(1, 0), pending(2, 0), pending(3, 0)));
        when(mapper.update(isNull(), any())).thenReturn(1);
        doThrow(new MailSendException("timeout")).when(mailer).send(anyString(), anyString(), anyString());

        assertThat(service.processDue()).isZero();

        // 第一封失败后，后面两封留到下一轮，不在限流期间连着发
        verify(mailer, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    void errorDescriptionShowsTheRootCause() {
        Exception e = new MailAuthenticationException("Authentication failed",
                new RuntimeException("wrapper", new SocketTimeoutException("Read timed out")));

        assertThat(EmailOutboxService.describe(e))
                .isEqualTo("MailAuthenticationException <- SocketTimeoutException: Read timed out");
    }
}
