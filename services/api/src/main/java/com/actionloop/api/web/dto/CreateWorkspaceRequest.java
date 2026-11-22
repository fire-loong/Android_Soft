package com.actionloop.api.web.dto;

import com.actionloop.api.domain.WorkspaceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建空间请求：type 缺省为 TEAM；PERSONAL 由注册时系统自动创建，不允许手动建。 */
public record CreateWorkspaceRequest(
        @NotBlank @Size(max = 64) String name,
        WorkspaceType type
) {
}
