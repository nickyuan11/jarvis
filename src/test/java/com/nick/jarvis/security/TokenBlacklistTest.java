package com.nick.jarvis.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 用 Mockito 模拟 Redis：不需要真的启动 Redis，只验证“我们有没有用正确的参数调用它”。
 */
@ExtendWith(MockitoExtension.class)
class TokenBlacklistTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Test
    void revokeStoresJtiUntilTokenExpires() {
        when(redis.opsForValue()).thenReturn(valueOps);
        TokenBlacklist blacklist = new TokenBlacklist(redis);
        Instant expiresAt = Instant.now().plus(Duration.ofHours(2));

        blacklist.revoke(new JwtService.ParsedToken(1L, "abc", expiresAt));

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq("jarvis:token:blacklist:abc"), eq("1"), ttl.capture());
        // 过期时间应该约等于 token 的剩余寿命（允许几秒误差）
        assertThat(ttl.getValue()).isBetween(Duration.ofHours(2).minusSeconds(5), Duration.ofHours(2));
    }

    @Test
    void alreadyExpiredTokenIsNotStored() {
        TokenBlacklist blacklist = new TokenBlacklist(redis);

        blacklist.revoke(new JwtService.ParsedToken(1L, "abc", Instant.now().minusSeconds(10)));

        // 过期的 token 本来就不能用，没必要再占 Redis 空间
        verify(redis, never()).opsForValue();
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void isRevokedChecksRedis() {
        when(redis.hasKey("jarvis:token:blacklist:abc")).thenReturn(true);
        when(redis.hasKey("jarvis:token:blacklist:xyz")).thenReturn(false);
        TokenBlacklist blacklist = new TokenBlacklist(redis);

        assertThat(blacklist.isRevoked("abc")).isTrue();
        assertThat(blacklist.isRevoked("xyz")).isFalse();
        assertThat(blacklist.isRevoked(null)).isFalse();
    }
}
