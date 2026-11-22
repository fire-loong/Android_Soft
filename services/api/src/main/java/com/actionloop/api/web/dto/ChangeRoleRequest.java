package com.actionloop.api.web.dto;

import com.actionloop.api.domain.Role;
import jakarta.validation.constraints.NotNull;

/** 变更成员角色。 */
public record ChangeRoleRequest(
        @NotNull Role role
) {
}
