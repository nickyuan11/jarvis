package com.nick.jarvis.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void delayDoublesAfterEachFailure() {
        assertThat(RetryPolicy.delayAfter(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(RetryPolicy.delayAfter(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(RetryPolicy.delayAfter(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(RetryPolicy.delayAfter(6)).isEqualTo(Duration.ofMinutes(32));
    }

    @Test
    void delayIsCappedAtOneHour() {
        assertThat(RetryPolicy.delayAfter(7)).isEqualTo(Duration.ofMinutes(60));
        // 次数很大也不会溢出成负数
        assertThat(RetryPolicy.delayAfter(100)).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void retriesCoverMoreThanOneThrottlingPeriod() {
        // 163 限流实测约 30 分钟后恢复，所有重试间隔加起来要明显更长
        Duration total = Duration.ZERO;
        for (int attempts = 1; attempts < RetryPolicy.MAX_ATTEMPTS; attempts++) {
            total = total.plus(RetryPolicy.delayAfter(attempts));
        }
        assertThat(total).isGreaterThan(Duration.ofMinutes(60));
    }

    @Test
    void givesUpAfterMaxAttempts() {
        assertThat(RetryPolicy.exhausted(RetryPolicy.MAX_ATTEMPTS - 1)).isFalse();
        assertThat(RetryPolicy.exhausted(RetryPolicy.MAX_ATTEMPTS)).isTrue();
    }
}
