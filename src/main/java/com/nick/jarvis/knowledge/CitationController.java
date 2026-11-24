package com.nick.jarvis.knowledge;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nick.jarvis.entity.KnowledgeDocument;
import com.nick.jarvis.mapper.KnowledgeDocumentMapper;
import com.nick.jarvis.mapper.MessageMapper;
import com.nick.jarvis.security.CurrentUser;

@RestController
public class CitationController {
    public record Preview(String filename, String before, String excerpt, String after) {
    }

    private final MessageMapper messages;
    private final KnowledgeDocumentMapper documents;

    public CitationController(MessageMapper messages, KnowledgeDocumentMapper documents) {
        this.messages = messages;
        this.documents = documents;
    }

    @GetMapping("/api/messages/{messageId}/citations/{number}")
    public ResponseEntity<Preview> preview(@PathVariable long messageId, @PathVariable int number) {
        long userId = CurrentUser.id();
        var citation = ReplyCitations.decode(messages.ownedCitations(messageId, userId)).stream()
                .filter(c -> c.number() == number).findFirst().orElseThrow(CitationController::unavailable);
        // 每次打开都重新检查资料归属和状态，恢复并处理完成后，原来的链接可以继续使用。
        var doc = documents.selectOne(new LambdaQueryWrapper<KnowledgeDocument>()
                .select(KnowledgeDocument::getFilename, KnowledgeDocument::getContent)
                .eq(KnowledgeDocument::getId, citation.documentId())
                .eq(KnowledgeDocument::getUserId, userId)
                .eq(KnowledgeDocument::getStatus, KnowledgeDocument.STATUS_READY)
                .isNull(KnowledgeDocument::getDeletedAt));
        SourceRange range = citation.range();
        if (doc == null || !range.fits(doc.getContent().length())) throw unavailable();
        String text = doc.getContent();
        Preview preview = new Preview(doc.getFilename(), text.substring(range.contextStart(), range.start()),
                text.substring(range.start(), range.end()), text.substring(range.end(), range.contextEnd()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preview);
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "引用来源暂不可用");
    }
}
