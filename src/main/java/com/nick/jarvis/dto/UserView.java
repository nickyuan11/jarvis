package com.nick.jarvis.dto;

/**
 * 返回给前端的用户信息，注意没有 password 字段。
 */
public record UserView(Long id, String username, String nickname) {
}
