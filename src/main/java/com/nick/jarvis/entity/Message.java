package com.nick.jarvis.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("message")
public class Message {

    /** 角色常量，取值和大模型 API 的 role 保持一致 */
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_TOOL = "tool";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    /** user / assistant / system / tool */
    private String role;

    private String content;

    /** 生成回复的模型名称，用户消息为 null */
    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
