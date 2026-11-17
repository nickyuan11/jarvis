package com.nick.jarvis.ratelimit;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 固定窗口限流：每个用户在一个时间窗口内最多请求 maxRequests 次。
 *
 * 做法：Redis 里为每个用户维护一个计数器，每次请求加 1；
 * 第一次创建计数器时设置过期时间，窗口结束后计数器自动消失，重新计数。
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimiter {

    private static final String KEY_PREFIX = "jarvis:ratelimit:chat:";
    private static final String DAILY_KEY_PREFIX = "jarvis:ratelimit:chat:daily:";
    private static final long DAY_SECONDS = 24 * 60 * 60;

    /**
     * 为什么用 Lua 脚本：INCR 和 EXPIRE 是两条命令。如果分开执行，
     * 在两条命令之间程序崩溃，计数器就永远不会过期，这个用户会被永久限流。
     * Redis 执行 Lua 脚本是原子的，要么都执行，要么都不执行。
     *
     * 返回 {当前计数, 剩余秒数}
     */
    private static final RedisScript<List> SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return {count, redis.call('TTL', KEYS[1])}
            """, List.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;

    public RateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** 超过限制时抛出 429 Too Many Requests */
    public void check(Long userId) {
        List<?> result = redis.execute(SCRIPT, List.of(KEY_PREFIX + userId),
                String.valueOf(properties.window().toSeconds()));
        long count = ((Number) result.get(0)).longValue();
        long ttl = ((Number) result.get(1)).longValue();
        if (count > properties.maxRequests()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "发送太频繁了，请 " + Math.max(ttl, 1) + " 秒后再试");
        }
        checkDaily(userId);
    }

    /**
     * 每日上限：给每个用户一天的花费封顶。每分钟的限流防的是“刷得太快”，
     * 但每分钟 10 条不停地发，一天也有一万多条；上线后必须再加一层总量限制。
     * 窗口从当天第一条消息开始算 24 小时，复用同一个 Lua 脚本。
     * 放在每分钟限流之后：被那一层拒绝的请求，不占每日的额度。
     */
    private void checkDaily(Long userId) {
        if (properties.dailyMaxRequests() <= 0) {
            return;
        }
        List<?> result = redis.execute(SCRIPT, List.of(DAILY_KEY_PREFIX + userId), String.valueOf(DAY_SECONDS));
        long count = ((Number) result.get(0)).longValue();
        long ttl = ((Number) result.get(1)).longValue();
        if (count > properties.dailyMaxRequests()) {
            long hours = Math.max((ttl + 3599) / 3600, 1);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "今天的消息数已达上限（" + properties.dailyMaxRequests() + " 条），请约 " + hours + " 小时后再试");
        }
    }
}
