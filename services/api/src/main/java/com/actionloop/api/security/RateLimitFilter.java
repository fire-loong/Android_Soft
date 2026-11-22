package com.actionloop.api.security;

import com.actionloop.api.config.RateLimitProperties;
import com.actionloop.api.web.RequestIdFilter;
import com.actionloop.shared.error.ApiError;
import com.actionloop.shared.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 认证端点限流过滤器（指导书 15.3）：在 JWT 过滤器之前执行，按 IP 限制 register/login/refresh 频率。
 * 未命中的请求直接放行，不影响其他端点。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;
    private final boolean trustForwardedFor;

    public RateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties, ObjectMapper objectMapper,
                           @org.springframework.beans.factory.annotation.Value(
                                   "${app.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.trustForwardedFor = trustForwardedFor;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = normalize(request.getRequestURI());
        return !path.equals("/api/v1/auth/register")
                && !path.equals("/api/v1/auth/login")
                && !path.equals("/api/v1/auth/refresh");
    }

    /** 去掉尾斜杠，避免 /login/ 绕过限流。 */
    private String normalize(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = normalize(request.getRequestURI());
        RateLimiter.AuthEndpoint endpoint = switch (path) {
            case "/api/v1/auth/register" -> RateLimiter.AuthEndpoint.REGISTER;
            case "/api/v1/auth/login" -> RateLimiter.AuthEndpoint.LOGIN;
            case "/api/v1/auth/refresh" -> RateLimiter.AuthEndpoint.REFRESH;
            default -> null;
        };
        if (endpoint == null || rateLimiter.tryAcquire(endpoint, clientIp(request))) {
            chain.doFilter(request, response);
            return;
        }
        writeLimited(response, endpoint);
    }

    private void writeLimited(HttpServletResponse response, RateLimiter.AuthEndpoint endpoint) throws IOException {
        RateLimitProperties.Endpoint cfg = switch (endpoint) {
            case REGISTER -> properties.getRegister();
            case LOGIN -> properties.getLogin();
            case REFRESH -> properties.getRefresh();
        };
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId == null) {
            requestId = response.getHeader(RequestIdFilter.HEADER);
        }
        response.setStatus(ErrorCode.RATE_LIMITED.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", String.valueOf(cfg.getWindowSeconds()));
        objectMapper.writeValue(response.getWriter(),
                ApiError.of(ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后再试", requestId, null));
    }

    private String clientIp(HttpServletRequest request) {
        // 默认不信任客户端可伪造的 X-Forwarded-For / X-Real-IP；仅当部署在可信反代后方才开启
        if (!trustForwardedFor) {
            return request.getRemoteAddr();
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }
}
