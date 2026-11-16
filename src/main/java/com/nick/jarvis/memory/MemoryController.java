package com.nick.jarvis.memory;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.nick.jarvis.security.CurrentUser;

/**
 * 记忆接口：让用户看到 Jarvis 记住了自己什么，并且能删掉任意一条。
 *   GET    /api/memories        全部记忆
 *   DELETE /api/memories/{id}   删除一条
 */
@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    /** 返回给前端的一条记忆 */
    public record MemoryView(Long id, String content, String type, String source,
                             java.time.LocalDateTime updateTime) {
    }

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping
    public List<MemoryView> list() {
        return memoryService.list(CurrentUser.id()).stream()
                .map(m -> new MemoryView(m.getId(), m.getContent(), m.getType(), m.getSource(), m.getUpdateTime()))
                .toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        try {
            memoryService.forget(CurrentUser.id(), id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到这条记忆");
        }
        return ResponseEntity.noContent().build();
    }
}
