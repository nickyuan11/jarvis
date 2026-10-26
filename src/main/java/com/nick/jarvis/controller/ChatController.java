package com.nick.jarvis.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nick.jarvis.dto.ChatReply;
import com.nick.jarvis.dto.ChatRequest;
import com.nick.jarvis.service.ChatService;

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

    @PostMapping
    public ChatReply chat(@RequestBody ChatRequest request) {
        return chatService.chat(request.conversationId(), request.message());
    }
}
