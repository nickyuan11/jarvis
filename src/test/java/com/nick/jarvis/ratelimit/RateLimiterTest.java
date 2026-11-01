package com.nick.jarvis.ratelimit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class RateLimiterTest {

    @Mock
    private StringRedisTemplate redis;

    private RateLimiter rateLimiter() {
        return new RateLimiter(redis, new RateLimitProperties(10, Duration.ofSeconds(60)));
    }

    /** 模拟 Lua 脚本返回 {当前计数, 剩余秒数} */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void redisReturns(long count, long ttl) {
        when(redis.execute(any(RedisScript.class), eq(List.of("jarvis:ratelimit:chat:1")), eq("60")))
                .thenReturn((List) List.of(count, ttl));
    }

    @Test
    void allowsRequestsWithinLimit() {
        redisReturns(10, 30);

        // 第 10 次正好在限制内，应该放行
        assertThatCode(() -> rateLimiter().check(1L)).doesNotThrowAnyException();
    }

    @Test
    void rejectsRequestsOverLimitWithRetryHint() {
        redisReturns(11, 42);

        assertThatThrownBy(() -> rateLimiter().check(1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    org.assertj.core.api.Assertions.assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    org.assertj.core.api.Assertions.assertThat(e.getReason()).contains("42 秒");
                });
    }
}
