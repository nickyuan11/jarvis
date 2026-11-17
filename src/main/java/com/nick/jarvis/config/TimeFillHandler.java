package com.nick.jarvis.config;

import java.time.LocalDateTime;

import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.nick.jarvis.support.JarvisTime;

/**
 * 自动填充 createTime 和 updateTime，业务代码里不需要手动 set。
 * 对应实体字段上的 @TableField(fill = ...)。
 *
 * 时间取自 JarvisTime（配置的时区），不能用 LocalDateTime.now()（电脑的系统时区）。
 * 整个应用只能有一个时钟：这里填的时间会和业务代码里 time.now() 得到的时间直接比较，
 * 比如“会话空闲了多久”。两个时钟的时区不一致时，比较结果会差出几个小时，
 * 而且在时区恰好相同的电脑上完全看不出问题。
 */
@Component
public class TimeFillHandler implements MetaObjectHandler {

    private final JarvisTime time;

    public TimeFillHandler(JarvisTime time) {
        this.time = time;
    }

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = time.now();
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, time.now());
    }
}
