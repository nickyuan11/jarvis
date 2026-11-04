package com.nick.jarvis.knowledge;

/**
 * 一份资料需要处理（新上传、从回收站恢复、手动重试）。
 * 写入它的事务提交之后，DocumentProcessor.onQueued 收到这个事件，在后台开始处理。
 */
public record DocumentQueuedEvent(Long documentId) {
}
