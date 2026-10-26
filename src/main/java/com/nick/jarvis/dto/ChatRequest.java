package com.nick.jarvis.dto;

/**
 * 聊天请求。
 * record 是 Java 16+ 的语法，自动生成构造器、getter、equals 和 toString，适合做纯数据对象。
 *
 * @param conversationId 会话 id；为空表示开启一个新会话
 * @param message        用户这次说的话
 */
public record ChatRequest(Long conversationId, String message) {
}
