package com.nick.jarvis.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.nick.jarvis.reminder.RepeatType;

class JarvisTimeTest {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Test
    void tokyoMidnightRoundTripsThroughUtcOnThePreviousDay() {
        Instant instant = Instant.parse("2026-11-22T15:05:00Z");
        JarvisTime time = new JarvisTime(Clock.fixed(instant, ZoneId.of("Asia/Shanghai")));
        LocalDateTime tokyo = LocalDateTime.parse("2026-11-23T00:05");

        assertThat(time.now()).isEqualTo(LocalDateTime.parse("2026-11-22T15:05"));
        assertThat(JarvisTime.instant(time.now())).isEqualTo(instant);
        assertThat(time.zonedNow(TOKYO).toLocalDateTime()).isEqualTo(tokyo);
        assertThat(JarvisTime.utc(tokyo, TOKYO)).isEqualTo(time.now());
        assertThat(JarvisTime.local(time.now(), TOKYO)).isEqualTo(tokyo);
        assertThat(time.userZone(null)).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void dailyNineAmStaysAtNineAcrossTheSpringTransition() {
        LocalDateTime saturday = LocalDateTime.parse("2026-03-07T14:00");
        assertThat(RepeatType.DAILY.nextAfter(saturday, saturday, NEW_YORK))
                .isEqualTo(LocalDateTime.parse("2026-03-08T13:00"));
    }

    @Test
    void nonexistentTimeIsRejectedAndDailyRecurrenceSkipsThatDate() {
        LocalDateTime missing = LocalDateTime.parse("2026-03-08T02:30");
        assertThatThrownBy(() -> JarvisTime.utc(missing, NEW_YORK))
                .isInstanceOf(IllegalArgumentException.class);
        LocalDateTime saturday = LocalDateTime.parse("2026-03-07T07:30");
        assertThat(RepeatType.DAILY.nextAfter(saturday, saturday, NEW_YORK))
                .isEqualTo(LocalDateTime.parse("2026-03-09T06:30"));
    }

    @Test
    void repeatedClockTimeFiresOnlyOnTheFirstOccurrence() {
        LocalDateTime first = LocalDateTime.parse("2026-11-01T05:30");
        LocalDateTime previous = first.minusDays(1);
        assertThat(RepeatType.DAILY.nextAfter(previous, previous, NEW_YORK)).isEqualTo(first);
        assertThat(RepeatType.DAILY.nextAfter(previous, first.plusMinutes(20), NEW_YORK))
                .isEqualTo(LocalDateTime.parse("2026-11-02T06:30"));
    }
}
