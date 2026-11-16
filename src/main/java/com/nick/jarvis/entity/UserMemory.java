package com.nick.jarvis.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一条长期记忆：关于用户本人、长期有效、在系统里没有其他地方存的信息。
 * 提醒事项有 reminder 表，资料有知识库，它们不进记忆，否则同一件事存两份，改了一边另一边就过时了。
 *
 * 删除是物理删除（没有 deleted 字段）：用户说“忘掉”，就是真的不想让 Jarvis 知道了。
 */
@Data
@TableName("user_memory")
public class UserMemory {

    /** 对 Jarvis 回答方式的要求，例如“回答简短一点”：每次回答都要遵守 */
    public static final String TYPE_PREFERENCE = "PREFERENCE";
    /** 关于用户的事实，例如“对花生过敏”：相关时参考 */
    public static final String TYPE_FACT = "FACT";

    /** 用户明确要求记住 */
    public static final String SOURCE_EXPLICIT = "EXPLICIT";
    /** 后台从对话中提取 */
    public static final String SOURCE_EXTRACTED = "EXTRACTED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String content;

    private String type;

    private String source;

    private Long conversationId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
