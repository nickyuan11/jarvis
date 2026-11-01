package com.nick.jarvis.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对应 application.yml 里的 jarvis.rate-limit.chat。
 */
@ConfigurationProperties(prefix = "jarvis.rate-limit.chat")
public record RateLimitProperties(int maxRequests, Duration window) {
}
