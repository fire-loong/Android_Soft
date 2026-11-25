package com.actionloop.api.web.dto;

import java.util.List;

/** 当前登录用户、空间成员视图与账户注销状态（指导书 13.2 GET /me）。 */
public record MeResponse(
        UserResponse user,
        List<WorkspaceView> workspaces,
        AccountDeletionView accountDeletion
) {
}
