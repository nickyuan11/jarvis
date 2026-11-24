package com.nick.jarvis.knowledge;

import java.util.List;

/** 坐标均指向不可编辑的资料原文。外层范围包含前后一块，内层范围用于高亮。 */
public record SourceRange(int contextStart, int start, int end, int contextEnd) {

    public static SourceRange of(List<TextChunker.Chunk> chunks, int index) {
        var chunk = chunks.get(index);
        return new SourceRange(chunks.get(Math.max(0, index - 1)).start(), chunk.start(), chunk.end(),
                chunks.get(Math.min(chunks.size() - 1, index + 1)).end());
    }

    public boolean fits(int length) {
        return contextStart >= 0 && contextStart <= start && start < end && end <= contextEnd && contextEnd <= length;
    }
}
