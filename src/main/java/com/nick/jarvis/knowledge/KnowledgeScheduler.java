package com.nick.jarvis.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 知识库的两个定时任务：处理到期的资料、永久删除回收站里满 7 天的资料 */
@Component
public class KnowledgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeScheduler.class);

    private final DocumentProcessor processor;
    private final KnowledgeService knowledgeService;

    public KnowledgeScheduler(DocumentProcessor processor, KnowledgeService knowledgeService) {
        this.processor = processor;
        this.knowledgeService = knowledgeService;
    }

    /** 每分钟：处理等待重试的、租约过期的（处理途中应用崩了） */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void processDue() {
        try {
            processor.processDue();
        } catch (Exception e) {
            log.error("Failed to process due documents", e);
        }
    }

    /** 每小时：永久删除。7 天的期限差个把小时无所谓，用固定间隔就不用操心 cron 的时区 */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void purgeExpired() {
        try {
            knowledgeService.purgeExpired();
        } catch (Exception e) {
            log.error("Failed to purge expired documents", e);
        }
    }
}
