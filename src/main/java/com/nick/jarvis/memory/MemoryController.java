package com.nick.jarvis.memory;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.nick.jarvis.entity.UserMemory;
import com.nick.jarvis.security.CurrentUser;
import com.nick.jarvis.support.JarvisTime;

/**
 * 记忆接口：让用户看到 Jarvis 记住了自己什么，并且能添加、删掉任意一条。
 *   GET    /api/memories        全部记忆
 *   POST   /api/memories        手动添加一条，返回 201
 *   DELETE /api/memories/{id}   删除一条
 *
 * 添加时的校验（非空、字数、条数上限、敏感信息）都在 MemoryService 里，
 * 和“记住”工具、后台提取走的是同一个入口，这里不用重写一遍。
 */
@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    /** 返回给前端的一条记忆 */
    public record MemoryView(Long id, String content, String type, String source, Instant updateTime) {
    }

    /** 手动添加的请求体。type 不填时默认是 FACT */
    public record CreateRequest(String content, String type) {
    }

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping
    public List<MemoryView> list() {
        return memoryService.list(CurrentUser.id()).stream().map(MemoryController::toView).toList();
    }

    @PostMapping
    public ResponseEntity<MemoryView> create(@RequestBody CreateRequest request) {
        try {
            // 用户亲手添加的，来源算“明确要求记住”
            UserMemory memory = memoryService.remember(CurrentUser.id(), request.content(), request.type(),
                    UserMemory.SOURCE_EXPLICIT, null);
            return ResponseEntity.status(HttpStatus.CREATED).body(toView(memory));
        } catch (IllegalArgumentException e) {
            // MemoryService 的提示本来是写给大模型看的，但说的都是人话，直接返回给页面
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
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

    private static MemoryView toView(UserMemory m) {
        return new MemoryView(m.getId(), m.getContent(), m.getType(), m.getSource(), JarvisTime.instant(m.getUpdateTime()));
    }
}
