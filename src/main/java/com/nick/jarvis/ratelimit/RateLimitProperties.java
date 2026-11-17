package com.nick.jarvis.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对应 application.yml 里的 jarvis.rate-limit.chat。
 */
@ConfigurationProperties(prefix = "jarvis.rate-limit.chat")
/** dailyMaxRequests：每个用户 24 小时内最多发送多少条消息，0 表示不限制 */
public record RateLimitProperties(int maxRequests, Duration window, int dailyMaxRequests) {
}
