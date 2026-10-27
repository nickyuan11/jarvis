package com.nick.jarvis.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nick.jarvis.dto.ConversationView;
import com.nick.jarvis.dto.MessageView;
import com.nick.jarvis.entity.Conversation;
import com.nick.jarvis.entity.Message;
import com.nick.jarvis.mapper.ConversationMapper;
import com.nick.jarvis.mapper.MessageMapper;
import com.nick.jarvis.security.CurrentUser;

/**
 * 会话管理：列表、查看消息、重命名、删除。
 * 每个操作都先校验“这个会话属于当前用户”，否则别人改个 id 就能看到你的聊天记录。
 */
@Service
public class ConversationService {

    private static final int MAX_LIST_SIZE = 50;
    private static final int MAX_TITLE_LENGTH = 100;

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    public ConversationService(ConversationMapper conversationMapper, MessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    /** 当前用户最近活跃的会话，最新的在前 */
    public List<ConversationView> list() {
        return conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getUserId, CurrentUser.id())
                        .orderByDesc(Conversation::getUpdateTime)
                        .last("LIMIT " + MAX_LIST_SIZE))
                .stream()
                .map(c -> new ConversationView(c.getId(), c.getTitle(), c.getUpdateTime()))
                .toList();
    }

    /** 某个会话的全部消息，按时间正序 */
    public List<MessageView> messages(Long conversationId) {
        requireOwned(conversationId);
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversationId)
                        .orderByAsc(Message::getId))
                .stream()
                .map(m -> new MessageView(m.getId(), m.getRole(), m.getContent(), m.getCreateTime()))
                .toList();
    }

    public void rename(Long conversationId, String title) {
        requireOwned(conversationId);
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题不能为空");
        }
        String trimmed = title.strip();
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题不能超过 " + MAX_TITLE_LENGTH + " 个字");
        }
        Conversation update = new Conversation();
        update.setId(conversationId);
        update.setTitle(trimmed);
        conversationMapper.updateById(update);
    }

    /**
     * 逻辑删除：实际执行的是 UPDATE conversation SET deleted = 1。
     * 消息不用删，会话查不到了，它的消息也就访问不到了。
     */
    public void delete(Long conversationId) {
        requireOwned(conversationId);
        conversationMapper.deleteById(conversationId);
    }

    private void requireOwned(Long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        // 不存在和不属于你，统一返回 404，不让人通过错误信息猜出别人的会话是否存在
        if (conversation == null || !CurrentUser.id().equals(conversation.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
    }
}
