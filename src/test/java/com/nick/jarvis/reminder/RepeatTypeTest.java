package com.nick.jarvis.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class RepeatTypeTest {

    private static final LocalDateTime MONDAY_9AM = LocalDateTime.of(2026, 11, 2, 9, 0);

    @Test
    void nextMovesOnePeriod() {
        assertThat(RepeatType.DAILY.next(MONDAY_9AM)).isEqualTo(LocalDateTime.of(2026, 11, 3, 9, 0));
        assertThat(RepeatType.WEEKLY.next(MONDAY_9AM)).isEqualTo(LocalDateTime.of(2026, 11, 9, 9, 0));
        assertThat(RepeatType.MONTHLY.next(MONDAY_9AM)).isEqualTo(LocalDateTime.of(2026, 12, 2, 9, 0));
    }

    @Test
    void monthlyOnThe31stFallsBackToMonthEnd() {
        // 1 月 31 日的“每月提醒”，2 月没有 31 号，Java 会自动落到 2 月最后一天
        assertThat(RepeatType.MONTHLY.next(LocalDateTime.of(2027, 1, 31, 9, 0)))
                .isEqualTo(LocalDateTime.of(2027, 2, 28, 9, 0));
    }

    @Test
    void missedOccurrencesAreSkippedNotReplayed() {
        // 每天 9 点的提醒，服务停了三天，周四 10 点才恢复：下一次应该是周五 9 点，而不是补发三次
        LocalDateTime thursday10am = LocalDateTime.of(2026, 11, 5, 10, 0);

        assertThat(RepeatType.DAILY.nextAfter(MONDAY_9AM, thursday10am))
                .isEqualTo(LocalDateTime.of(2026, 11, 6, 9, 0));
    }

    @Test
    void nextAfterIsAlwaysInTheFuture() {
        LocalDateTime now = MONDAY_9AM;
        // 恰好在提醒时刻触发：下一次是一周后，不能还是现在
        assertThat(RepeatType.WEEKLY.nextAfter(MONDAY_9AM, now)).isAfter(now);
    }

    @Test
    void parseIsLenient() {
        assertThat(RepeatType.parse(null)).isEqualTo(RepeatType.NONE);
        assertThat(RepeatType.parse(" ")).isEqualTo(RepeatType.NONE);
        assertThat(RepeatType.parse("weekly")).isEqualTo(RepeatType.WEEKLY);
        assertThatThrownBy(() -> RepeatType.parse("yearly")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonRepeatingHasNoNext() {
        assertThatThrownBy(() -> RepeatType.NONE.next(MONDAY_9AM)).isInstanceOf(IllegalStateException.class);
    }
}
