package com.nick.jarvis.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.nick.jarvis.TestTokens;

/**
 * 纯单元测试：不启动 Spring，不连数据库，直接 new 出被测对象。
 * 跑得快（毫秒级），出错时也能精确定位到是哪段逻辑的问题。
 */
class JwtServiceTest {

    private final JwtService jwtService = TestTokens.jwtService();

    @Test
    void issuedTokenCanBeParsedBack() {
        JwtService.IssuedToken issued = jwtService.issue(42L, "nick");

        JwtService.ParsedToken parsed = jwtService.parse(issued.token());

        assertThat(parsed).isNotNull();
        assertThat(parsed.userId()).isEqualTo(42L);
        assertThat(parsed.jti()).isNotBlank();
        assertThat(parsed.expiresAt()).isEqualTo(issued.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void everyTokenHasItsOwnJti() {
        String jti1 = jwtService.parse(jwtService.issue(1L, "nick").token()).jti();
        String jti2 = jwtService.parse(jwtService.issue(1L, "nick").token()).jti();

        // 同一个用户登录两次，两个 token 的编号也不同，退出一个不会影响另一个
        assertThat(jti1).isNotEqualTo(jti2);
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtService.issue(1L, "nick").token();
        String[] parts = token.split("\\.");
        // 把 payload 里的用户 id 改成 2，签名不变：模拟有人想冒充别的用户
        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"2\"}".getBytes(StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThat(jwtService.parse(forged)).isNull();
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String otherSecret = Base64.getEncoder()
                .encodeToString("another-secret-another-secret-123".getBytes(StandardCharsets.UTF_8));
        JwtService other = new JwtService(new JwtProperties(otherSecret, Duration.ofDays(7)));

        assertThat(jwtService.parse(other.issue(1L, "nick").token())).isNull();
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService shortLived = new JwtService(new JwtProperties(TestTokens.SECRET, Duration.ofSeconds(-1)));

        assertThat(jwtService.parse(shortLived.issue(1L, "nick").token())).isNull();
    }

    @Test
    void garbageIsRejected() {
        assertThat(jwtService.parse("not-a-jwt")).isNull();
        assertThat(jwtService.parse("")).isNull();
    }

    @Test
    void tooShortSecretFailsFast() {
        String shortSecret = Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new JwtService(new JwtProperties(shortSecret, Duration.ofDays(7))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("太短");
    }
}
