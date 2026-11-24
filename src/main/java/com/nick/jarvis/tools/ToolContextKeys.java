package com.nick.jarvis.tools;

import java.time.ZoneId;

import org.springframework.ai.chat.model.ToolContext;

public final class ToolContextKeys {

    public static final String USER_ID = "userId";
    /** 当前会话 id：记住一条记忆时，记下它来自哪个会话 */
    public static final String CONVERSATION_ID = "conversationId";

    public static final String TIME_ZONE = "timeZone";

    public static ZoneId zone(ToolContext context) {
        return (ZoneId) context.getContext().get(TIME_ZONE);
    }

    private ToolContextKeys() {
    }
}
