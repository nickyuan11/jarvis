package com.nick.jarvis.memory;

import java.util.regex.Pattern;

/**
 * 识别绝对不能存进记忆的敏感信息：银行卡号、身份证号、密码。
 *
 * 这类信息对聊天毫无用处，存下来却是纯粹的风险（数据泄露、被提示词注入诱导复述出来）。
 * 提示词里已经要求模型不要记，这里在代码层面再挡一道：不能只靠模型自觉（纵深防御）。
 * 宁可偶尔误伤（比如一串很长的订单号），也不能漏过真正的卡号。
 */
final class SensitiveInfoFilter {

    /** 银行卡号：13～19 位数字，中间可能有空格或横线。手机号是 11 位，不会被误判 */
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d(?:[ -]?\\d){12,18}(?!\\d)");
    /** 身份证号：18 位（最后一位可能是 X），或者老的 15 位 */
    private static final Pattern ID_CARD = Pattern.compile("(?<![\\dXx])(?:\\d{17}[\\dXx]|\\d{15})(?![\\dXx])");
    /**
     * 密码、验证码、口令后面跟着具体内容：关键词后面紧跟“是 / 为 / 冒号 / 等号”，或者直接跟着 4 位以上数字。
     * 要求有这些标志，是为了不误伤“在学密码学”这类正常的话。
     * pin 前后用“不是英文字母”判断，而不是 \\b：Java 的 \\b 把汉字也当成单词字符，“我的pin是1234”会匹配不上
     */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(?:密码|口令|验证码|支付码|password|passwd|(?<![A-Za-z])pin(?![A-Za-z]))\\s*(?:(?:是|为|:|：|=)\\s*\\S+|\\d{4,})");

    private SensitiveInfoFilter() {
    }

    static boolean containsSensitiveInfo(String text) {
        if (text == null) {
            return false;
        }
        return BANK_CARD.matcher(text).find() || ID_CARD.matcher(text).find() || SECRET.matcher(text).find();
    }
}
