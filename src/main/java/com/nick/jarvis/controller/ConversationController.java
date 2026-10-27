package com.nick.jarvis.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nick.jarvis.dto.ConversationView;
import com.nick.jarvis.dto.MessageView;
import com.nick.jarvis.dto.RenameRequest;
import com.nick.jarvis.service.ConversationService;

/**
 * RESTful 风格：用 URL 表示“资源”，用 HTTP 方法表示“操作”。
 *   GET    /api/conversations                 查列表
 *   GET    /api/conversations/{id}/messages   查某个会话的消息
 *   PATCH  /api/conversations/{id}            修改一部分字段（重命名）
 *   DELETE /api/conversations/{id}            删除
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    public List<ConversationView> list() {
        return conversationService.list();
    }

    @GetMapping("/{id}/messages")
    public List<MessageView> messages(@PathVariable Long id) {
        return conversationService.messages(id);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Void> rename(@PathVariable Long id, @RequestBody RenameRequest request) {
        conversationService.rename(id, request.title());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        conversationService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
