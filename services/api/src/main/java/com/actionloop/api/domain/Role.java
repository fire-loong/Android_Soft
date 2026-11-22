package com.actionloop.api.domain;

/**
 * 空间角色，权限矩阵见指导书 3.5。HELPER 不自动成为空间成员。
 */
public enum Role {
    OWNER,
    ADMIN,
    MEMBER,
    VIEWER,
    HELPER
}
