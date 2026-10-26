package com.nick.jarvis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nick.jarvis.entity.Conversation;

/**
 * 继承 BaseMapper 后，insert、selectById、updateById、deleteById 等方法都不用自己写。
 */
public interface ConversationMapper extends BaseMapper<Conversation> {
}
