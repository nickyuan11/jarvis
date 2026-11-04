package com.nick.jarvis.knowledge;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.nick.jarvis.dto.KnowledgeDocumentView;
import com.nick.jarvis.security.CurrentUser;

/**
 * 知识库资料接口：
 *   GET    /api/knowledge/documents               资料列表（?trash=true 查回收站）
 *   POST   /api/knowledge/documents               上传（multipart/form-data，字段名 file），返回 201
 *   DELETE /api/knowledge/documents/{id}          移入回收站
 *   POST   /api/knowledge/documents/{id}/restore  从回收站恢复
 *   POST   /api/knowledge/documents/{id}/retry    处理失败后重试
 * restore、retry 不是对资源字段的修改，而是“动作”，所以用 POST + 动词路径，这是 RESTful 里常见的折中写法。
 */
@RestController
@RequestMapping("/api/knowledge/documents")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping
    public List<KnowledgeDocumentView> list(@RequestParam(defaultValue = "false") boolean trash) {
        return knowledgeService.list(CurrentUser.id(), trash);
    }

    @PostMapping
    public ResponseEntity<KnowledgeDocumentView> upload(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(knowledgeService.upload(CurrentUser.id(), file));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        knowledgeService.delete(CurrentUser.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable Long id) {
        knowledgeService.restore(CurrentUser.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable Long id) {
        knowledgeService.retry(CurrentUser.id(), id);
        return ResponseEntity.noContent().build();
    }
}
