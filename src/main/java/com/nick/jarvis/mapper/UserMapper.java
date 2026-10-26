package com.nick.jarvis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nick.jarvis.entity.User;

/**
 * 继承 BaseMapper 后，insert、selectById、updateById、deleteById 等方法都不用自己写。
 */
public interface UserMapper extends BaseMapper<User> {
}
