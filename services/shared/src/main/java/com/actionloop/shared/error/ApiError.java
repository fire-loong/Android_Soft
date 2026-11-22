package com.actionloop.shared.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * 统一错误响应体，严格遵循指导书 13.4：
 * <pre>
 * { "error": { "code", "message", "requestId", "details": {...} } }
 * </pre>
 * 错误响应不得包含堆栈、SQL、密钥或原文。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        ErrorBody error
) {
    public record ErrorBody(
            String code,
            String message,
            String requestId,
            Instant timestamp,
            Map<String, Object> details
    ) {}

    public static ApiError of(ErrorCode code, String message, String requestId, Map<String, Object> details) {
        return new ApiError(new ErrorBody(code.name(), message, requestId, Instant.now(), details));
    }
}
