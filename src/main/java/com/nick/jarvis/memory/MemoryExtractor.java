package com.nick.jarvis.memory;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.nick.jarvis.entity.Conversation;
import com.nick.jarvis.entity.Message;
import com.nick.jarvis.entity.UserMemory;
import com.nick.jarvis.mapper.ConversationMapper;
import com.nick.jarvis.mapper.MessageMapper;
import com.nick.jarvis.memory.MemoryExtractionClient.Operation;
import com.nick.jarvis.support.JarvisTime;

/**
 * 长期记忆的后台提取：会话空闲一段时间后，从新消息里找出值得记住的信息。
 * 用户没有明说“记住”、但值得记的事，以及让旧记忆过时的新情况，都靠它。
 *
 * 一次提取分四步：
 *   ① 读出水位线之后的新消息、用户已有的记忆
 *   ② 调用模型，得到“新增 / 更新 / 删除”操作（要好几秒，可能失败）
 *   ③ 把操作写进 user_memory
 *   ④ 把会话的水位线推到第 ① 步读到的最后一条消息
 *
 * - ② 不在事务里：调用模型很慢，不能长时间占着数据库连接；
 * - ③④ 在同一个事务里：否则 ③ 做完、④ 之前崩了，下次会把同一段对话再提取一遍，记忆里出现近似重复的条目；
 * - ④ 是条件更新（WHERE 水位线 = 开始时读到的值），并且在事务里先于 ③ 执行：
 *   两个线程同时提取同一个会话时，只有一个能更新成功，另一个什么都不写（乐观锁）。
 *   不能用“读的时候加锁”：数据库的锁只在事务期间有效，那样事务就得包住第 ② 步。
 */
@Service
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    /** 一次最多读多少条消息；更多的留到下一轮（水位线只推到实际读到的位置，不会跳过） */
    static final int MAX_MESSAGES = 40;
    /** 助手的回复只用来理解上下文，截短一些：省 token，也减少把检索到的资料原文带进来的机会 */
    static final int MAX_ASSISTANT_CHARS = 200;
    static final int MAX_USER_CHARS = 1000;
    /** 同一个会话连续失败这么多次，就跳过这一段。记忆是锦上添花，漏一段好过无限重试花钱 */
    static final int MAX_FAILURES = 3;
    private static final int BATCH_SIZE = 5;

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final MemoryService memoryService;
    private final MemoryExtractionClient client;
    private final JarvisTime time;
    private final TransactionTemplate transaction;
    private final Duration idle;
    /** 会话 id → 连续失败次数。只存在内存里，重启后清零，够用了 */
    private final Map<Long, Integer> failures = new ConcurrentHashMap<>();

    public MemoryExtractor(ConversationMapper conversationMapper, MessageMapper messageMapper,
                           MemoryService memoryService, MemoryExtractionClient client, JarvisTime time,
                           PlatformTransactionManager transactionManager,
                           @Value("${jarvis.memory.extraction.idle:10m}") Duration idle) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.memoryService = memoryService;
        this.client = client;
        this.time = time;
        // 编程式事务：自己调用 transaction.execute(...)，不依赖代理，所以写在同一个类里也生效
        this.transaction = new TransactionTemplate(transactionManager);
        this.idle = idle;
    }

    /** 处理“空闲了一段时间、并且有新消息”的会话，由定时任务调用。返回本轮处理的会话数 */
    public int extractDue() {
        List<Conversation> due = conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                // update_time 是会话最后一次活动的时间：用户不会说“聊完了”，所以用“一段时间没有新消息”来判断
                .le(Conversation::getUpdateTime, time.now().minus(idle))
                .apply("EXISTS (SELECT 1 FROM message m WHERE m.conversation_id = conversation.id"
                        + " AND m.id > conversation.memory_extracted_message_id)")
                .orderByAsc(Conversation::getUpdateTime)
                .last("LIMIT " + BATCH_SIZE));
        int processed = 0;
        for (Conversation conversation : due) {
            try {
                if (extractOne(conversation)) {
                    processed++;
                }
            } catch (RuntimeException e) {
                // 多半是模型接口的问题（限流、网络），接着处理别的会话大概率也失败：这一轮到此为止
                log.warn("Memory extraction failed for conversation #{}, will retry next round: {}",
                        conversation.getId(), e.toString());
                break;
            }
        }
        return processed;
    }

    /** @return true 表示这个会话的水位线被推进了 */
    boolean extractOne(Conversation conversation) {
        long watermark = conversation.getMemoryExtractedMessageId() == null
                ? 0 : conversation.getMemoryExtractedMessageId();
        List<Message> messages = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversation.getId())
                .gt(Message::getId, watermark)
                .orderByAsc(Message::getId)
                .last("LIMIT " + MAX_MESSAGES));
        if (messages.isEmpty()) {
            return false;
        }
        // 水位线推到“实际读到的最后一条”：提取期间用户新发的消息 id 更大，留给下一次
        long newWatermark = messages.get(messages.size() - 1).getId();
        Long userId = conversation.getUserId();
        List<UserMemory> existing = memoryService.list(userId);

        // 没有用户说的话（比如只有一条被中断的回复）：不用问模型，直接推进水位线
        boolean hasUserMessage = messages.stream().anyMatch(m -> Message.ROLE_USER.equals(m.getRole()));
        List<Operation> operations = hasUserMessage
                ? callModel(conversation.getId(), existing, messages)
                : List.of();

        // 模型给的编号不可信：只允许改动确实属于这个用户的记忆
        Set<Long> ownIds = existing.stream().map(UserMemory::getId).collect(Collectors.toSet());
        Boolean advanced = transaction.execute(status -> {
            if (!advance(conversation.getId(), watermark, newWatermark)) {
                // 别的线程（或另一台服务器）已经处理过这一段了
                return false;
            }
            int applied = 0;
            for (Operation operation : operations) {
                if (apply(userId, conversation.getId(), operation, ownIds)) {
                    applied++;
                }
            }
            log.info("Memory extraction: conversation #{}, {} messages, {} of {} operations applied",
                    conversation.getId(), messages.size(), applied, operations.size());
            return true;
        });
        return Boolean.TRUE.equals(advanced);
    }

    private List<Operation> callModel(Long conversationId, List<UserMemory> existing, List<Message> messages) {
        try {
            ZoneId zone = messages.reversed().stream().filter(m -> Message.ROLE_USER.equals(m.getRole()))
                    .findFirst().orElseThrow().sentZone();
            List<Operation> operations = client.extract(time.zonedNow(zone).toLocalDate().toString(),
                    describe(existing, zone), transcript(messages));
            failures.remove(conversationId);
            return operations;
        } catch (RuntimeException e) {
            int count = failures.merge(conversationId, 1, Integer::sum);
            if (count < MAX_FAILURES) {
                throw e;
            }
            failures.remove(conversationId);
            log.error("Memory extraction for conversation #{} failed {} times, skipping these messages",
                    conversationId, count, e);
            return List.of();
        }
    }

    /**
     * 条件更新水位线。用 update(null, wrapper)：没有实体对象，不会触发 update_time 的自动填充，
     * 否则后台提取会把会话顶到列表最前面，还会让它重新进入“空闲计时”。
     */
    private boolean advance(Long conversationId, long from, long to) {
        return conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .set(Conversation::getMemoryExtractedMessageId, to)
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getMemoryExtractedMessageId, from)) == 1;
    }

    private boolean apply(Long userId, Long conversationId, Operation op, Set<Long> ownIds) {
        try {
            switch (op.action()) {
                case MemoryExtractionClient.ADD ->
                        memoryService.remember(userId, op.content(), op.type(), UserMemory.SOURCE_EXTRACTED, conversationId);
                case MemoryExtractionClient.UPDATE -> {
                    if (!ownIds.contains(op.id())) {
                        return false;
                    }
                    memoryService.update(userId, op.id(), op.content(), op.type());
                }
                case MemoryExtractionClient.DELETE -> {
                    if (!ownIds.contains(op.id())) {
                        return false;
                    }
                    memoryService.forget(userId, op.id());
                }
                default -> {
                    return false;
                }
            }
            return true;
        } catch (IllegalArgumentException e) {
            // 校验没通过（敏感信息、太长、已满、类型不对）：跳过这一条，不影响其他操作
            log.info("Memory operation {} skipped: {}", op.action(), e.getMessage());
            return false;
        }
    }

    private static String describe(List<UserMemory> memories, ZoneId zone) {
        if (memories.isEmpty()) {
            return "（还没有）\n";
        }
        StringBuilder sb = new StringBuilder();
        for (UserMemory m : memories) {
            sb.append('#').append(m.getId()).append(" [").append(m.getType()).append("] ").append(m.getContent());
            if (m.getUpdateTime() != null) {
                sb.append("（记录于 ").append(JarvisTime.local(m.getUpdateTime(), zone).toLocalDate()).append('）');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 把消息整理成给模型看的对话文本。数据库里只存了用户和助手的消息，工具返回的原始结果不在其中 */
    static String transcript(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            if (m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            if (Message.ROLE_USER.equals(m.getRole())) {
                sb.append("[用户] ");
                if (m.getCreateTime() != null) {
                    sb.append(JarvisTime.local(m.getCreateTime(), m.sentZone())).append(" ").append(m.sentZone()).append(" ");
                }
                sb.append(shorten(m.getContent(), MAX_USER_CHARS)).append('\n');
            } else if (Message.ROLE_ASSISTANT.equals(m.getRole())) {
                sb.append("[助手] ").append(shorten(m.getContent(), MAX_ASSISTANT_CHARS)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String shorten(String text, int max) {
        String s = text.strip();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
