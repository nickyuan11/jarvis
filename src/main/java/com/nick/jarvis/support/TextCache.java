package com.nick.jarvis.support;

import java.time.Duration;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 把一段文本缓存在 Redis（Cache-Aside 模式）：先查缓存，没有再调用 loader，并把结果写回缓存。
 * NBA、LoL 等第三方数据都用它，避免每个服务各写一遍同样的逻辑。
 *
 * Redis 出问题时直接调用 loader：缓存只是加速，不能因为它影响功能。
 */
@Component
public class TextCache {

    private static final Logger log = LoggerFactory.getLogger(TextCache.class);
    private static final String PREFIX = "jarvis:cache:";

    private final StringRedisTemplate redis;

    public TextCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String get(String key, Duration ttl, Supplier<String> loader) {
        String fullKey = PREFIX + key;
        try {
            String hit = redis.opsForValue().get(fullKey);
            if (hit != null) {
                return hit;
            }
        } catch (Exception e) {
            log.warn("Redis unavailable, skip cache for {}", fullKey, e);
        }
        String value = loader.get();
        try {
            redis.opsForValue().set(fullKey, value, ttl);
        } catch (Exception e) {
            log.warn("Failed to write cache {}", fullKey, e);
        }
        return value;
    }
}
