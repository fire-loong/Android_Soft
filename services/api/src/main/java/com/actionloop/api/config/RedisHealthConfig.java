package com.actionloop.api.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 自定义 Redis 健康指示器：使用 PING 而非 INFO 解析。
 * <p>背景：Spring Boot 自带的 RedisHealthIndicator 通过 INFO 命令并用
 * {@link java.util.Properties#load} 解析返回内容；Windows 版 Redis 的 INFO
 * 中 executable 字段含反斜杠路径，其中「反斜杠 + u」被 Properties.load
 * 当作 Unicode 转义起始符导致解析失败（"Cannot read Redis info"），使健康检查
 * 恒为 DOWN。生产环境使用 Linux Redis 7 无此问题，但本地开发使用 Windows Redis。
 * PING 式检查更轻量且跨平台一致，故覆盖默认指示器。
 */
@Configuration
public class RedisHealthConfig {

    @Bean("redisHealthIndicator")
    public HealthIndicator redisHealthIndicator(RedisConnectionFactory connectionFactory) {
        return () -> {
            try (RedisConnection connection = connectionFactory.getConnection()) {
                String pong = connection.ping();
                return Health.up().withDetail("ping", pong).build();
            } catch (Exception ex) {
                return Health.down()
                        .withDetail("error", ex.getClass().getSimpleName() + ": " + ex.getMessage())
                        .build();
            }
        };
    }
}
