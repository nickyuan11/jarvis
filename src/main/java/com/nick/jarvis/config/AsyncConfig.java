package com.nick.jarvis.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 开启 @Async。Spring Boot 会自动配置一个线程池来执行异步方法。
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
