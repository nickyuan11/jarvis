package com.nick.jarvis.tools;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.nick.jarvis.entity.Reminder;
import com.nick.jarvis.reminder.ReminderService;
import com.nick.jarvis.reminder.RepeatType;
import com.nick.jarvis.support.JarvisTime;

/**
 * 交给大模型使用的“提醒”工具。
 *
 * @Tool 的 description 和 @ToolParam 的描述会被发给大模型，它靠这些文字判断什么时候调用、参数怎么填，
 * 所以描述要像给同事写的接口文档一样清楚。
 *
 * 工具方法返回的字符串会被交回给大模型，由它组织成自然语言回复用户。
 * 出错时也返回一段说明（而不是抛异常），让大模型能向用户解释，或者修正参数后重试。
 */
@Component
public class ReminderTools {

    private final ReminderService reminderService;
    private final JarvisTime time;

    public ReminderTools(ReminderService reminderService, JarvisTime time) {
        this.reminderService = reminderService;
        this.time = time;
    }

    @Tool(description = """
            为用户创建一条提醒。用户说“提醒我……”“……的时候叫我”“别让我忘了……”时使用。
            相对时间（明天、下周一、半小时后）请先根据系统提示里的当前时间换算成具体时间。
            用户没说具体时刻时，不要自己编，先向用户确认。""")
    public String createReminder(
            @ToolParam(description = "提醒内容，简洁描述要做的事，例如“参加面试”") String content,
            @ToolParam(description = "提醒时间，格式 yyyy-MM-dd HH:mm，例如 2026-11-02 15:00") String remindAt,
            @ToolParam(description = "重复方式：NONE（不重复，默认）、DAILY（每天）、WEEKLY（每周）、MONTHLY（每月）",
                    required = false) String repeatType,
            ToolContext toolContext) {
        try {
            LocalDateTime time = LocalDateTime.parse(remindAt.strip(), ReminderService.FORMAT);
            Reminder reminder = reminderService.create(userId(toolContext), content, time, RepeatType.parse(repeatType));
            return "已创建提醒 " + ReminderService.describe(reminder);
        } catch (DateTimeParseException e) {
            return "创建失败：时间格式不对，需要 yyyy-MM-dd HH:mm，收到的是 " + remindAt;
        } catch (IllegalArgumentException e) {
            return "创建失败：" + e.getMessage();
        }
    }

    @Tool(description = """
            列出用户的提醒事项：所有待提醒的，以及最近 24 小时内已经提醒过或已取消的。
            用户问“我有哪些提醒”“这周有什么安排”“刚才那个提醒响了吗”时使用；取消提醒前也可以先用它找到编号。""")
    public String listReminders(ToolContext toolContext) {
        Long userId = userId(toolContext);
        List<Reminder> pending = reminderService.listPending(userId);
        // 只给待提醒的，大模型看不到“已经提醒过”的事项，会误以为提醒没设置成功
        List<Reminder> finished = reminderService.listRecentlyFinished(userId, time.now().minusHours(24));

        StringBuilder sb = new StringBuilder();
        if (pending.isEmpty()) {
            sb.append("当前没有待提醒的事项\n");
        } else {
            sb.append("待提醒：\n");
            pending.forEach(r -> sb.append(ReminderService.describe(r)).append('\n'));
        }
        if (!finished.isEmpty()) {
            sb.append("最近 24 小时已提醒或已取消：\n");
            finished.forEach(r -> sb.append(ReminderService.describe(r)).append('\n'));
        }
        return sb.toString();
    }

    @Tool(description = "取消一条待提醒的事项。不知道编号时，先调用 listReminders 查找。")
    public String cancelReminder(
            @ToolParam(description = "提醒编号，即列表中 # 后面的数字") Long reminderId,
            ToolContext toolContext) {
        try {
            Reminder reminder = reminderService.cancel(userId(toolContext), reminderId);
            return "已取消提醒 " + ReminderService.describe(reminder);
        } catch (IllegalArgumentException e) {
            return "取消失败：" + e.getMessage();
        }
    }

    /**
     * 当前用户 id 通过 ToolContext 传进来，而不是用 CurrentUser.id()：
     * 流式对话时工具可能在别的线程上执行，拿不到 SecurityContext（它是 ThreadLocal）。
     * ToolContext 的内容只在服务端内部传递，不会发给大模型，大模型也无法篡改。
     */
    private static Long userId(ToolContext toolContext) {
        return (Long) toolContext.getContext().get(ToolContextKeys.USER_ID);
    }
}
