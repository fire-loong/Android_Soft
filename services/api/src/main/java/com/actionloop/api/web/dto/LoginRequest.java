package com.actionloop.api.web.dto;

import com.actionloop.api.domain.DevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 登录请求：login 可为邮箱或用户名，服务端不区分以避免账户枚举。 */
public record LoginRequest(
        @NotBlank String login,
        @NotBlank String password,
        @NotBlank @Size(max = 64) String deviceName,
        @NotNull DevicePlatform devicePlatform
) {
}
