package com.nick.jarvis.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
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
import org.springframework.transaction.PlatformTransactionManager;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nick.jarvis.entity.Conversation;
import com.nick.jarvis.entity.Message;
import com.nick.jarvis.entity.UserMemory;
import com.nick.jarvis.mapper.ConversationMapper;
import com.nick.jarvis.mapper.MessageMapper;
import com.nick.jarvis.memory.MemoryExtractionClient.Operation;
import com.nick.jarvis.support.JarvisTime;

@ExtendWith(MockitoExtension.class)
class MemoryExtractorTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 15, 0);
    private static final long USER_ID = 1L;
    private static final long CONVERSATION_ID = 7L;
    private static final long WATERMARK = 100L;

    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private MemoryService memoryService;
    @Mock
    private MemoryExtractionClient client;
    @Mock
    private PlatformTransactionManager transactionManager;

    private MemoryExtractor extractor;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Conversation.class);
        TableInfoHelper.initTableInfo(assistant, Message.class);
    }

    @BeforeEach
    void setUp() {
        // 事务管理器是模拟的：TransactionTemplate 照常执行回调，只是不会真的开事务
        extractor = new MemoryExtractor(conversationMapper, messageMapper, memoryService, client,
                new JarvisTime(Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE)), transactionManager,
                Duration.ofMinutes(10));
    }

    private static Conversation conversation() {
        Conversation c = new Conversation();
        c.setId(CONVERSATION_ID);
        c.setUserId(USER_ID);
        c.setMemoryExtractedMessageId(WATERMARK);
        return c;
    }

    private static Message message(long id, String role, String content) {
        Message m = new Message();
        m.setId(id);
        m.setConversationId(CONVERSATION_ID);
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    private void newMessages() {
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message(101, Message.ROLE_USER, "我下个月入职新公司了"),
                message(102, Message.ROLE_ASSISTANT, "恭喜！")));
    }

    private void modelReturns(Operation... operations) {
        when(client.extract(anyString(), anyString(), anyString())).thenReturn(List.of(operations));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void appliesOperationsAndAdvancesTheWatermark() {
        newMessages();
        modelReturns(new Operation("ADD", null, "FACT", "下个月入职新公司"));
        when(conversationMapper.update(isNull(), any())).thenReturn(1);

        assertThat(extractor.extractOne(conversation())).isTrue();

        verify(memoryService).remember(USER_ID, "下个月入职新公司", "FACT", UserMemory.SOURCE_EXTRACTED, CONVERSATION_ID);
        // 水位线从 100 推到实际读到的最后一条消息 102，条件里带着旧值 100（乐观锁）
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(conversationMapper).update(isNull(), captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        wrapper.getSqlSegment();
        Collection<Object> values = wrapper.getParamNameValuePairs().values();
        assertThat(values).contains(102L, WATERMARK, CONVERSATION_ID);
    }

    @Test
    void ignoresMemoryIdsThatDoNotBelongToTheUser() {
        newMessages();
        UserMemory own = new UserMemory();
        own.setId(3L);
        own.setUserId(USER_ID);
        own.setType(UserMemory.TYPE_FACT);
        own.setContent("在找 Java 后端的工作");
        when(memoryService.list(USER_ID)).thenReturn(List.of(own));
        // 模型给的编号不可信：99、98 不是这个用户的记忆
        modelReturns(new Operation("UPDATE", 99L, "FACT", "别人的记忆"),
                new Operation("DELETE", 98L, null, null),
                new Operation("DELETE", 3L, null, null));
        when(conversationMapper.update(isNull(), any())).thenReturn(1);

        extractor.extractOne(conversation());

        verify(memoryService).forget(USER_ID, 3L);
        verify(memoryService, never()).forget(USER_ID, 98L);
        verify(memoryService, never()).update(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void writesNothingWhenSomeoneElseAlreadyProcessedTheseMessages() {
        newMessages();
        modelReturns(new Operation("ADD", null, "FACT", "下个月入职新公司"));
        // 条件更新影响 0 行：水位线已经被别的线程推走了
        when(conversationMapper.update(isNull(), any())).thenReturn(0);

        assertThat(extractor.extractOne(conversation())).isFalse();

        verify(memoryService, never()).remember(anyLong(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void skipsTheModelWhenThereAreNoUserMessages() {
        when(messageMapper.selectList(any())).thenReturn(List.of(message(101, Message.ROLE_ASSISTANT, "（被中断的回复）")));
        when(conversationMapper.update(isNull(), any())).thenReturn(1);

        assertThat(extractor.extractOne(conversation())).isTrue();

        verifyNoInteractions(client);
    }

    @Test
    void modelFailureLeavesTheWatermarkForARetry() {
        newMessages();
        when(client.extract(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("429"));

        assertThatThrownBy(() -> extractor.extractOne(conversation())).hasMessage("429");

        verify(conversationMapper, never()).update(isNull(), any());
    }

    @Test
    void givesUpOnTheseMessagesAfterRepeatedFailures() {
        newMessages();
        when(client.extract(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("bad"));
        when(conversationMapper.update(isNull(), any())).thenReturn(1);

        for (int i = 1; i < MemoryExtractor.MAX_FAILURES; i++) {
            assertThatThrownBy(() -> extractor.extractOne(conversation())).hasMessage("bad");
        }
        // 第 3 次失败：不再重试，跳过这一段，水位线照样推进
        assertThat(extractor.extractOne(conversation())).isTrue();
    }

    @Test
    void assistantRepliesAreShortenedInTheTranscript() {
        String transcript = MemoryExtractor.transcript(List.of(
                message(1, Message.ROLE_USER, "我对花生过敏"),
                message(2, Message.ROLE_ASSISTANT, "字".repeat(500))));

        assertThat(transcript).startsWith("[用户] 我对花生过敏\n[助手] ");
        assertThat(transcript.length()).isLessThan(300);
    }
}
