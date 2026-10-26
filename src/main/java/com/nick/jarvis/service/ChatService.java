package com.nick.jarvis.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nick.jarvis.dto.ChatReply;
import com.nick.jarvis.entity.Conversation;
import com.nick.jarvis.entity.Message;
import com.nick.jarvis.mapper.ConversationMapper;
import com.nick.jarvis.mapper.MessageMapper;

/**
 * 聊天的核心流程：
 * 1. 找到（或新建）会话
 * 2. 保存用户消息
 * 3. 取出最近的历史消息，连同系统提示词一起发给大模型
 * 4. 保存大模型的回复，以及 token 用量
 */
@Service
public class ChatService {

    /** 还没有做登录，先用一个固定的用户 id，第 2 周接入登录后替换 */
    private static final Long TEMP_USER_ID = 1L;

    private final ChatClient chatClient;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final int historySize;

    public ChatService(ChatClient.Builder chatClientBuilder,
                       ConversationMapper conversationMapper,
                       MessageMapper messageMapper,
                       @Value("${jarvis.chat.system-prompt}") String systemPrompt,
                       @Value("${jarvis.chat.history-size:20}") int historySize) {
        // 系统提示词作为默认设置，每次请求都会自动带上
        this.chatClient = chatClientBuilder.defaultSystem(systemPrompt).build();
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.historySize = historySize;
    }

    public ChatReply chat(Long conversationId, String userText) {
        if (userText == null || userText.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }

        Conversation conversation = findOrCreateConversation(conversationId, userText);
        saveMessage(conversation.getId(), Message.ROLE_USER, userText, null, null, null);

        // 注意：调用大模型可能要好几秒，所以不要把它放在数据库事务里，否则会长时间占用数据库连接
        List<org.springframework.ai.chat.messages.Message> history = loadHistory(conversation.getId());
        ChatResponse response = chatClient.prompt()
                .messages(history)
                .call()
                .chatResponse();

        String replyText = response.getResult().getOutput().getText();
        String model = response.getMetadata().getModel();
        Usage usage = response.getMetadata().getUsage();
        Integer promptTokens = usage == null ? null : usage.getPromptTokens();
        Integer completionTokens = usage == null ? null : usage.getCompletionTokens();

        saveMessage(conversation.getId(), Message.ROLE_ASSISTANT, replyText, model, promptTokens, completionTokens);
        touchConversation(conversation.getId());

        return new ChatReply(conversation.getId(), replyText, model, promptTokens, completionTokens);
    }

    private Conversation findOrCreateConversation(Long conversationId, String firstMessage) {
        if (conversationId != null) {
            Conversation existing = conversationMapper.selectById(conversationId);
            if (existing == null || !TEMP_USER_ID.equals(existing.getUserId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
            }
            return existing;
        }
        Conversation conversation = new Conversation();
        conversation.setUserId(TEMP_USER_ID);
        // 用第一句话的前 20 个字当标题，以后可以改成让 AI 自动总结标题
        String title = firstMessage.strip();
        conversation.setTitle(title.length() > 20 ? title.substring(0, 20) + "…" : title);
        conversationMapper.insert(conversation);
        return conversation;
    }

    /**
     * 取最近 historySize 条消息，并转换成 Spring AI 的消息对象。
     * 先按 id 倒序取最新的 N 条，再反转成时间正序，因为大模型需要按对话发生的顺序阅读。
     */
    private List<org.springframework.ai.chat.messages.Message> loadHistory(Long conversationId) {
        List<Message> recent = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByDesc(Message::getId)
                .last("LIMIT " + historySize));
        Collections.reverse(recent);

        List<org.springframework.ai.chat.messages.Message> history = new ArrayList<>();
        for (Message m : recent) {
            if (Message.ROLE_USER.equals(m.getRole())) {
                history.add(new UserMessage(m.getContent()));
            } else if (Message.ROLE_ASSISTANT.equals(m.getRole())) {
                history.add(new AssistantMessage(m.getContent()));
            }
        }
        return history;
    }

    private void saveMessage(Long conversationId, String role, String content,
                             String model, Integer promptTokens, Integer completionTokens) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setModel(model);
        message.setPromptTokens(promptTokens);
        message.setCompletionTokens(completionTokens);
        messageMapper.insert(message);
    }

    /** 更新会话的 update_time，让最近聊过的会话排在列表前面 */
    private void touchConversation(Long conversationId) {
        Conversation update = new Conversation();
        update.setId(conversationId);
        conversationMapper.updateById(update);
    }
}
