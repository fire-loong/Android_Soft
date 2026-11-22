package com.actionloop.api.web.dto;

import com.actionloop.api.domain.Role;
import com.actionloop.api.domain.WorkspaceType;

import java.time.Instant;
import java.util.UUID;

/** 空间详情（含当前用户在该空间的角色）。 */
public record WorkspaceDetailView(
        UUID id,
        String name,
        WorkspaceType type,
        UUID ownerId,
        Role myRole,
        Instant createdAt
) {
}
