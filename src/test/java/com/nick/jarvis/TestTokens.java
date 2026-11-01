package com.nick.jarvis;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import com.nick.jarvis.security.JwtProperties;
import com.nick.jarvis.security.JwtService;

/**
 * 测试里共用的 JWT 配置。密钥是测试专用的固定值，和 application-local.yml 里的真实密钥无关。
 */
public final class TestTokens {

    public static final String SECRET = Base64.getEncoder()
            .encodeToString("test-secret-test-secret-test-secret!".getBytes(StandardCharsets.UTF_8));

    private TestTokens() {
    }

    public static JwtService jwtService() {
        return new JwtService(new JwtProperties(SECRET, Duration.ofDays(7)));
    }
}
