package com.nick.jarvis.user;

import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.nick.jarvis.entity.User;
import com.nick.jarvis.mapper.UserMapper;
import com.nick.jarvis.notification.channel.EmailChannel;

@Service
public class UserSettingsService {

    private static final Logger log = LoggerFactory.getLogger(UserSettingsService.class);
    /** 只做基本格式校验；真正确认邮箱可用，靠“发送测试邮件” */
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int MAX_EMAIL = 100;

    private final UserMapper userMapper;
    private final EmailChannel emailChannel;

    public UserSettingsService(UserMapper userMapper, EmailChannel emailChannel) {
        this.userMapper = userMapper;
        this.emailChannel = emailChannel;
    }

    public UserSettings get(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在");
        }
        return new UserSettings(user.getEmail(), Boolean.TRUE.equals(user.getEmailReminder()), emailChannel.configured());
    }

    public UserSettings update(Long userId, String email, boolean emailReminder) {
        String trimmed = email == null || email.isBlank() ? null : email.strip();
        if (trimmed != null && (trimmed.length() > MAX_EMAIL || !EMAIL.matcher(trimmed).matches())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮箱格式不正确");
        }
        if (emailReminder && trimmed == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "开启邮件提醒前，请先填写邮箱");
        }
        // 用 UpdateWrapper 显式设置：清空邮箱时要把 email 写成 NULL，而 updateById 默认会跳过 null 字段
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .set(User::getEmail, trimmed)
                .set(User::getEmailReminder, emailReminder)
                .eq(User::getId, userId));
        return get(userId);
    }

    /** 给用户保存的邮箱发一封测试邮件，失败时把原因返回给前端 */
    public void sendTestEmail(Long userId) {
        if (!emailChannel.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "服务器还没有配置发件邮箱");
        }
        User user = userMapper.selectById(userId);
        if (user == null || user.getEmail() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先保存邮箱");
        }
        try {
            emailChannel.sendTo(user.getEmail(), "Jarvis 测试邮件",
                    "你好，" + (user.getNickname() == null ? user.getUsername() : user.getNickname())
                            + "：\n\n这是一封测试邮件。收到它，说明提醒邮件可以正常送达。\n\n—— Jarvis");
        } catch (Exception e) {
            log.warn("Test email failed for user {}", userId, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "发送失败，请检查邮箱地址是否正确，稍后再试");
        }
    }

    /** emailAvailable：服务器是否配置了发件邮箱，前端据此提示 */
    public record UserSettings(String email, boolean emailReminder, boolean emailAvailable) {
    }
}
