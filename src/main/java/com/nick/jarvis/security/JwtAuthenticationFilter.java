package com.nick.jarvis.security;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 每个请求进来时，检查请求头里的 token：
 *   Authorization: Bearer xxxxx
 * 签名有效、没过期、也不在黑名单里，就把用户 id 放进 SecurityContext，
 * 后面的代码通过 CurrentUser.id() 取出来。
 * 否则什么都不做，交给 SecurityConfig 的规则决定是否放行。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final TokenBlacklist blacklist;

    public JwtAuthenticationFilter(JwtService jwtService, TokenBlacklist blacklist) {
        this.jwtService = jwtService;
        this.blacklist = blacklist;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(PREFIX)) {
            JwtService.ParsedToken token = jwtService.parse(header.substring(PREFIX.length()));
            if (token != null && !blacklist.isRevoked(token.jti())) {
                var authentication = new UsernamePasswordAuthenticationToken(token.userId(), null, List.of());
                // 把解析后的 token 也带上，退出登录时要用到它的 jti 和过期时间
                authentication.setDetails(token);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        chain.doFilter(request, response);
    }
}
