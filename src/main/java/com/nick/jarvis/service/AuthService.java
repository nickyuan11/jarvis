package com.nick.jarvis.service;

import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nick.jarvis.dto.AuthResponse;
import com.nick.jarvis.dto.UserView;
import com.nick.jarvis.entity.User;
import com.nick.jarvis.mapper.UserMapper;
import com.nick.jarvis.security.CurrentUser;
import com.nick.jarvis.security.JwtService;
import com.nick.jarvis.security.TokenBlacklist;

@Service
public class AuthService {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,20}$");
    private static final int MIN_PASSWORD = 6;
    private static final int MAX_PASSWORD = 64;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenBlacklist blacklist;
    /** 注册邀请码。为空表示开放注册（本地开发）；线上必须配置，见 application-prod.yml */
    private final String inviteCode;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder,
                       JwtService jwtService, TokenBlacklist blacklist,
                       @Value("${jarvis.registration.invite-code:}") String inviteCode) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.blacklist = blacklist;
        this.inviteCode = inviteCode == null ? "" : inviteCode.strip();
    }

    public AuthResponse register(String username, String password, String nickname, String inviteCode) {
        // 邀请码放在最前面检查：没有邀请码的人，连“用户名是否已被注册”都试探不出来
        requireInviteCode(inviteCode);
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw badRequest("用户名需为 3 到 20 位英文字母、数字或下划线，不能包含空格、中文或其他符号");
        }
        if (password == null || password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
            throw badRequest("密码长度需为 " + MIN_PASSWORD + " 到 " + MAX_PASSWORD + " 位");
        }
        if (findByUsername(username) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已被注册");
        }

        User user = new User();
        user.setUsername(username);
        // 数据库里只存 BCrypt 哈希，即使数据库泄露，别人也拿不到原始密码
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname == null || nickname.isBlank() ? username : nickname.strip());
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 两个人同时注册同一个用户名：上面的检查都通过了，最后由数据库的唯一索引兜底
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已被注册");
        }
        return issueFor(user);
    }

    /**
     * 上线后任何人都能打开注册页，而每次对话、每次向量化花的都是服务器配置的 API Key 的钱。
     * 所以线上只允许持有邀请码的人注册。
     * 比较用 MessageDigest.isEqual（恒定时间）：普通的 equals 遇到第一个不同的字符就返回，
     * 攻击者可以根据响应时间的细微差别，一位一位地猜出邀请码（时序攻击）。
     */
    private void requireInviteCode(String provided) {
        if (inviteCode.isEmpty()) {
            return;
        }
        byte[] expected = inviteCode.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] actual = (provided == null ? "" : provided.strip()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (!java.security.MessageDigest.isEqual(expected, actual)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "邀请码不正确");
        }
    }

    public AuthResponse login(String username, String password) {
        User user = username == null ? null : findByUsername(username);
        // 用户不存在和密码错误，返回同一句话，不让人借此试探哪些用户名已注册
        if (user == null || password == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        return issueFor(user);
    }

    /** 退出登录：让当前这个 token 立即失效 */
    public void logout() {
        blacklist.revoke(CurrentUser.token());
    }

    public UserView me() {
        User user = userMapper.selectById(CurrentUser.id());
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在");
        }
        return toView(user);
    }

    private AuthResponse issueFor(User user) {
        JwtService.IssuedToken issued = jwtService.issue(user.getId(), user.getUsername());
        return new AuthResponse(issued.token(), issued.expiresAt(), toView(user));
    }

    private User findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    private static UserView toView(User user) {
        return new UserView(user.getId(), user.getUsername(), user.getNickname());
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
