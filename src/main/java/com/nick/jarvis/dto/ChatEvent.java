package com.nick.jarvis.dto;

import java.util.List;

/**
 * 流式接口推送给前端的事件内容，每个事件的 data 都是一段 JSON。
 * 用 JSON 而不是直接推纯文本，是因为 SSE 会吞掉 data 开头的空格、按换行拆分内容，
 * 包一层 JSON 可以原样保留模型输出的空格和换行。
 */
public final class ChatEvent {

    private ChatEvent() {
    }

    /** 第一个事件：告诉前端这次对话属于哪个会话（新会话时前端需要记住它） */
    public record Meta(Long conversationId) {
    }

    /** 中间的事件：模型新吐出的一小段文字 */
    public record Delta(String text) {
    }

    /** 最后一个事件：本次回复结束，附带模型和 token 用量 */
    public record Done(String model, Integer promptTokens, Integer completionTokens, Long messageId, List<Integer> citations) {
    }

    /** 出错时推送 */
    public record Error(String message) {
    }
}
