package com.nick.jarvis.memory;

import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nick.jarvis.entity.UserMemory;
import com.nick.jarvis.mapper.UserMemoryMapper;

/**
 * 长期记忆：跨会话记住关于用户本人、长期有效的信息。
 *
 * 用法：每次对话都把这个用户的全部记忆放进系统提示词（见 promptSection），而不是像知识库那样检索。
 * 原因：一个人的记忆条数不多，全部带上也就一两千 token；而检索可能漏掉“平时用不上、一用上就很关键”的记忆，
 * 比如用户说“推荐一款零食”，“零食”和“对花生过敏”的向量未必相近。宁可多花一点 token，也不能给出错误的建议。
 *
 * 校验失败时抛出 IllegalArgumentException，消息写得让大模型也能看懂，工具层原样交给模型向用户解释。
 */
@Service
public class MemoryService {

    /** 每个用户最多多少条：给 token 消耗设个上限 */
    public static final int MAX_MEMORIES = 100;
    /** 每条最多多少字 */
    public static final int MAX_LENGTH = 100;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final UserMemoryMapper mapper;

    public MemoryService(UserMemoryMapper mapper) {
        this.mapper = mapper;
    }

    /** 偏好在前，事实在后；同类里最近更新的在前 */
    public List<UserMemory> list(Long userId) {
        return mapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .orderByDesc(UserMemory::getType)
                .orderByDesc(UserMemory::getUpdateTime));
    }

    /**
     * 记住一条。内容和已有的某条完全一样时，不重复保存，直接返回已有的那条。
     *
     * @param conversationId 来自哪个会话，可以为空
     */
    public UserMemory remember(Long userId, String content, String type, String source, Long conversationId) {
        String text = validate(content);
        String memoryType = parseType(type);

        UserMemory existing = mapper.selectOne(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getContent, text)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        Long count = mapper.selectCount(new LambdaQueryWrapper<UserMemory>().eq(UserMemory::getUserId, userId));
        if (count >= MAX_MEMORIES) {
            throw new IllegalArgumentException("记忆已满 " + MAX_MEMORIES + " 条，请先让用户删掉一些不再需要的");
        }

        UserMemory memory = new UserMemory();
        memory.setUserId(userId);
        memory.setContent(text);
        memory.setType(memoryType);
        memory.setSource(source);
        memory.setConversationId(conversationId);
        mapper.insert(memory);
        return memory;
    }

    /**
     * 改写一条已有的记忆（后台提取发现它过时了）。type 为空时保持原来的类型。
     * 和新增走同样的校验：敏感信息不会因为是“更新”就漏过去。
     */
    public UserMemory update(Long userId, Long memoryId, String content, String type) {
        String text = validate(content);
        UserMemory memory = memoryId == null ? null : mapper.selectById(memoryId);
        if (memory == null || !userId.equals(memory.getUserId())) {
            throw new IllegalArgumentException("找不到编号为 " + memoryId + " 的记忆");
        }
        String memoryType = type == null || type.isBlank() ? memory.getType() : parseType(type);
        // 新建一个只带要改的字段的对象：updateById 只更新非空字段，update_time 由自动填充写入
        UserMemory update = new UserMemory();
        update.setId(memoryId);
        update.setContent(text);
        update.setType(memoryType);
        mapper.updateById(update);
        memory.setContent(text);
        memory.setType(memoryType);
        return memory;
    }

    /** 新增和更新共用的内容校验，返回去掉首尾空白后的内容 */
    private static String validate(String content) {
        String text = content == null ? "" : content.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("一条记忆不能超过 " + MAX_LENGTH + " 个字，请精简后再记");
        }
        if (SensitiveInfoFilter.containsSensitiveInfo(text)) {
            throw new IllegalArgumentException("银行卡号、身份证号、密码这类敏感信息不能保存到记忆里，即使用户要求也不行");
        }
        return text;
    }

    /** 忘掉一条（物理删除）。“不存在”和“不属于你”给出同样的提示，和其他地方的越权校验一致 */
    public UserMemory forget(Long userId, Long memoryId) {
        UserMemory memory = memoryId == null ? null : mapper.selectById(memoryId);
        if (memory == null || !userId.equals(memory.getUserId())) {
            throw new IllegalArgumentException("找不到编号为 " + memoryId + " 的记忆");
        }
        mapper.deleteById(memoryId);
        return memory;
    }

    /**
     * 放进系统提示词的一段文字。没有记忆时返回空字符串。
     *
     * 偏好和事实分开写，因为用法不同：偏好每次回答都要遵守，事实只在相关时参考。
     * 每条带上编号（用户说“忘掉”时，模型可以直接按编号删除）和记录日期（让模型判断新旧）。
     */
    public String promptSection(Long userId) {
        List<UserMemory> memories = list(userId);
        if (memories.isEmpty()) {
            return "";
        }
        StringBuilder preferences = new StringBuilder();
        StringBuilder facts = new StringBuilder();
        for (UserMemory m : memories) {
            StringBuilder target = UserMemory.TYPE_PREFERENCE.equals(m.getType()) ? preferences : facts;
            target.append("- #").append(m.getId()).append(' ').append(m.getContent());
            if (m.getUpdateTime() != null) {
                target.append("（记录于 ").append(m.getUpdateTime().format(DATE)).append('）');
            }
            target.append('\n');
        }
        StringBuilder sb = new StringBuilder("\n\n## 你记得的关于用户的信息\n");
        if (!preferences.isEmpty()) {
            sb.append("用户对你回答方式的要求（每次回答都要遵守）：\n").append(preferences);
        }
        if (!facts.isEmpty()) {
            sb.append("关于用户的事实（相关时参考，比如推荐、建议时要考虑到。记录时间较早的可能已经过时，")
                    .append("和用户最新说的矛盾时，以用户最新说的为准）：\n").append(facts);
        }
        return sb.toString();
    }

    static String parseType(String type) {
        if (type == null || type.isBlank()) {
            return UserMemory.TYPE_FACT;
        }
        String t = type.strip().toUpperCase();
        if (UserMemory.TYPE_PREFERENCE.equals(t) || UserMemory.TYPE_FACT.equals(t)) {
            return t;
        }
        throw new IllegalArgumentException("记忆类型只能是 PREFERENCE（回答方式的要求）或 FACT（关于用户的事实），收到的是 " + type);
    }
}
