package com.nick.jarvis.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 读取 application.yml 中 jarvis.jwt 下的配置。
 * 比起到处写 @Value，把一组相关配置绑定到一个对象上更清晰，也方便校验。
 */
@ConfigurationProperties(prefix = "jarvis.jwt")
public record JwtProperties(String secret, Duration expiration) {
}
