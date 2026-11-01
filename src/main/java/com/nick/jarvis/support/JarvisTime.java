package com.nick.jarvis.support;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 统一获取“现在几点”。
 * 不在代码里到处写 LocalDateTime.now()，有两个好处：
 * 1. 时区集中配置（jarvis.time-zone），换服务器也不会错乱；
 * 2. 测试时可以传入固定的 Clock，模拟“现在是某个时间”。
 */
@Component
public class JarvisTime {

    private final Clock clock;

    @Autowired
    public JarvisTime(@Value("${jarvis.time-zone:Asia/Shanghai}") String zone) {
        this(Clock.system(ZoneId.of(zone)));
    }

    public JarvisTime(Clock clock) {
        this.clock = clock;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public ZonedDateTime zonedNow() {
        return ZonedDateTime.now(clock);
    }

    public ZoneId zone() {
        return clock.getZone();
    }
}
