package com.nick.jarvis.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nick.jarvis.entity.Reminder;
import com.nick.jarvis.mapper.ReminderMapper;
import com.nick.jarvis.notification.channel.NotificationDispatcher;

/**
 * 这里只测 fire 的逻辑。“失败后整体回滚”是 Spring 事务代理的行为，纯单元测试里没有代理，
 * 所以要靠手动验证：让写入发件箱失败，看提醒是否保持待提醒、站内通知是否也没有写入。
 */
@ExtendWith(MockitoExtension.class)
class ReminderFirerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 15, 0);

    @Mock
    private ReminderMapper reminderMapper;
    @Mock
    private NotificationDispatcher dispatcher;

    private ReminderFirer firer;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Reminder.class);
    }

    @BeforeEach
    void setUp() {
        firer = new ReminderFirer(reminderMapper, dispatcher);
    }

    private static Reminder dueReminder() {
        Reminder reminder = new Reminder();
        reminder.setId(1L);
        reminder.setUserId(1L);
        reminder.setContent("开会");
        reminder.setRemindAt(NOW);
        reminder.setRepeatType(RepeatType.NONE.name());
        reminder.setStatus(Reminder.STATUS_PENDING);
        return reminder;
    }

    @Test
    void claimedReminderIsDispatched() {
        when(reminderMapper.update(isNull(), any())).thenReturn(1);

        assertThat(firer.fire(dueReminder(), NOW)).isTrue();

        verify(dispatcher).dispatch(eq(1L), contains("开会"), anyString());
    }

    @Test
    void reminderClaimedByOthersIsNotDispatched() {
        when(reminderMapper.update(isNull(), any())).thenReturn(0);

        assertThat(firer.fire(dueReminder(), NOW)).isFalse();

        verifyNoInteractions(dispatcher);
    }

    @Test
    void dispatchFailureIsThrownSoTheTransactionRollsBack() {
        when(reminderMapper.update(isNull(), any())).thenReturn(1);
        doThrow(new RuntimeException("insert failed")).when(dispatcher).dispatch(anyLong(), anyString(), anyString());

        assertThatThrownBy(() -> firer.fire(dueReminder(), NOW)).hasMessage("insert failed");
    }
}
