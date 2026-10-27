package com.nick.jarvis.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nick.jarvis.dto.AuthResponse;
import com.nick.jarvis.dto.LoginRequest;
import com.nick.jarvis.dto.RegisterRequest;
import com.nick.jarvis.dto.UserView;
import com.nick.jarvis.service.AuthService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public AuthResponse register(@RequestBody RegisterRequest request) {
        return authService.register(request.username(), request.password(), request.nickname());
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest request) {
        return authService.login(request.username(), request.password());
    }

    /** 当前登录用户的信息，前端用它判断 token 是否还有效 */
    @GetMapping("/me")
    public UserView me() {
        return authService.me();
    }
}
