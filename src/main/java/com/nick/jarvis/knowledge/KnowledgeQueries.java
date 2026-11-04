package com.nick.jarvis.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nick.jarvis.entity.KnowledgeDocument;

/** 知识库资料的常用查询条件 */
final class KnowledgeQueries {

    private KnowledgeQueries() {
    }

    /**
     * 查询时不带原文 content：它可能有 1 MB，列表、状态判断都用不到，没必要每次从数据库搬出来。
     * 需要原文时（真正切块的那一刻）再用 selectById 单独查。
     */
    @SuppressWarnings("unchecked")
    static LambdaQueryWrapper<KnowledgeDocument> withoutContent() {
        return new LambdaQueryWrapper<KnowledgeDocument>().select(
                KnowledgeDocument::getId,
                KnowledgeDocument::getUserId,
                KnowledgeDocument::getFilename,
                KnowledgeDocument::getSizeBytes,
                KnowledgeDocument::getContentHash,
                KnowledgeDocument::getStatus,
                KnowledgeDocument::getChunkCount,
                KnowledgeDocument::getAttempts,
                KnowledgeDocument::getNextAttemptAt,
                KnowledgeDocument::getLastError,
                KnowledgeDocument::getDeletedAt,
                KnowledgeDocument::getCreateTime,
                KnowledgeDocument::getUpdateTime);
    }
}
