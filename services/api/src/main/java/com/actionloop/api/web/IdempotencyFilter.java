package com.actionloop.api.web;

import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.IdempotencyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Idempotency-Key 横切中间件（指导书 13.3）。
 * 仅对已认证的 POST/PUT/PATCH 且带 Idempotency-Key 头生效：进行中 409、哈希不一致 409、已完成重放首次响应。
 * 必须运行在 JwtAuthenticationFilter 之后（SecurityConfig 中 addFilterAfter）。
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public IdempotencyFilter(IdempotencyService idempotency, ObjectMapper objectMapper) {
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    private static final long MAX_BODY_BYTES = 1_048_576L;
    private static final int MAX_CACHE_BYTES = 1_048_576;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 大文件上传/下载不走幂等缓存，防止把 50MB 内容读入内存
        if (request.getRequestURI().startsWith("/api/v1/assets/")) {
            return true;
        }
        String key = request.getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) {
            return true;
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return true;
        }
        String method = request.getMethod();
        return !(method.equals("POST") || method.equals("PUT") || method.equals("PATCH"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }
        UUID actorId = principal.userId();
        String key = request.getHeader("Idempotency-Key");

        CachedBodyHttpServletRequest wrappedRequest;
        try {
            wrappedRequest = new CachedBodyHttpServletRequest(request, MAX_BODY_BYTES);
        } catch (java.io.IOException tooLarge) {
            writeError(response, 413, "PAYLOAD_TOO_LARGE", "请求体过大，已拒绝幂等缓存");
            return;
        }
        String requestHash = computeHash(request, wrappedRequest.getCachedBody());

        IdempotencyService.AcquireResult result = idempotency.tryAcquire(actorId, key, requestHash);
        switch (result.outcome()) {
            case MISMATCH -> {
                writeError(response, 409, "IDEMPOTENCY_MISMATCH", "同一 Idempotency-Key 的请求内容不一致");
                return;
            }
            case IN_PROGRESS -> {
                writeError(response, 409, "IDEMPOTENCY_IN_PROGRESS", "同一请求正在处理中，请勿重复提交");
                return;
            }
            case CACHED -> {
                replayCached(response, result.cachedResponse());
                return;
            }
            case NEW -> { /* 继续执行 */ }
        }

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(wrappedRequest, wrappedResponse);
            int status = wrappedResponse.getStatus();
            if (status >= 500) {
                // 5xx 不缓存，允许客户端重试
                idempotency.release(actorId, key);
            } else if (wrappedResponse.getContentAsByteArray().length > MAX_CACHE_BYTES) {
                // 超大响应不缓存，避免内存放大；释放占用以便客户端可重试
                idempotency.release(actorId, key);
            } else {
                String cached = objectMapper.writeValueAsString(Map.of(
                        "status", status,
                        "contentType", wrappedResponse.getContentType() == null
                                ? "application/json" : wrappedResponse.getContentType(),
                        "body", new String(wrappedResponse.getContentAsByteArray(), StandardCharsets.UTF_8)
                ));
                idempotency.complete(actorId, key, cached);
            }
            wrappedResponse.copyBodyToResponse();
        } catch (Exception e) {
            idempotency.release(actorId, key);
            wrappedResponse.copyBodyToResponse();
            throw e;
        }
    }

    private String computeHash(HttpServletRequest request, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String qs = request.getQueryString() == null ? "" : request.getQueryString();
            String head = request.getMethod() + "|" + request.getRequestURI() + "|" + qs + "|";
            digest.update(head.getBytes(StandardCharsets.UTF_8));
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        String reqId = MDC.get(RequestIdFilter.MDC_KEY);
        if (reqId == null) {
            reqId = "req_" + UUID.randomUUID().toString().replace("-", "");
        }
        Map<String, Object> error = Map.of(
                "code", code, "message", message,
                "requestId", reqId, "timestamp", Instant.now().toString()
        );
        String json = objectMapper.writeValueAsString(Map.of("error", error));
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json);
    }

    private void replayCached(HttpServletResponse response, String cached) throws IOException {
        try {
            JsonNode node = objectMapper.readTree(cached);
            int status = node.path("status").asInt(200);
            String contentType = node.path("contentType").asText("application/json");
            String body = node.path("body").asText("");
            response.setStatus(status);
            response.setContentType(contentType);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(body);
        } catch (Exception e) {
            writeError(response, 500, "INTERNAL_ERROR", "缓存响应解析失败");
        }
    }
}
