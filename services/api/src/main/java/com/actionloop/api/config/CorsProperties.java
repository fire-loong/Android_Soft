package com.actionloop.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

/**
 * CORS 白名单（指导书 15.x 安全基线）：仅允许显式配置的来源，禁止全放行；携带凭证时 Origin 不得为通配。
 * 生产通过 ACTIONLOOP_CORS_ALLOWED_ORIGINS 注入（逗号分隔），默认仅放行本地开发端。
 */
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    /** 逗号分隔的允许来源；为空时使用默认本地开发来源 */
    private String allowedOrigins = "";
    private boolean allowCredentials = true;
    private long maxAgeSeconds = 3600;

    private static final List<String> DEFAULT_DEV_ORIGINS = List.of(
            "http://localhost:5173", "http://127.0.0.1:5173",
            "http://localhost:1420", "http://127.0.0.1:1420",
            "http://localhost:3000", "http://127.0.0.1:3000",
            "tauri://localhost", "http://tauri.localhost"
    );

    public List<String> resolvedOrigins() {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return DEFAULT_DEV_ORIGINS;
        }
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public String getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(String allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    public boolean isAllowCredentials() { return allowCredentials; }
    public void setAllowCredentials(boolean allowCredentials) { this.allowCredentials = allowCredentials; }
    public long getMaxAgeSeconds() { return maxAgeSeconds; }
    public void setMaxAgeSeconds(long maxAgeSeconds) { this.maxAgeSeconds = maxAgeSeconds; }
}
