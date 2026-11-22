package com.actionloop.api.web.dto;

import com.actionloop.api.domain.Role;
import com.actionloop.api.domain.WorkspaceType;

import java.time.Instant;
import java.util.UUID;

/** 待我接受的邀请视图。 */
public record InviteView(
        UUID workspaceId,
        String workspaceName,
        WorkspaceType workspaceType,
        Role role,
        Instant invitedAt
) {
}
