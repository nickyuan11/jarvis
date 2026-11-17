package com.nick.jarvis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import com.nick.jarvis.TestTokens;
import com.nick.jarvis.dto.AuthResponse;
import com.nick.jarvis.entity.User;
import com.nick.jarvis.mapper.UserMapper;
import com.nick.jarvis.security.JwtService;
import com.nick.jarvis.security.TokenBlacklist;

/**
 * 数据库（UserMapper）和 Redis（TokenBlacklist）是模拟的；
 * 密码加密和 JWT 用真实实现，因为它们本身就是要验证的逻辑。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private TokenBlacklist blacklist;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtService jwtService = TestTokens.jwtService();
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userMapper, passwordEncoder, jwtService, blacklist, "");
    }

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    // ---------------- 注册 ----------------

    @Test
    void registerStoresHashedPasswordAndReturnsToken() {
        // 模拟数据库插入后回填自增 id
        doAnswer(invocation -> {
            invocation.<User>getArgument(0).setId(7L);
            return 1;
        }).when(userMapper).insert(any(User.class));

        AuthResponse response = authService.register("nick_yuan", "secret123", "Nick", null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(saved.capture());
        // 存进数据库的绝不能是明文，但要能被 BCrypt 校验通过
        assertThat(saved.getValue().getPassword()).isNotEqualTo("secret123");
        assertThat(passwordEncoder.matches("secret123", saved.getValue().getPassword())).isTrue();

        assertThat(response.user().id()).isEqualTo(7L);
        assertThat(response.user().nickname()).isEqualTo("Nick");
        assertThat(jwtService.parse(response.token()).userId()).isEqualTo(7L);
    }

    @Test
    void nicknameDefaultsToUsername() {
        AuthResponse response = authService.register("nick_yuan", "secret123", "  ", null);

        assertThat(response.user().nickname()).isEqualTo("nick_yuan");
    }

    /** 同一个测试方法，用多组输入各跑一次 */
    @ParameterizedTest
    @ValueSource(strings = {"nick yuan", "小克", "ab", "nick.yuan", "a_very_long_username_123"})
    void registerRejectsInvalidUsername(String username) {
        assertStatus(() -> authService.register(username, "secret123", null, null), HttpStatus.BAD_REQUEST);
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerRejectsShortPassword() {
        assertStatus(() -> authService.register("nick_yuan", "12345", null, null), HttpStatus.BAD_REQUEST);
    }

    @Test
    void registerRejectsTakenUsername() {
        when(userMapper.selectOne(any())).thenReturn(user(1L, "nick_yuan", "whatever"));

        assertStatus(() -> authService.register("nick_yuan", "secret123", null, null), HttpStatus.CONFLICT);
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void concurrentRegistrationIsCaughtByUniqueIndex() {
        // 检查时用户名还没被占用，插入时却撞上了唯一索引：模拟两个人同时注册
        doThrow(new DuplicateKeyException("uk_username")).when(userMapper).insert(any(User.class));

        assertStatus(() -> authService.register("nick_yuan", "secret123", null, null), HttpStatus.CONFLICT);
    }

    // ---------------- 登录 ----------------

    @Test
    void loginWithCorrectPasswordReturnsToken() {
        when(userMapper.selectOne(any())).thenReturn(user(3L, "nick_yuan", passwordEncoder.encode("secret123")));

        AuthResponse response = authService.login("nick_yuan", "secret123");

        assertThat(jwtService.parse(response.token()).userId()).isEqualTo(3L);
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameError() {
        when(userMapper.selectOne(any()))
                .thenReturn(user(3L, "nick_yuan", passwordEncoder.encode("secret123")))
                .thenReturn(null);

        String wrongPassword = reasonOf(() -> authService.login("nick_yuan", "wrong-password"));
        String unknownUser = reasonOf(() -> authService.login("nobody", "secret123"));

        // 两种情况返回完全一样的提示，不让别人借此试探哪些用户名已注册
        assertThat(wrongPassword).isEqualTo(unknownUser).isEqualTo("用户名或密码错误");
    }

    // ---------------- 退出 ----------------

    @Test
    void logoutRevokesCurrentToken() {
        JwtService.ParsedToken token = new JwtService.ParsedToken(3L, "jti-1", Instant.now().plusSeconds(3600));
        var authentication = new UsernamePasswordAuthenticationToken(3L, null, List.of());
        authentication.setDetails(token);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        authService.logout();

        verify(blacklist).revoke(token);
    }

    // ---------------- 工具方法 ----------------

    private static User user(Long id, String username, String passwordHash) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setNickname(username);
        user.setPassword(passwordHash);
        return user;
    }

    private static void assertStatus(Runnable action, HttpStatus expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(expected));
    }

    private static String reasonOf(Runnable action) {
        try {
            action.run();
        } catch (ResponseStatusException e) {
            return e.getReason();
        }
        throw new AssertionError("应该抛出 ResponseStatusException");
    }

    @Test
    void registrationRequiresTheInviteCodeWhenOneIsConfigured() {
        AuthService restricted = new AuthService(userMapper, passwordEncoder, jwtService, blacklist, "open-sesame");

        // 邀请码错误或没填：403，而且在检查用户名之前就被拒绝
        assertStatus(() -> restricted.register("nick_yuan", "secret123", null, "wrong"), HttpStatus.FORBIDDEN);
        assertStatus(() -> restricted.register("nick_yuan", "secret123", null, null), HttpStatus.FORBIDDEN);
    }
}
