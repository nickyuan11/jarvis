package com.nick.jarvis.knowledge;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.nick.jarvis.entity.KnowledgeDocument;
import com.nick.jarvis.mapper.KnowledgeDocumentMapper;

/**
 * 在用户的资料里检索。
 *
 * 跨库“拼”查询：状态在 MySQL，向量在 PostgreSQL，没法 JOIN。
 * 1. 先从 MySQL 查出当前用户所有 READY 的资料（回收站里的是 DELETED，处理中的是 PROCESSING，都不会查出来）；
 * 2. 再把这些资料的 id 作为过滤条件，交给向量检索。
 * 过滤条件放在检索查询“里面”，而不是先取前几块再在 Java 里过滤：后者可能过滤完一块不剩。
 */
@Service
public class KnowledgeSearchService {

    /** 检索到的一块资料 */
    public record Hit(String filename, Long documentId, int chunkIndex, double score, String text, SourceRange range) {
    }

    /**
     * @param searchedDocuments 实际搜索了多少份资料；0 表示用户没有可检索的资料
     * @param note              给模型的补充说明（比如指定的资料没找到），没有时为 null
     */
    public record SearchResult(List<Hit> hits, int searchedDocuments, String note) {
    }

    private final KnowledgeDocumentMapper mapper;
    private final VectorStore vectorStore;
    private final int topK;
    private final double similarityThreshold;

    public KnowledgeSearchService(KnowledgeDocumentMapper mapper, VectorStore vectorStore,
                                  @Value("${jarvis.knowledge.top-k:5}") int topK,
                                  @Value("${jarvis.knowledge.similarity-threshold:0.4}") double similarityThreshold) {
        this.mapper = mapper;
        this.vectorStore = vectorStore;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    /**
     * @param query        检索语句（由模型改写成独立、完整的句子）
     * @param documentName 可选：只在名字包含这个关键词的资料里搜；一份都匹配不上时退回到搜全部
     */
    public SearchResult search(Long userId, String query, String documentName) {
        List<KnowledgeDocument> ready = mapper.selectList(KnowledgeQueries.withoutContent()
                .eq(KnowledgeDocument::getUserId, userId)
                .eq(KnowledgeDocument::getStatus, KnowledgeDocument.STATUS_READY));
        if (ready.isEmpty()) {
            return new SearchResult(List.of(), 0, null);
        }

        List<KnowledgeDocument> scope = ready;
        String note = null;
        if (documentName != null && !documentName.isBlank()) {
            String keyword = documentName.strip().toLowerCase(Locale.ROOT);
            List<KnowledgeDocument> matched = ready.stream()
                    .filter(d -> d.getFilename().toLowerCase(Locale.ROOT).contains(keyword))
                    .toList();
            if (matched.isEmpty()) {
                // 模型给的名字对不上时，不能直接返回“没找到”，否则会漏掉其实存在的内容
                note = "没有找到名字包含“" + documentName.strip() + "”的资料，以下是在全部资料里搜索的结果";
            } else {
                scope = matched;
            }
        }

        Map<Long, String> filenames = scope.stream()
                .collect(Collectors.toMap(KnowledgeDocument::getId, KnowledgeDocument::getFilename));
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        // userId 和 documentId 两个条件：documentId 已经只包含当前用户的资料了，
        // 再加上 userId 是“纵深防御”，万一哪天 documentId 的来源出了错，也搜不到别人的资料
        Filter.Expression filter = b.and(
                b.eq("userId", userId),
                b.in("documentId", filenames.keySet().toArray())).build();

        List<Document> found = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .filterExpression(filter)
                .build());

        // 老向量没有原文坐标。只为本次命中的旧资料重走原切块规则，同一份资料只读一次。
        Map<Long, List<TextChunker.Chunk>> legacy = new HashMap<>();
        List<Hit> hits = found.stream()
                .filter(d -> filenames.containsKey(((Number) d.getMetadata().get("documentId")).longValue()))
                .map(d -> toHit(d, filenames, userId, legacy))
                // 最相关的放最前面：上下文越靠中间越容易被模型忽略（lost in the middle）
                .sorted((a, c) -> Double.compare(c.score(), a.score()))
                .toList();
        return new SearchResult(hits, scope.size(), note);
    }

    private Hit toHit(Document document, Map<Long, String> filenames, Long userId,
                      Map<Long, List<TextChunker.Chunk>> legacy) {
        Map<String, Object> metadata = document.getMetadata();
        // 元数据从 PostgreSQL 的 JSON 读回来，数字可能是 Integer 也可能是 Long，统一按 Number 处理
        Long documentId = ((Number) metadata.get("documentId")).longValue();
        int chunkIndex = ((Number) metadata.get("chunkIndex")).intValue();
        SourceRange range = null;
        if (metadata.get("start") instanceof Number start && metadata.get("end") instanceof Number end
                && metadata.get("contextStart") instanceof Number left && metadata.get("contextEnd") instanceof Number right) {
            range = new SourceRange(left.intValue(), start.intValue(), end.intValue(), right.intValue());
        } else {
            List<TextChunker.Chunk> chunks = legacy.computeIfAbsent(documentId, id -> {
                KnowledgeDocument doc = mapper.selectById(id);
                if (doc == null || !userId.equals(doc.getUserId()) || !KnowledgeDocument.STATUS_READY.equals(doc.getStatus())) {
                    return List.of();
                }
                return new TextChunker(500, 80).splitLocated(doc.getContent());
            });
            if (chunkIndex >= 0 && chunkIndex < chunks.size() && chunks.get(chunkIndex).text().equals(document.getText())) {
                range = SourceRange.of(chunks, chunkIndex);
            }
        }
        double score = document.getScore() == null ? 0 : document.getScore();
        return new Hit(filenames.get(documentId), documentId, chunkIndex, score, document.getText(), range);
    }
}
