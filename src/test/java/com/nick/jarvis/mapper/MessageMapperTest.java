package com.nick.jarvis.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nick.jarvis.entity.Conversation;
import com.nick.jarvis.entity.Message;

/**
 * 验证数据库连通、建表正确、自动填充生效。
 * @Transactional 让测试结束后自动回滚，不会在库里留下测试数据。
 */
@SpringBootTest
@Transactional
class MessageMapperTest {

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private MessageMapper messageMapper;

    @Test
    void saveAndLoadConversationMessages() {
        Conversation conversation = new Conversation();
        conversation.setUserId(1L);
        conversation.setTitle("测试会话");
        conversationMapper.insert(conversation);
        assertNotNull(conversation.getId(), "插入后应该回填自增 id");

        saveMessage(conversation.getId(), Message.ROLE_USER, "你好，Jarvis 😀");
        saveMessage(conversation.getId(), Message.ROLE_ASSISTANT, "你好！有什么可以帮你？");

        List<Message> messages = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversation.getId())
                .orderByAsc(Message::getId));

        assertEquals(2, messages.size());
        assertEquals(Message.ROLE_USER, messages.get(0).getRole());
        assertEquals("你好，Jarvis 😀", messages.get(0).getContent());
        assertNotNull(messages.get(0).getCreateTime(), "createTime 应该被自动填充");
    }

    private void saveMessage(Long conversationId, String role, String content) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        messageMapper.insert(message);
    }
}
