package com.nick.jarvis.security;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

/**
 * 生成和校验 JWT。
 *
 * JWT 由三段组成，用点分隔：header.payload.signature
 * - header：签名算法
 * - payload：存放的信息（用户 id、过期时间等），只是 Base64 编码，任何人都能解开看到，所以不能放密码等敏感信息
 * - signature：用服务器的密钥对前两段签名。别人改了 payload，签名就对不上，服务器能识别出来
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        if (properties.secret() == null || properties.secret().isBlank()) {
            throw new IllegalStateException("未配置 jarvis.jwt.secret，请在 application-local.yml 中设置");
        }
        byte[] bytes = Decoders.BASE64.decode(properties.secret());
        if (bytes.length < 32) {
            throw new IllegalStateException("jarvis.jwt.secret 太短，HS256 至少需要 32 字节");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.properties = properties;
    }

    public IssuedToken issue(Long userId, String username) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.expiration());
        String token = Jwts.builder()
                // jti：每个 token 的唯一编号。退出登录时，把这个编号加入黑名单
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /**
     * 校验签名和有效期，通过后返回 token 里的信息；token 无效时返回 null。
     */
    public ParsedToken parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new ParsedToken(
                    Long.valueOf(claims.getSubject()),
                    claims.getId(),
                    claims.getExpiration().toInstant());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    /** 解析后的 token：用户 id、唯一编号、过期时间 */
    public record ParsedToken(Long userId, String jti, Instant expiresAt) {
    }
}
