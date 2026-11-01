package com.nick.jarvis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import com.nick.jarvis.entity.Conversation;
import com.nick.jarvis.mapper.ConversationMapper;
import com.nick.jarvis.mapper.MessageMapper;

/**
 * 重点验证越权访问（IDOR）：用户 2 不能看、改、删用户 1 的会话。
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    private static final Long OWNER = 1L;
    private static final Long OTHER_USER = 2L;

    @Mock
    private ConversationMapper conversationMapper;

    @Mock
    private MessageMapper messageMapper;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        service = new ConversationService(conversationMapper, messageMapper);
    }

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownerCanRename() {
        loginAs(OWNER);
        when(conversationMapper.selectById(10L)).thenReturn(conversationOf(OWNER));

        service.rename(10L, "  新标题  ");

        ArgumentCaptor<Conversation> update = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationMapper).updateById(update.capture());
        assertThat(update.getValue().getTitle()).isEqualTo("新标题");
    }

    @Test
    void otherUserCannotReadMessages() {
        loginAs(OTHER_USER);
        when(conversationMapper.selectById(10L)).thenReturn(conversationOf(OWNER));

        assertNotFound(() -> service.messages(10L));
        verify(messageMapper, never()).selectList(any());
    }

    @Test
    void otherUserCannotRename() {
        loginAs(OTHER_USER);
        when(conversationMapper.selectById(10L)).thenReturn(conversationOf(OWNER));

        assertNotFound(() -> service.rename(10L, "hacked"));
        verify(conversationMapper, never()).updateById(any(Conversation.class));
    }

    @Test
    void otherUserCannotDelete() {
        loginAs(OTHER_USER);
        when(conversationMapper.selectById(10L)).thenReturn(conversationOf(OWNER));

        assertNotFound(() -> service.delete(10L));
        verify(conversationMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void missingAndForeignConversationsLookTheSame() {
        loginAs(OTHER_USER);
        when(conversationMapper.selectById(10L)).thenReturn(conversationOf(OWNER));
        when(conversationMapper.selectById(99L)).thenReturn(null);

        // 都是 404：不让人通过返回码判断某个 id 的会话是否存在
        assertNotFound(() -> service.delete(10L));
        assertNotFound(() -> service.delete(99L));
    }

    @Test
    void blankTitleIsRejected() {
        loginAs(OWNER);
        when(conversationMapper.selectById(10L)).thenReturn(conversationOf(OWNER));

        assertThatThrownBy(() -> service.rename(10L, "   "))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static void loginAs(Long userId) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private static Conversation conversationOf(Long userId) {
        Conversation conversation = new Conversation();
        conversation.setId(10L);
        conversation.setUserId(userId);
        conversation.setTitle("旧标题");
        return conversation;
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
