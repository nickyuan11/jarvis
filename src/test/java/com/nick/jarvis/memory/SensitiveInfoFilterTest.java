package com.nick.jarvis.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveInfoFilterTest {

    @Test
    void detectsBankCardNumbers() {
        assertThat(SensitiveInfoFilter.containsSensitiveInfo("我的银行卡号是 6222021234567890123")).isTrue();
        // 中间带空格、横线的写法
        assertThat(SensitiveInfoFilter.containsSensitiveInfo("卡号 6222 0212 3456 7890")).isTrue();
        assertThat(SensitiveInfoFilter.containsSensitiveInfo("卡号 6222-0212-3456-7890")).isTrue();
    }

    @Test
    void detectsIdCardNumbers() {
        assertThat(SensitiveInfoFilter.containsSensitiveInfo("身份证 11010519491231002X")).isTrue();
    }

    @Test
    void detectsPasswords() {
        assertThat(SensitiveInfoFilter.containsSensitiveInfo("邮箱密码是 abc123")).isTrue();
        assertThat(SensitiveInfoFilter.containsSensitiveInfo("支付密码：888888")).isTrue();
        assertThat(SensitiveInfoFilter.containsSensitiveInfo("银行卡密码123456")).isTrue();
        assertThat(SensitiveInfoFilter.containsSensitiveInfo("我的pin是1234")).isTrue();
        assertThat(SensitiveInfoFilter.containsSensitiveInfo("password=hunter2")).isTrue();
    }

    @Test
    void doesNotFlagOrdinaryMemories() {
        assertThat(SensitiveInfoFilter.containsSensitiveInfo("对花生过敏")).isFalse();
        assertThat(SensitiveInfoFilter.containsSensitiveInfo("在找 Java 后端的工作，有 1 年经验")).isFalse();
        // 手机号是 11 位，不算卡号
        assertThat(SensitiveInfoFilter.containsSensitiveInfo("手机号 13800138000")).isFalse();
        // “密码”后面没有具体内容，只是普通的词
        assertThat(SensitiveInfoFilter.containsSensitiveInfo("在学密码学")).isFalse();
        assertThat(SensitiveInfoFilter.containsSensitiveInfo("喜欢用拼音pinyin输入法")).isFalse();
        assertThat(SensitiveInfoFilter.containsSensitiveInfo("生日是 2000-01-15")).isFalse();
    }
}
