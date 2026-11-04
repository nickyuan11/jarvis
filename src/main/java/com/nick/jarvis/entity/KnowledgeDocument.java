package com.nick.jarvis.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 知识库里的一份资料。
 *
 * 不叫 Document：Spring AI 自带一个 Document 类（向量库里的一块），同名会很混乱。
 * 删除不用 @TableLogic：回收站要能查出已删除的资料，定时任务还要知道“什么时候删的”。
 *
 * 移入回收站时，status 改为 DELETED，同时记下 deletedAt（删除时间）：
 * - status = READY 就一定可以检索，查询时只看 status 也不会搜到回收站里的资料，漏写条件也不会泄露；
 * - deletedAt 用来判断“满 7 天了没有”。
 * 两个字段表达的是同一件事，所以总是在同一条 UPDATE 里一起修改（删除、恢复），避免互相矛盾。
 */
@Data
@TableName("knowledge_document")
public class KnowledgeDocument {

    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";
    /** 在回收站里。向量块已经删掉了，恢复时会重新处理 */
    public static final String STATUS_DELETED = "DELETED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String filename;

    private Integer sizeBytes;

    /** 原文。可能有 1 MB，列表等用不到的地方不要查它，见 KnowledgeQueries.withoutContent() */
    private String content;

    private String contentHash;

    private String status;

    private Integer chunkCount;

    /** 已尝试处理的次数，抢占时兼做版本号 */
    private Integer attempts;

    /** 下一次可以处理的时间 */
    private LocalDateTime nextAttemptAt;

    private String lastError;

    /** 移入回收站的时间，null 表示未删除 */
    private LocalDateTime deletedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
