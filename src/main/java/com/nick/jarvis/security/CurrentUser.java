package com.nick.jarvis.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * 获取当前登录用户。
 * JwtAuthenticationFilter 校验 token 后，把用户 id 放进了 SecurityContext，这里把它取出来。
 * 业务代码只调用 CurrentUser.id()，不需要关心登录是怎么实现的。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Long id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
    }

    /** 当前请求携带的 token 信息（由 JwtAuthenticationFilter 放入） */
    public static JwtService.ParsedToken token() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getDetails() instanceof JwtService.ParsedToken token) {
            return token;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
    }
}
