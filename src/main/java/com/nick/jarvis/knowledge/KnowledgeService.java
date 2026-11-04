package com.nick.jarvis.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.nick.jarvis.dto.KnowledgeDocumentView;
import com.nick.jarvis.entity.KnowledgeDocument;
import com.nick.jarvis.mapper.KnowledgeDocumentMapper;
import com.nick.jarvis.support.JarvisTime;

/**
 * 知识库资料的管理：上传、列表、删除（进回收站）、恢复、重试、到期永久删除。
 *
 * 两个库的数据以 MySQL 为准（唯一的真相来源），PostgreSQL 里的向量块跟着它走：
 * - 删除：先在 MySQL 标记删除，再删向量块。删块失败也没关系，检索只看 READY 的资料，残留的块不会被搜到；
 * - 永久删除：顺序反过来，先删向量块，再删 MySQL 的记录。否则删块失败时，就没有任何记录指向这些块了。
 *
 * 每个操作都校验资料属于当前用户，“不存在”和“不属于你”统一返回 404，和会话管理一样。
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    static final long MAX_FILE_BYTES = 1024 * 1024;
    static final Set<String> ALLOWED_EXTENSIONS = Set.of(".txt", ".md");
    static final int MAX_DOCUMENTS_PER_USER = 100;
    static final int MAX_FILENAME_LENGTH = 200;
    /** 回收站保留时间 */
    static final Duration RETENTION = Duration.ofDays(7);
    private static final int PURGE_BATCH = 100;

    private final KnowledgeDocumentMapper mapper;
    private final DocumentProcessor processor;
    private final ApplicationEventPublisher events;
    private final JarvisTime time;

    public KnowledgeService(KnowledgeDocumentMapper mapper, DocumentProcessor processor,
                            ApplicationEventPublisher events, JarvisTime time) {
        this.mapper = mapper;
        this.processor = processor;
        this.events = events;
        this.time = time;
    }

    /**
     * 上传一份资料。只做校验和入库，马上返回；切块、向量化在后台进行，页面通过 status 查看进度。
     */
    public KnowledgeDocumentView upload(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw badRequest("请选择一个文件");
        }
        String filename = cleanFilename(file.getOriginalFilename());
        String lower = filename.toLowerCase(Locale.ROOT);
        if (ALLOWED_EXTENSIONS.stream().noneMatch(lower::endsWith)) {
            throw badRequest("目前只支持 .txt 和 .md 文件");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "文件不能超过 1 MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw badRequest("读取文件失败，请重新上传");
        }
        String text = TextDecoder.decode(bytes);
        // 图片等二进制文件改了后缀也能传上来，它们的内容里通常有 \0；PostgreSQL 的 text 类型存不了 \0
        if (text.indexOf('\0') >= 0) {
            throw badRequest("这个文件看起来不是文本文件");
        }
        if (text.isBlank()) {
            throw badRequest("文件里没有文字内容");
        }

        Long count = mapper.selectCount(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getUserId, userId));
        if (count >= MAX_DOCUMENTS_PER_USER) {
            throw badRequest("资料数量已达上限 " + MAX_DOCUMENTS_PER_USER + " 份（包括回收站里的），请先删除一些");
        }
        // 对解码后的文字算哈希：同一份内容，不管原来是 UTF-8 还是 GBK 编码，都算同一份
        String hash = sha256(text);
        KnowledgeDocument existing = mapper.selectOne(KnowledgeQueries.withoutContent()
                .eq(KnowledgeDocument::getUserId, userId)
                .eq(KnowledgeDocument::getContentHash, hash));
        if (existing != null) {
            throw duplicate(existing);
        }

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setUserId(userId);
        doc.setFilename(filename);
        doc.setSizeBytes((int) file.getSize());
        doc.setContent(text);
        doc.setContentHash(hash);
        doc.setStatus(KnowledgeDocument.STATUS_PROCESSING);
        doc.setChunkCount(0);
        doc.setAttempts(0);
        // 去掉毫秒，原因见邮件发件箱：MySQL 的 DATETIME 会四舍五入，可能存成“下一秒”
        doc.setNextAttemptAt(time.now().withNano(0));
        try {
            mapper.insert(doc);
        } catch (DuplicateKeyException e) {
            // 同一份资料几乎同时上传了两次，唯一索引兜底
            throw new ResponseStatusException(HttpStatus.CONFLICT, "这份资料已经上传过了");
        }
        events.publishEvent(new DocumentQueuedEvent(doc.getId()));
        return toView(doc);
    }

    /** @param trash true 查回收站，false 查正常的资料 */
    public List<KnowledgeDocumentView> list(Long userId, boolean trash) {
        LambdaQueryWrapper<KnowledgeDocument> query = KnowledgeQueries.withoutContent()
                .eq(KnowledgeDocument::getUserId, userId);
        if (trash) {
            query.isNotNull(KnowledgeDocument::getDeletedAt).orderByDesc(KnowledgeDocument::getDeletedAt);
        } else {
            query.isNull(KnowledgeDocument::getDeletedAt).orderByDesc(KnowledgeDocument::getId);
        }
        return mapper.selectList(query).stream().map(KnowledgeService::toView).toList();
    }

    /**
     * 移入回收站：先改 MySQL（真相来源），再删向量块。
     * status 和 deleted_at 在同一条 UPDATE 里一起改；chunk_count 清零，因为向量块马上就要删掉了。
     * 如果资料正在处理，处理流程最后标记 READY 时会因为 status 不再是 PROCESSING 而失败，
     * 并发现它已被删除，自己再删一次向量块（见 DocumentProcessor.markReady）。
     */
    public void delete(Long userId, Long documentId) {
        findOwned(userId, documentId, false);
        LocalDateTime now = time.now();
        int updated = mapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .set(KnowledgeDocument::getStatus, KnowledgeDocument.STATUS_DELETED)
                .set(KnowledgeDocument::getChunkCount, 0)
                .set(KnowledgeDocument::getDeletedAt, now)
                .set(KnowledgeDocument::getUpdateTime, now)
                .eq(KnowledgeDocument::getId, documentId)
                .isNull(KnowledgeDocument::getDeletedAt));
        if (updated == 0) {
            return;
        }
        try {
            processor.deleteChunks(documentId);
        } catch (RuntimeException e) {
            // 不影响删除结果：资料已经不是“可检索”的了，残留的块搜不到，7 天后永久删除时会再删一次
            log.warn("Failed to delete chunks of document #{}, will retry when purging", documentId, e);
        }
    }

    /** 从回收站恢复：向量块在删除时已经删掉了，所以改回 PROCESSING，重新走一遍处理流程 */
    public void restore(Long userId, Long documentId) {
        findOwned(userId, documentId, true);
        LocalDateTime now = time.now();
        int updated = mapper.update(null, requeue(now)
                .set(KnowledgeDocument::getDeletedAt, null)
                .eq(KnowledgeDocument::getId, documentId)
                .eq(KnowledgeDocument::getStatus, KnowledgeDocument.STATUS_DELETED)
                .isNotNull(KnowledgeDocument::getDeletedAt));
        if (updated == 1) {
            events.publishEvent(new DocumentQueuedEvent(documentId));
        }
    }

    /** 处理失败后手动重试 */
    public void retry(Long userId, Long documentId) {
        KnowledgeDocument doc = findOwned(userId, documentId, false);
        if (!KnowledgeDocument.STATUS_FAILED.equals(doc.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有处理失败的资料才能重试");
        }
        int updated = mapper.update(null, requeue(time.now())
                .eq(KnowledgeDocument::getId, documentId)
                .eq(KnowledgeDocument::getStatus, KnowledgeDocument.STATUS_FAILED)
                .isNull(KnowledgeDocument::getDeletedAt));
        if (updated == 1) {
            events.publishEvent(new DocumentQueuedEvent(documentId));
        }
    }

    /**
     * 永久删除回收站里满 7 天的资料，由定时任务调用。
     * 顺序是先删向量块、再删记录：只要还有数据依赖这条记录，就先别删它。
     */
    public int purgeExpired() {
        LocalDateTime cutoff = time.now().minus(RETENTION);
        List<KnowledgeDocument> expired = mapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                .select(KnowledgeDocument::getId)
                .eq(KnowledgeDocument::getStatus, KnowledgeDocument.STATUS_DELETED)
                .lt(KnowledgeDocument::getDeletedAt, cutoff)
                .last("LIMIT " + PURGE_BATCH));
        int purged = 0;
        for (KnowledgeDocument doc : expired) {
            try {
                processor.deleteChunks(doc.getId());
                // 条件里再判断一次“还在回收站且已满 7 天”：用户可能正好在这一刻点了恢复
                purged += mapper.delete(new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getId, doc.getId())
                        .eq(KnowledgeDocument::getStatus, KnowledgeDocument.STATUS_DELETED)
                        .lt(KnowledgeDocument::getDeletedAt, cutoff));
            } catch (RuntimeException e) {
                log.warn("Failed to purge document #{}, will retry next time", doc.getId(), e);
            }
        }
        if (purged > 0) {
            log.info("Purged {} documents from the trash", purged);
        }
        return purged;
    }

    /** 重新排队处理：状态改回 PROCESSING，次数清零，马上可以处理 */
    private static LambdaUpdateWrapper<KnowledgeDocument> requeue(LocalDateTime now) {
        return new LambdaUpdateWrapper<KnowledgeDocument>()
                .set(KnowledgeDocument::getStatus, KnowledgeDocument.STATUS_PROCESSING)
                .set(KnowledgeDocument::getAttempts, 0)
                .set(KnowledgeDocument::getChunkCount, 0)
                .set(KnowledgeDocument::getLastError, null)
                .set(KnowledgeDocument::getNextAttemptAt, now.withNano(0))
                .set(KnowledgeDocument::getUpdateTime, now);
    }

    /** 查出属于当前用户的资料；inTrash 表示要找的是回收站里的还是正常的。找不到一律 404 */
    private KnowledgeDocument findOwned(Long userId, Long documentId, boolean inTrash) {
        LambdaQueryWrapper<KnowledgeDocument> query = KnowledgeQueries.withoutContent()
                .eq(KnowledgeDocument::getId, documentId)
                .eq(KnowledgeDocument::getUserId, userId);
        if (inTrash) {
            query.isNotNull(KnowledgeDocument::getDeletedAt);
        } else {
            query.isNull(KnowledgeDocument::getDeletedAt);
        }
        KnowledgeDocument doc = mapper.selectOne(query);
        if (doc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到这份资料");
        }
        return doc;
    }

    private static ResponseStatusException duplicate(KnowledgeDocument existing) {
        String message = existing.getDeletedAt() != null
                ? "这份资料在回收站里（" + existing.getFilename() + "），可以直接恢复"
                : "这份资料已经上传过了（" + existing.getFilename() + "）";
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    /** 只保留文件名本身：有些浏览器会带上完整路径，比如 C:\Users\...\notes.txt */
    static String cleanFilename(String original) {
        if (original == null) {
            throw badRequest("文件名不能为空");
        }
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty()) {
            throw badRequest("文件名不能为空");
        }
        if (name.length() > MAX_FILENAME_LENGTH) {
            throw badRequest("文件名太长了，最多 " + MAX_FILENAME_LENGTH + " 个字");
        }
        return name;
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // 每个 Java 运行环境都必须支持 SHA-256，不会走到这里
            throw new IllegalStateException(e);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static KnowledgeDocumentView toView(KnowledgeDocument doc) {
        LocalDateTime purgeAt = doc.getDeletedAt() == null ? null : doc.getDeletedAt().plus(RETENTION);
        return new KnowledgeDocumentView(doc.getId(), doc.getFilename(), doc.getSizeBytes(), doc.getStatus(),
                doc.getChunkCount(), doc.getLastError(), doc.getCreateTime(), doc.getDeletedAt(), purgeAt);
    }
}
