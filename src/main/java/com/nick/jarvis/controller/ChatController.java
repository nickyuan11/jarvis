package com.nick.jarvis.controller;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nick.jarvis.dto.ChatReply;
import com.nick.jarvis.dto.ChatRequest;
import com.nick.jarvis.service.ChatService;

import reactor.core.publisher.Flux;

/**
 * Controller 只负责接收请求、返回结果，业务逻辑都放在 Service 里。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    // 只有一个构造器时，Spring 会自动用它注入依赖，不需要 @Autowired
    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /** 一次性返回 */
    @PostMapping
    public ChatReply chat(@RequestBody ChatRequest request) {
        return chatService.chat(request.conversationId(), request.message(), request.timeZone());
    }

    /**
     * 流式返回。produces = text/event-stream 表示响应是 SSE 格式：
     * 连接保持打开，服务端可以持续往里写事件，直到写完再关闭。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(@RequestBody ChatRequest request) {
        return chatService.streamChat(request.conversationId(), request.message(), request.timeZone());
    }
}
