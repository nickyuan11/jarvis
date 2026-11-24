package com.nick.jarvis.dto;

import java.time.Instant;

/**
 * 会话列表里的一项。
 * 不直接把实体类返回给前端：实体里有 userId、deleted 等前端不需要的字段，
 * 而且以后表结构变了，接口格式也不应该跟着变。
 */
public record ConversationView(Long id, String title, Instant updateTime) {
}
