package com.nick.jarvis.service;

import java.util.ArrayList;
import java.util.Collections;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nick.jarvis.dto.ChatEvent;
import com.nick.jarvis.dto.ChatReply;
import com.nick.jarvis.entity.Conversation;
import com.nick.jarvis.entity.Message;
import com.nick.jarvis.mapper.ConversationMapper;
import com.nick.jarvis.mapper.MessageMapper;
import com.nick.jarvis.ratelimit.RateLimiter;
import com.nick.jarvis.security.CurrentUser;
import com.nick.jarvis.support.JarvisTime;
import com.nick.jarvis.memory.MemoryService;
import com.nick.jarvis.tools.KnowledgeTools;
import com.nick.jarvis.tools.MemoryTools;
import com.nick.jarvis.tools.LolTools;
import com.nick.jarvis.tools.NbaTools;
import com.nick.jarvis.tools.ReminderTools;
import com.nick.jarvis.tools.ToolContextKeys;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 聊天的核心流程：
 * 1. 找到（或新建）会话
 * 2. 保存用户消息
 * 3. 取出最近的历史消息，连同系统提示词一起发给大模型
 * 4. 保存大模型的回复，以及 token 用量
 *
 * chat() 是一次性返回，streamChat() 是边生成边推送。前三步两者共用。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final DateTimeFormatter SENT_AT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final ChatClient chatClient;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final RateLimiter rateLimiter;
    private final JarvisTime time;
    private final String systemPrompt;
    private final int historySize;
    /** 交给大模型的工具 */
    private final Object[] tools;
    private final MemoryService memoryService;

    public ChatService(ChatClient.Builder chatClientBuilder,
                       ConversationMapper conversationMapper,
                       MessageMapper messageMapper,
                       RateLimiter rateLimiter,
                       JarvisTime time,
                       ReminderTools reminderTools,
                       NbaTools nbaTools,
                       LolTools lolTools,
                       KnowledgeTools knowledgeTools,
                       MemoryTools memoryTools,
                       MemoryService memoryService,
                       @Value("${jarvis.chat.system-prompt}") String systemPrompt,
                       @Value("${jarvis.chat.history-size:20}") int historySize) {
        this.chatClient = chatClientBuilder.build();
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.rateLimiter = rateLimiter;
        this.time = time;
        this.systemPrompt = systemPrompt;
        this.historySize = historySize;
        this.tools = new Object[] {reminderTools, nbaTools, lolTools, knowledgeTools, memoryTools};
        this.memoryService = memoryService;
    }

    /**
     * 每次请求都准备一个新的 prompt：
     * - 系统提示词里带上当前时间。大模型不知道“现在”是什么时候，不告诉它，它就没法把“明天下午”换算成具体日期；
     * - 系统提示词里带上用户的长期记忆；
     * - 注册工具，并通过 toolContext 把当前用户 id、会话 id 传给工具。
     *
     * 系统提示词的拼接顺序：固定规则 → 记忆 → 当前时间。
     * DeepSeek 有上下文缓存：请求开头和之前完全相同的部分，计费更便宜、处理更快。缓存只认“从开头起完全相同”，
     * 当前时间每分钟都在变，必须放在最后；记忆很少变化，放在它前面，就能和固定规则一起被缓存。
     */
    private ChatClient.ChatClientRequestSpec newPrompt(Prepared prepared) {
        var now = time.zonedNow(prepared.zone());
        String timeLine = "\n当前时间：" + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                + "，" + now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA)
                + "（" + prepared.zone() + "）";
        return chatClient.prompt()
                .system(systemPrompt + memoryService.promptSection(prepared.userId(), prepared.zone()) + timeLine)
                .messages(prepared.history())
                .tools(tools)
                .toolContext(Map.of(ToolContextKeys.USER_ID, prepared.userId(),
                        ToolContextKeys.TIME_ZONE, prepared.zone(), ToolContextKeys.CONVERSATION_ID, prepared.conversationId()));
    }

    /** 一次性返回完整回复 */
    public ChatReply chat(Long conversationId, String userText, String timeZone) {
        Prepared prepared = prepare(conversationId, userText, timeZone);

        // 注意：调用大模型可能要好几秒，所以不要把它放在数据库事务里，否则会长时间占用数据库连接
        ChatResponse response = newPrompt(prepared)
                .call()
                .chatResponse();

        String replyText = response.getResult().getOutput().getText();
        String model = response.getMetadata().getModel();
        Usage usage = response.getMetadata().getUsage();
        Integer promptTokens = usage == null ? null : usage.getPromptTokens();
        Integer completionTokens = usage == null ? null : usage.getCompletionTokens();

        saveAssistantReply(prepared.conversationId(), replyText, model, promptTokens, completionTokens);
        return new ChatReply(prepared.conversationId(), replyText, model, promptTokens, completionTokens);
    }

    /**
     * 流式返回：模型每生成一小段，就立刻推给前端。
     * 返回的 Flux 是“一串将来会陆续到达的事件”，Spring MVC 会把它按 SSE 格式写回给浏览器。
     */
    public Flux<ServerSentEvent<Object>> streamChat(Long conversationId, String userText, String timeZone) {
        // 准备工作（查库、存用户消息）是同步执行的，出错会直接返回 400/404，不会进入流
        Prepared prepared = prepare(conversationId, userText, timeZone);
        Long convId = prepared.conversationId();

        // 用来在流的过程中累积完整回复和最后的元数据
        StringBuilder fullText = new StringBuilder();
        AtomicReference<String> model = new AtomicReference<>();
        AtomicReference<Usage> usage = new AtomicReference<>();

        Flux<ServerSentEvent<Object>> deltas = newPrompt(prepared)
                .stream()
                .chatResponse()
                // 下面的回调里有数据库操作（阻塞），切到专门处理阻塞任务的线程池上执行
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    // 模型名和 token 用量通常只出现在最后一个分块里，见到就记下来
                    if (chunk.getMetadata() != null) {
                        if (chunk.getMetadata().getModel() != null) {
                            model.set(chunk.getMetadata().getModel());
                        }
                        Usage u = chunk.getMetadata().getUsage();
                        if (u != null && u.getTotalTokens() != null && u.getTotalTokens() > 0) {
                            usage.set(u);
                        }
                    }
                })
                .map(this::textOf)
                .filter(text -> !text.isEmpty())
                .doOnNext(fullText::append)
                .map(text -> event("delta", new ChatEvent.Delta(text)));

        Flux<ServerSentEvent<Object>> done = Flux.defer(() -> {
            Usage u = usage.get();
            Integer promptTokens = u == null ? null : u.getPromptTokens();
            Integer completionTokens = u == null ? null : u.getCompletionTokens();
            saveAssistantReply(convId, fullText.toString(), model.get(), promptTokens, completionTokens);
            return Flux.just(event("done", new ChatEvent.Done(model.get(), promptTokens, completionTokens)));
        });

        return Flux.concat(
                        Flux.just(event("meta", new ChatEvent.Meta(convId))),
                        deltas,
                        done)
                // 用户中途关闭页面：把已经生成的部分保存下来，避免对话记录缺一截
                .doOnCancel(() -> {
                    if (!fullText.isEmpty()) {
                        log.info("Stream cancelled by client, saving partial reply, conversationId={}", convId);
                        saveAssistantReply(convId, fullText.toString(), model.get(), null, null);
                    }
                })
                // 模型调用失败：不让异常直接断开连接，而是推一个 error 事件给前端展示
                .onErrorResume(e -> {
                    log.error("Stream chat failed, conversationId={}", convId, e);
                    return Flux.just(event("error", new ChatEvent.Error("Jarvis 暂时无法回复，请稍后再试")));
                });
    }

    // ------------------------------------------------------------------
    // 以下是两种方式共用的步骤
    // ------------------------------------------------------------------

    /** 前三步的结果：会话 id、当前用户 id、准备发给模型的历史消息 */
    private record Prepared(Long conversationId, Long userId, ZoneId zone,
                            List<org.springframework.ai.chat.messages.Message> history) {
    }

    private Prepared prepare(Long conversationId, String userText, String timeZone) {
        if (userText == null || userText.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        ZoneId zone;
        try {
            zone = time.userZone(timeZone);
        } catch (DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的时区");
        }
        // 先限流再干活：被拒绝的请求不会写数据库，也不会调用大模型
        rateLimiter.check(CurrentUser.id());
        Conversation conversation = findOrCreateConversation(conversationId, userText);
        saveMessage(conversation.getId(), Message.ROLE_USER, userText, zone.getId(), null, null, null);
        return new Prepared(conversation.getId(), CurrentUser.id(), zone, loadHistory(conversation.getId()));
    }

    private Conversation findOrCreateConversation(Long conversationId, String firstMessage) {
        if (conversationId != null) {
            Conversation existing = conversationMapper.selectById(conversationId);
            if (existing == null || !CurrentUser.id().equals(existing.getUserId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
            }
            return existing;
        }
        Conversation conversation = new Conversation();
        conversation.setUserId(CurrentUser.id());
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
                // 给用户消息加上发送时间（只在发给模型时加，数据库里的内容不变），
                // 让模型知道对话之间隔了多久，比如“设提醒”是几分钟前的事，而不是刚刚
                history.add(new UserMessage(sentAt(m) + m.getContent()));
            } else if (Message.ROLE_ASSISTANT.equals(m.getRole())) {
                history.add(new AssistantMessage(m.getContent()));
            }
        }
        return history;
    }

    private static String sentAt(Message m) {
        return m.getCreateTime() == null ? "" : "（发送于 " + JarvisTime.local(m.getCreateTime(), m.sentZone()).format(SENT_AT) + " " + m.sentZone() + "）";
    }

    private void saveAssistantReply(Long conversationId, String text, String model,
                                    Integer promptTokens, Integer completionTokens) {
        saveMessage(conversationId, Message.ROLE_ASSISTANT, text, null, model, promptTokens, completionTokens);
        touchConversation(conversationId);
    }

    private void saveMessage(Long conversationId, String role, String content, String timeZone,
                             String model, Integer promptTokens, Integer completionTokens) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setTimeZone(timeZone);
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

    /** 从一个流式分块里取出文字，可能为空（比如最后只带用量信息的分块） */
    private String textOf(ChatResponse chunk) {
        if (chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return "";
        }
        String text = chunk.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.builder(data).event(name).build();
    }
}
