package com.actionloop.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 空间导出请求（指导书 13.2：异步、短期下载、二次认证）。
 * password 仅用于服务端重新校验，不落库、不进日志。
 */
public record CreateExportRequest(
        @NotNull UUID workspaceId,
        String exportType,
        @NotBlank @Size(max = 128) String password
) {
}
