package com.actionloop.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置（application.yml 的 app.jwt.*）。
 * secret 生产环境必须通过环境变量注入且不少于 32 字节，默认值仅用于本地开发。
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        long accessTtlMinutes,
        long refreshTtlDays
) {
}
