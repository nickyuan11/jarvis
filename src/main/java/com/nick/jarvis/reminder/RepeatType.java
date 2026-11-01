package com.nick.jarvis.reminder;

import java.time.LocalDateTime;

/**
 * 提醒的重复方式。每种方式知道自己“下一次”是什么时候。
 */
public enum RepeatType {

    NONE("不重复"),
    DAILY("每天"),
    WEEKLY("每周"),
    MONTHLY("每月");

    private final String label;

    RepeatType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 从 from 往后推一个周期 */
    public LocalDateTime next(LocalDateTime from) {
        return switch (this) {
            case NONE -> throw new IllegalStateException("不重复的提醒没有下一次");
            case DAILY -> from.plusDays(1);
            case WEEKLY -> from.plusWeeks(1);
            case MONTHLY -> from.plusMonths(1);
        };
    }

    /**
     * 算出晚于 now 的下一次提醒时间。
     * 比如服务停了三天，每天的提醒不应该一口气补发三次，而是直接跳到下一个未来的时间点。
     */
    public LocalDateTime nextAfter(LocalDateTime from, LocalDateTime now) {
        LocalDateTime next = next(from);
        while (!next.isAfter(now)) {
            next = next(next);
        }
        return next;
    }

    /** 宽松解析：大模型传来的可能是小写或空值 */
    public static RepeatType parse(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        return RepeatType.valueOf(value.strip().toUpperCase());
    }
}
