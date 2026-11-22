package com.actionloop.api.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 刷新令牌轮换请求：重用检测到即撤销整个设备会话。 */
public record RefreshRequest(
        @NotBlank String refreshToken
) {
}
