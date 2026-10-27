package com.nick.jarvis.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.DispatcherType;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        http
                // 用 token 认证，不依赖 Cookie，所以不需要 CSRF 防护
                .csrf(AbstractHttpConfigurer::disable)
                // 无状态：服务器不创建 Session，每个请求都靠 token 证明身份
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 关掉 Spring Security 自带的登录页和 Basic 认证，我们用自己的登录接口
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // 流式响应的后续分发、错误页转发：真正的请求在第一次进来时已经校验过了
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        // 页面和静态资源、注册登录接口、健康检查，不需要登录
                        .requestMatchers("/", "/index.html", "/login.html", "/favicon.ico", "/error").permitAll()
                        .requestMatchers("/api/auth/register", "/api/auth/login", "/api/ping").permitAll()
                        // 其他所有接口都必须登录
                        .anyRequest().authenticated())
                // 未登录访问受保护接口：返回 401 和 JSON，而不是跳转到登录页
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"message\":\"请先登录\"}");
                }))
                // 在 Spring Security 默认的用户名密码过滤器之前，先执行我们的 JWT 过滤器
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * BCrypt：专门为密码设计的哈希算法。
     * 每次加密都会随机加盐，同一个密码两次加密的结果也不同；而且故意算得慢，暴力破解成本很高。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
