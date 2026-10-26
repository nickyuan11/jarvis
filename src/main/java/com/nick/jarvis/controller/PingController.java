package com.nick.jarvis.controller;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第一个接口：用来确认服务已经正常启动。
 * 访问 http://localhost:8080/api/ping 会返回一段 JSON。
 */
@RestController
@RequestMapping("/api")
public class PingController {

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of(
                "status", "ok",
                "message", "Jarvis is online",
                "time", LocalDateTime.now().toString()
        );
    }
}
