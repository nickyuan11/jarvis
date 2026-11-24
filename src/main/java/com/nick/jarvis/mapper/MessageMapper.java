package com.nick.jarvis.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nick.jarvis.entity.Message;

/**
 * 继承 BaseMapper 后，insert、selectById、updateById、deleteById 等方法都不用自己写。
 */
public interface MessageMapper extends BaseMapper<Message> {

    @Select("""
            SELECT m.citations FROM message m JOIN conversation c ON c.id = m.conversation_id
            WHERE m.id = #{messageId} AND m.role = 'assistant' AND c.user_id = #{userId} AND c.deleted = 0
            """)
    String ownedCitations(@Param("messageId") long messageId, @Param("userId") long userId);
}
