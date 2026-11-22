package com.actionloop.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/** 初始化上传：客户端先计算 SHA-256 并声明 mime/大小，服务端校验配额与去重（指导书 11.6）。 */
public record InitUploadRequest(
        @NotNull UUID workspaceId,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String sha256,
        @NotBlank String mime,
        @NotNull @Positive Long sizeBytes,
        String fileName
) {
}
