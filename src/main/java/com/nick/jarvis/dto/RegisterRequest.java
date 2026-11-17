package com.nick.jarvis.dto;

/** inviteCode：服务器配置了邀请码时必填 */
public record RegisterRequest(String username, String password, String nickname, String inviteCode) {
}
