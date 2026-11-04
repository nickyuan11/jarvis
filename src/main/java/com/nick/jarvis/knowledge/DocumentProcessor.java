package com.nick.jarvis.knowledge;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.nick.jarvis.entity.KnowledgeDocument;
import com.nick.jarvis.mapper.KnowledgeDocumentMapper;
import com.nick.jarvis.support.Exceptions;
import com.nick.jarvis.support.JarvisTime;

/**
 * 处理一份资料：切块 → 向量化 → 写入 pgvector → 标记为 READY。
 *
 * 和邮件发件箱是同一套思路：
 * 1. 抢占：条件更新（status = PROCESSING 且 attempts = 原值），同一时刻只有一个在处理；
 * 2. 租约：抢占的同时把 next_attempt_at 推后 10 分钟。处理途中应用崩了，租约到期后定时任务会重新扫到它；
 * 3. 幂等：处理的第一步是删掉这份资料已有的块，上次处理到一半留下的也一起清掉，重跑多少次结果都一样；
 * 4. 失败重试：隔几分钟自动重试，最多 3 次，之后标记 FAILED，用户可以在页面上手动重试。
 */
@Service
public class DocumentProcessor {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessor.class);

    /** 租约：要明显长于处理一份 1 MB 资料的时间（几百块、每批 10 块，一般一两分钟） */
    static final Duration LEASE = Duration.ofMinutes(10);
    static final int MAX_ATTEMPTS = 3;
    /** 第 n 次失败后等 n × 2 分钟再试 */
    static final Duration RETRY_DELAY = Duration.ofMinutes(2);
    /** 百炼 qwen3.7-text-embedding 一次最多接受 20 条，留出余量 */
    static final int BATCH_SIZE = 10;
    private static final int SCAN_SIZE = 10;
    private static final int MAX_ERROR_LENGTH = 500;

    public enum Result {
        READY,
        FAILED,
        /** 没抢到，或者处理期间资料被删除、被别人重新抢走了 */
        SKIPPED
    }

    private final KnowledgeDocumentMapper mapper;
    private final VectorStore vectorStore;
    private final JarvisTime time;
    private final TextChunker chunker = new TextChunker(500, 80);

    public DocumentProcessor(KnowledgeDocumentMapper mapper, VectorStore vectorStore, JarvisTime time) {
        this.mapper = mapper;
        this.vectorStore = vectorStore;
        this.time = time;
    }

    /**
     * 上传、恢复、手动重试后，事务提交了就立即在后台处理，不用等定时任务。
     * 和邮件发件箱的 onQueued 一样：立即处理只是为了快，定时任务才是兜底。
     */
    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onQueued(DocumentQueuedEvent event) {
        KnowledgeDocument doc = mapper.selectOne(KnowledgeQueries.withoutContent()
                .eq(KnowledgeDocument::getId, event.documentId()));
        if (doc != null) {
            process(doc);
        }
    }

    /** 处理到期的资料（新上传但没被立即处理的、等待重试的、租约过期的），由定时任务调用 */
    public int processDue() {
        List<KnowledgeDocument> due = mapper.selectList(KnowledgeQueries.withoutContent()
                .eq(KnowledgeDocument::getStatus, KnowledgeDocument.STATUS_PROCESSING)
                .isNull(KnowledgeDocument::getDeletedAt)
                .le(KnowledgeDocument::getNextAttemptAt, time.now())
                .orderByAsc(KnowledgeDocument::getNextAttemptAt)
                .last("LIMIT " + SCAN_SIZE));
        int ready = 0;
        for (KnowledgeDocument doc : due) {
            Result result = process(doc);
            if (result == Result.READY) {
                ready++;
            } else if (result == Result.FAILED) {
                // 和发件箱一样的简单熔断：失败多半是向量化接口的问题（限流、Key 失效），接着处理剩下的大概率也失败
                break;
            }
        }
        return ready;
    }

    Result process(KnowledgeDocument doc) {
        LocalDateTime now = time.now();
        int attempts = doc.getAttempts() + 1;
        if (!claim(doc, now)) {
            return Result.SKIPPED;
        }
        // 抢到了才去查原文
        KnowledgeDocument full = mapper.selectById(doc.getId());
        if (full == null) {
            return Result.SKIPPED;
        }
        int chunkCount;
        try {
            chunkCount = index(full);
        } catch (RuntimeException e) {
            onFailure(full, attempts, now, e);
            return Result.FAILED;
        }
        return markReady(full, attempts, chunkCount) ? Result.READY : Result.SKIPPED;
    }

    /** 删掉一份资料的所有向量块。删除本身是幂等的：没有块、或者已经删过，再删一次也没关系 */
    public void deleteChunks(Long documentId) {
        vectorStore.delete(new FilterExpressionBuilder().eq("documentId", documentId).build());
    }

    private int index(KnowledgeDocument doc) {
        // 第一步先清掉旧块：上次可能处理到一半，或者这是从回收站恢复的资料
        deleteChunks(doc.getId());

        List<String> texts = chunker.split(doc.getContent());
        List<Document> chunks = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            // 元数据：检索时按 userId、documentId 过滤；注明出处时用 filename 和 chunkIndex
            chunks.add(new Document(texts.get(i), Map.of(
                    "userId", doc.getUserId(),
                    "documentId", doc.getId(),
                    "chunkIndex", i,
                    "filename", doc.getFilename())));
        }
        // add 会先调用百炼把每块变成向量，再写入 PostgreSQL；分批调用，每批不超过接口上限
        for (int from = 0; from < chunks.size(); from += BATCH_SIZE) {
            vectorStore.add(chunks.subList(from, Math.min(from + BATCH_SIZE, chunks.size())));
        }
        return chunks.size();
    }

    /**
     * 抢占：只有更新成功（影响 1 行）的那次才去处理。
     * 条件里的 deleted_at IS NULL：已经进了回收站的资料不再处理。
     */
    private boolean claim(KnowledgeDocument doc, LocalDateTime now) {
        return mapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .set(KnowledgeDocument::getAttempts, doc.getAttempts() + 1)
                .set(KnowledgeDocument::getNextAttemptAt, now.plus(LEASE).withNano(0))
                .set(KnowledgeDocument::getUpdateTime, now)
                .eq(KnowledgeDocument::getId, doc.getId())
                .eq(KnowledgeDocument::getStatus, KnowledgeDocument.STATUS_PROCESSING)
                .eq(KnowledgeDocument::getAttempts, doc.getAttempts())
                .le(KnowledgeDocument::getNextAttemptAt, now)
                .isNull(KnowledgeDocument::getDeletedAt)) == 1;
    }

    /**
     * 标记为 READY。条件里带上抢占时的 attempts：处理期间如果租约过期、被别人重新抢走了，
     * 这里就不会覆盖对方的结果。
     */
    private boolean markReady(KnowledgeDocument doc, int attempts, int chunkCount) {
        int updated = mapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .set(KnowledgeDocument::getStatus, KnowledgeDocument.STATUS_READY)
                .set(KnowledgeDocument::getChunkCount, chunkCount)
                .set(KnowledgeDocument::getLastError, null)
                .set(KnowledgeDocument::getUpdateTime, time.now())
                .eq(KnowledgeDocument::getId, doc.getId())
                .eq(KnowledgeDocument::getStatus, KnowledgeDocument.STATUS_PROCESSING)
                .eq(KnowledgeDocument::getAttempts, attempts)
                .isNull(KnowledgeDocument::getDeletedAt));
        if (updated == 1) {
            log.info("Document #{} for user {} is ready, {} chunks", doc.getId(), doc.getUserId(), chunkCount);
            return true;
        }
        // 处理期间状态变了。如果是用户把它删了：删除时向量块已经删过一次，
        // 但这次处理又写进去了一些，所以要再删一次，不留残留
        KnowledgeDocument latest = mapper.selectOne(KnowledgeQueries.withoutContent()
                .eq(KnowledgeDocument::getId, doc.getId()));
        if (latest == null || latest.getDeletedAt() != null) {
            deleteChunks(doc.getId());
        }
        log.info("Document #{} changed while processing (deleted or re-claimed), result discarded", doc.getId());
        return false;
    }

    private void onFailure(KnowledgeDocument doc, int attempts, LocalDateTime now, RuntimeException e) {
        String error = Exceptions.describe(e, MAX_ERROR_LENGTH);
        LambdaUpdateWrapper<KnowledgeDocument> update = new LambdaUpdateWrapper<KnowledgeDocument>()
                .set(KnowledgeDocument::getLastError, error)
                .set(KnowledgeDocument::getUpdateTime, now)
                .eq(KnowledgeDocument::getId, doc.getId())
                .eq(KnowledgeDocument::getStatus, KnowledgeDocument.STATUS_PROCESSING)
                .eq(KnowledgeDocument::getAttempts, attempts);
        if (attempts >= MAX_ATTEMPTS) {
            update.set(KnowledgeDocument::getStatus, KnowledgeDocument.STATUS_FAILED);
            mapper.update(null, update);
            log.error("Document #{} for user {} failed after {} attempts", doc.getId(), doc.getUserId(), attempts, e);
        } else {
            LocalDateTime next = now.plus(RETRY_DELAY.multipliedBy(attempts)).withNano(0);
            update.set(KnowledgeDocument::getNextAttemptAt, next);
            mapper.update(null, update);
            log.warn("Document #{} for user {} failed on attempt {}, will retry at {}: {}",
                    doc.getId(), doc.getUserId(), attempts, next, error);
        }
    }
}
