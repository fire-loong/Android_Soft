package com.actionloop.shared.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一成功响应信封。所有响应含 requestId；列表使用 {@link CursorPage}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        T data,
        String requestId
) {
    public static <T> ApiResponse<T> of(T data, String requestId) {
        return new ApiResponse<>(data, requestId);
    }
}
