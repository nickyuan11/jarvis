package com.nick.jarvis.dto;

import java.time.LocalDateTime;

/**
 * 会话里的一条消息。
 */
public record MessageView(Long id, String role, String content, LocalDateTime createTime) {
}
