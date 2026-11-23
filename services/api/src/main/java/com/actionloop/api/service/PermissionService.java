package com.actionloop.api.service;

import com.actionloop.api.domain.Membership;
import com.actionloop.api.domain.MembershipStatus;
import com.actionloop.api.domain.Role;
import com.actionloop.api.repository.MembershipRepository;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * 权限校验：每次服务端请求都执行“账户状态 → 空间成员状态 → 操作权限”检查（指导书 3.5）。
 * 不能仅依赖前端隐藏按钮；越权一律抛 PERMISSION_DENIED。
 * HELPER 不是空间成员角色，仅在协助会话范围内校验（阶段 5 实现）。
 */
@Service
public class PermissionService {

    private final MembershipRepository membershipRepository;

    public PermissionService(MembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    public static int rank(Role role) {
        return switch (role) {
            case OWNER -> 4;
            case ADMIN -> 3;
            case MEMBER -> 2;
            case VIEWER -> 1;
            case HELPER -> 0;
        };
    }

    public Optional<Membership> activeMembership(UUID workspaceId, UUID userId) {
        return membershipRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE);
    }

    public Membership requireActiveMember(UUID workspaceId, UUID userId) {
        return activeMembership(workspaceId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PERMISSION_DENIED, "不是该空间的有效成员"));
    }

    /** 与成员撤权共用行锁，供需要在同一事务内完成权限复核和写入的操作使用。 */
    public Membership requireActiveMemberForUpdate(UUID workspaceId, UUID userId) {
        return membershipRepository.findLockedByWorkspaceIdAndUserId(workspaceId, userId)
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.PERMISSION_DENIED, "不是该空间的有效成员"));
    }

    public boolean hasAtLeastRole(UUID workspaceId, UUID userId, Role minRole) {
        return activeMembership(workspaceId, userId)
                .map(m -> rank(m.getRole()) >= rank(minRole))
                .orElse(false);
    }

    public void requireAtLeastRole(UUID workspaceId, UUID userId, Role minRole) {
        Membership m = requireActiveMember(workspaceId, userId);
        if (rank(m.getRole()) < rank(minRole)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "无权执行该操作");
        }
    }

    public boolean canManageMembers(UUID workspaceId, UUID userId) {
        return activeMembership(workspaceId, userId)
                .map(m -> m.getRole() == Role.OWNER || m.getRole() == Role.ADMIN)
                .orElse(false);
    }

    public void requireCanManageMembers(UUID workspaceId, UUID userId) {
        if (!canManageMembers(workspaceId, userId)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "无权管理该空间成员");
        }
    }
}
