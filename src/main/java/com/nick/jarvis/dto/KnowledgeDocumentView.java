package com.nick.jarvis.dto;

import java.time.Instant;

/**
 * 资料列表里的一项。不包含原文。
 *
 * @param lastError 处理失败的原因，页面上提示用户
 * @param deletedAt 移入回收站的时间，未删除时为 null
 * @param purgeAt   将被永久删除的时间（移入回收站 7 天后），未删除时为 null
 */
public record KnowledgeDocumentView(Long id, String filename, Integer sizeBytes, String status,
                                    Integer chunkCount, String lastError, Instant createTime,
                                    Instant deletedAt, Instant purgeAt) {
}
