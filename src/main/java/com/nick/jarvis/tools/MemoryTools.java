package com.nick.jarvis.tools;

import java.util.List;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.nick.jarvis.entity.UserMemory;
import com.nick.jarvis.memory.MemoryService;

/**
 * 交给大模型使用的“长期记忆”工具：用户明确说“记住……”“忘掉……”时，立即执行。
 * 用户没明说、但值得记的事，由后台提取负责，不靠模型在聊天中途自己判断（不稳定）。
 */
@Component
public class MemoryTools {

    private final MemoryService memoryService;

    public MemoryTools(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Tool(description = """
            用户明确要求你记住某件事时，必须调用，例如“记住我对花生过敏”“以后回答简短一点”“记一下我在找 Java 后端的工作”。
            只记关于用户本人、长期有效的信息。提醒事项用提醒工具，不要记在这里；用户上传的资料已经在知识库里，也不要记。
            银行卡号、身份证号、密码等敏感信息不能记，用户要求也不行，请向用户说明原因。
            如果是修改已有的记忆（例如用户说“我已经不在找工作了”），先用 forgetMemory 删掉旧的，再记新的。""")
    public String rememberMemory(
            @ToolParam(description = "记忆内容：用第三人称简洁陈述，一两句话，例如“对花生过敏”“希望回答简短”") String content,
            @ToolParam(description = "类型：PREFERENCE（对你回答方式的要求，如简短、用英文）或 FACT（关于用户的事实，如过敏、职业）") String type,
            ToolContext toolContext) {
        try {
            UserMemory memory = memoryService.remember(userId(toolContext), content, type,
                    UserMemory.SOURCE_EXPLICIT, conversationId(toolContext));
            return "已记住 #" + memory.getId() + "：" + memory.getContent();
        } catch (IllegalArgumentException e) {
            return "没有记住：" + e.getMessage();
        }
    }

    @Tool(description = """
            用户要求你忘掉某件事时调用，例如“忘掉我对花生过敏这件事”“别再记着我在找工作了”。
            系统提示里的每条记忆前面有 #编号；找不到对应的编号时，先调用 listMemories。""")
    public String forgetMemory(
            @ToolParam(description = "记忆编号，即 # 后面的数字") Long memoryId,
            ToolContext toolContext) {
        try {
            UserMemory memory = memoryService.forget(userId(toolContext), memoryId);
            return "已忘掉 #" + memory.getId() + "：" + memory.getContent();
        } catch (IllegalArgumentException e) {
            return "删除失败：" + e.getMessage();
        }
    }

    @Tool(description = "列出你记得的关于用户的全部信息。用户问“你记得我什么”“你对我了解多少”时使用。")
    public String listMemories(ToolContext toolContext) {
        List<UserMemory> memories = memoryService.list(userId(toolContext));
        if (memories.isEmpty()) {
            return "目前没有记住任何关于用户的信息。";
        }
        StringBuilder sb = new StringBuilder("记得的信息（共 ").append(memories.size()).append(" 条）：\n");
        for (UserMemory m : memories) {
            sb.append("- #").append(m.getId()).append(' ').append(m.getContent())
                    .append(UserMemory.TYPE_PREFERENCE.equals(m.getType()) ? "（回答方式的要求）" : "")
                    .append(UserMemory.SOURCE_EXTRACTED.equals(m.getSource()) ? "（从对话中自动记下）" : "（用户要求记住）")
                    .append('\n');
        }
        return sb.toString();
    }

    private static Long userId(ToolContext toolContext) {
        return (Long) toolContext.getContext().get(ToolContextKeys.USER_ID);
    }

    private static Long conversationId(ToolContext toolContext) {
        Object id = toolContext.getContext().get(ToolContextKeys.CONVERSATION_ID);
        return id instanceof Long l ? l : null;
    }
}
