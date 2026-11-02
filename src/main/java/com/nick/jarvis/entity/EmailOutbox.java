package com.nick.jarvis.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 发件箱里的一封邮件 */
@Data
@TableName("email_outbox")
public class EmailOutbox {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 只用于排查是谁的邮件，可以为空 */
    private Long userId;

    /** 收件地址：写入时的快照，之后用户改邮箱也不影响这封 */
    private String toAddress;

    private String subject;

    private String content;

    private String status;

    /** 已尝试次数。抢占时用它做条件，相当于版本号 */
    private Integer attempts;

    /** 下一次可以尝试的时间 */
    private LocalDateTime nextAttemptAt;

    private String lastError;

    private LocalDateTime sentAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
