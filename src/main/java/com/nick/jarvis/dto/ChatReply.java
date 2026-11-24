package com.nick.jarvis.dto;

import java.util.List;

/**
 * 聊天响应。
 */
public record ChatReply(
        Long conversationId,
        String reply,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Long messageId,
        List<Integer> citations) {
}
