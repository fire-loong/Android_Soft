package com.actionloop.api.web.dto;

import com.actionloop.api.domain.Role;
import com.actionloop.api.domain.WorkspaceType;

import java.time.Instant;
import java.util.UUID;

/** 成员视角的空间视图：含本人在该空间的角色。 */
public record WorkspaceView(
        UUID id,
        String name,
        WorkspaceType type,
        Role role,
        Instant joinedAt
) {
}
