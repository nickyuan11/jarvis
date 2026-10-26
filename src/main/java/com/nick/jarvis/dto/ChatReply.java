package com.nick.jarvis.dto;

/**
 * 聊天响应。
 */
public record ChatReply(
        Long conversationId,
        String reply,
        String model,
        Integer promptTokens,
        Integer completionTokens) {
}
