package com.actionloop.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与校验：HS256，短期 access + 可轮换 refresh。
 * access 携带 userId/deviceId/email；refresh 额外携带 refresh_family 与 jti 用于轮换与重用检测。
 * 任何解析失败抛出 {@link JwtException}，由调用方映射为统一错误码，不泄露签名细节。
 */
@Service
public class JwtService {

    private final JwtProperties props;
    private SecretKey key;
    private long accessTtlSeconds;
    private long refreshTtlSeconds;

    public JwtService(JwtProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        String configured = props.secret();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("ACTIONLOOP_JWT_SECRET 未配置，拒绝启动");
        }
        if (configured.contains("change_me") || configured.contains("changeme")
                || configured.contains("default_secret")) {
            // 默认/占位密钥可被公开推导，等同无认证；生产与本地都必须显式注入
            throw new IllegalStateException("ACTIONLOOP_JWT_SECRET 仍为占位值，拒绝启动；请注入至少 32 字节随机密钥");
        }
        byte[] secret = configured.getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("ACTIONLOOP_JWT_SECRET 必须至少 32 字节（当前 " + secret.length + "）");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.accessTtlSeconds = Math.max(60L, props.accessTtlMinutes()) * 60L;
        this.refreshTtlSeconds = Math.max(1L, props.refreshTtlDays()) * 24L * 3600L;
    }

    public long accessTtlSeconds() {
        return accessTtlSeconds;
    }

    public String issueAccessToken(UUID userId, UUID deviceId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("typ", "access")
                .claim("deviceId", deviceId.toString())
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTtlSeconds)))
                .signWith(key)
                .compact();
    }

    public String issueRefreshToken(UUID userId, UUID deviceId, String family, String jti) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("typ", "refresh")
                .claim("deviceId", deviceId.toString())
                .claim("fam", family)
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(refreshTtlSeconds)))
                .signWith(key)
                .compact();
    }

    /** 校验并解析 access token；失败抛 JwtException（含过期 ExpiredJwtException）。 */
    public Claims parseAccessToken(String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        if (!"access".equals(c.get("typ", String.class))) {
            throw new JwtException("not an access token");
        }
        return c;
    }

    /** 校验并解析 refresh token，要求含 fam 与 jti；失败抛 JwtException。 */
    public Claims parseRefreshToken(String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        if (!"refresh".equals(c.get("typ", String.class))) {
            throw new JwtException("not a refresh token");
        }
        if (c.get("fam", String.class) == null || c.getId() == null) {
            throw new JwtException("malformed refresh token");
        }
        return c;
    }
}
