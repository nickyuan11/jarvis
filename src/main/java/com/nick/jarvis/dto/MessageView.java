package com.nick.jarvis.dto;

import java.time.Instant;

/**
 * 会话里的一条消息。
 */
public record MessageView(Long id, String role, String content, Instant createTime) {
}
