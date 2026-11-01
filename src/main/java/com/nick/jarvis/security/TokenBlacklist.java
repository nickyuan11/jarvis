package com.nick.jarvis.security;

import java.time.Duration;
import java.time.Instant;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * token 黑名单，存在 Redis 里。
 *
 * 退出登录时，把 token 的 jti 写进 Redis，过期时间设为 token 的剩余有效期：
 * - token 过期之前，黑名单拦住它；
 * - token 过期之后，它本来就不能用了，Redis 自动删掉这条记录，不会越积越多。
 */
@Component
public class TokenBlacklist {

    private static final String KEY_PREFIX = "jarvis:token:blacklist:";

    private final StringRedisTemplate redis;

    public TokenBlacklist(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void revoke(JwtService.ParsedToken token) {
        Duration remaining = Duration.between(Instant.now(), token.expiresAt());
        if (remaining.isNegative() || remaining.isZero()) {
            return;
        }
        redis.opsForValue().set(KEY_PREFIX + token.jti(), "1", remaining);
    }

    public boolean isRevoked(String jti) {
        return jti != null && Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + jti));
    }
}
