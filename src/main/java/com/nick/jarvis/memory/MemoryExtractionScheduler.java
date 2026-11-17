package com.nick.jarvis.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每分钟检查一次有没有“空闲了一段时间、并且有新消息”的会话，提取长期记忆。
 * 检查本身只是一条 SQL，很便宜；真正花钱的模型调用只在有新对话时才发生。
 * jarvis.memory.extraction.enabled=false 可以关掉。
 */
@Component
@ConditionalOnProperty(name = "jarvis.memory.extraction.enabled", havingValue = "true", matchIfMissing = true)
public class MemoryExtractionScheduler {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionScheduler.class);

    private final MemoryExtractor extractor;

    public MemoryExtractionScheduler(MemoryExtractor extractor) {
        this.extractor = extractor;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void extractDue() {
        try {
            extractor.extractDue();
        } catch (Exception e) {
            log.error("Failed to extract memories", e);
        }
    }
}
