package com.actionloop.api.web.dto;

import java.util.List;

/** 当前登录用户及其空间成员视图。 */
public record MeResponse(
        UserResponse user,
        List<WorkspaceView> workspaces
) {
}
