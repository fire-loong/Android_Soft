package com.actionloop.api.web.dto;

import com.actionloop.api.domain.DevicePlatform;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 注册请求：同时创建账户与首个设备会话，并自动开通个人空间（指导书 5.x）。
 * 密码仅用于本地校验，落库为 Argon2id 哈希，不记录明文。
 */
public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 3, max = 32) String username,
        @NotBlank @Size(min = 8, max = 128) String password,
        String displayName,
        String timezone,
        String locale,
        @NotBlank @Size(max = 64) String deviceName,
        @NotNull DevicePlatform devicePlatform
) {
}
