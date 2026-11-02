package com.nick.jarvis.notification.outbox;

import java.time.Duration;

/**
 * 发件箱的重试规则：指数退避。
 *
 * 第 n 次失败后等 1、2、4、8、16、32 分钟……再试，每次翻倍，最长 60 分钟；最多尝试 7 次。
 * 第 1 次是立即发送，后面 6 次重试的间隔加起来约 63 分钟，
 * 足够跨过一次 163 的登录限流（实测停止约 30 分钟后恢复）。
 *
 * 为什么不固定间隔？临时故障（网络抖动）希望很快重试；
 * 持续故障（被限流）则希望越来越少地去打扰对方。翻倍能同时照顾这两种情况。
 */
final class RetryPolicy {

    static final int MAX_ATTEMPTS = 7;
    static final Duration FIRST_DELAY = Duration.ofMinutes(1);
    static final Duration MAX_DELAY = Duration.ofMinutes(60);

    /**
     * 租约：抢到一封邮件时，先把它的下一次尝试时间推后这么久。
     * 如果发送途中应用崩了，租约到期后这封会被重新扫到，不会永远卡住。
     * 必须比一次发送可能花的最长时间（SMTP 超时 10 秒 × 3）长得多，否则还没发完就被别人抢走。
     */
    static final Duration LEASE = Duration.ofMinutes(5);

    private RetryPolicy() {
    }

    /** 第 attempts 次尝试失败后，要等多久再试 */
    static Duration delayAfter(int attempts) {
        // 限制指数，防止次数很大时左移溢出
        int exponent = Math.min(Math.max(attempts - 1, 0), 10);
        Duration delay = FIRST_DELAY.multipliedBy(1L << exponent);
        return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
    }

    /** 已经尝试了 attempts 次，是不是该放弃了 */
    static boolean exhausted(int attempts) {
        return attempts >= MAX_ATTEMPTS;
    }
}
