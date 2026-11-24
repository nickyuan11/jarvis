package com.nick.jarvis.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 统一获取“现在几点”。
 * 不在代码里到处写 LocalDateTime.now()，有两个好处：
 * 1. 入库和比较统一使用 UTC；jarvis.time-zone 仅用于未上报时区的客户端；
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

    /** MySQL DATETIME 统一保存 UTC。 */
    public LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public ZonedDateTime zonedNow(ZoneId zone) {
        return clock.instant().atZone(zone);
    }

    public ZoneId userZone(String zoneId) {
        return zoneId == null || zoneId.isBlank() ? clock.getZone() : ZoneId.of(zoneId);
    }

    public static Instant instant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }

    public static LocalDateTime local(LocalDateTime utc, ZoneId zone) {
        return LocalDateTime.ofInstant(instant(utc), zone);
    }

    public static LocalDateTime utc(LocalDateTime local, ZoneId zone) {
        if (zone.getRules().getValidOffsets(local).isEmpty()) {
            throw new IllegalArgumentException("该时刻在 " + zone + " 因夏令时跳转而不存在，请选择其他时间");
        }
        return local.atZone(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
