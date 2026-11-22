package com.actionloop.api.service;

import com.actionloop.api.domain.Membership;
import com.actionloop.api.domain.MembershipStatus;
import com.actionloop.api.domain.Role;
import com.actionloop.api.domain.User;
import com.actionloop.api.domain.Workspace;
import com.actionloop.api.repository.MembershipRepository;
import com.actionloop.api.repository.UserRepository;
import com.actionloop.api.repository.WorkspaceRepository;
import com.actionloop.api.web.dto.InviteMemberRequest;
import com.actionloop.api.web.dto.InviteView;
import com.actionloop.api.web.dto.MemberView;
import com.actionloop.shared.audit.AuditAction;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 成员与邀请服务（指导书 3.5 / 5.x）：
 * - 邀请：管理员为已注册邮箱创建 PENDING 成员关系，对方接受后生效。
 * - 角色：OWNER 可授予/转移 OWNER；ADMIN 可管理除 OWNER 外的角色。
 * - 移除/退出：OWNER 退出前须先转移所有权；ADMIN 不能移除 OWNER。
 * 越权操作全部抛 PERMISSION_DENIED，并写审计。
 */
@Service
@Transactional
public class MembershipService {

    private final MembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final int inviteTtlDays;

    public MembershipService(MembershipRepository membershipRepository, UserRepository userRepository,
                             WorkspaceRepository workspaceRepository, PermissionService permissionService,
                             AuditService auditService,
                             @Value("${app.membership.invite-ttl-days:7}") int inviteTtlDays) {
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.inviteTtlDays = inviteTtlDays;
    }

    public MemberView invite(UUID workspaceId, UUID inviterId, InviteMemberRequest request) {
        permissionService.requireCanManageMembers(workspaceId, inviterId);
        Role role = request.role() != null ? request.role() : Role.MEMBER;
        if (role == Role.OWNER) {
            permissionService.requireAtLeastRole(workspaceId, inviterId, Role.OWNER);
        }
        User target = userRepository.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "该邮箱尚未注册，请先让对方注册账户"));
        if (target.getId().equals(inviterId)) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "不能邀请自己");
        }
        if (membershipRepository.existsByWorkspaceIdAndUserId(workspaceId, target.getId())) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "该用户已是成员或已被邀请");
        }
        Membership membership = new Membership();
        membership.setWorkspaceId(workspaceId);
        membership.setUserId(target.getId());
        membership.setRole(role);
        membership.setStatus(MembershipStatus.PENDING);
        membership.setJoinedAt(Instant.now());
        membership.setCreatedBy(inviterId);
        // #19 邀请必须带过期时间（指导书 15.3），过期后不可接受。
        membership.setExpiresAt(Instant.now().plus(inviteTtlDays, ChronoUnit.DAYS));
        membershipRepository.save(membership);
        auditService.record(inviterId, workspaceId, AuditAction.MEMBERSHIP_INVITE, "membership",
                membership.getId().toString(), AuditAction.RESULT_SUCCESS,
                Map.of("target", target.getId().toString(), "role", role.name()));
        return MemberView.of(membership, target);
    }

    public MemberView accept(UUID workspaceId, UUID userId) {
        Membership membership = pendingFor(workspaceId, userId);
        membership.setStatus(MembershipStatus.ACTIVE);
        membershipRepository.save(membership);
        User user = userRepository.findById(userId).orElseThrow();
        auditService.record(userId, workspaceId, AuditAction.MEMBERSHIP_JOIN, "membership",
                membership.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of());
        return MemberView.of(membership, user);
    }

    public void decline(UUID workspaceId, UUID userId) {
        Membership membership = pendingFor(workspaceId, userId);
        softDelete(membership);
        auditService.record(userId, workspaceId, AuditAction.MEMBERSHIP_DECLINE, "membership",
                membership.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of());
    }

    public MemberView changeRole(UUID workspaceId, UUID actorId, UUID targetUserId, Role newRole) {
        permissionService.requireCanManageMembers(workspaceId, actorId);
        Membership actor = permissionService.requireActiveMember(workspaceId, actorId);
        Membership target = membershipRepository.findByWorkspaceIdAndUserId(workspaceId, targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "目标用户不是该空间成员"));
        if (target.getStatus() != MembershipStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "目标成员状态异常，无法变更角色");
        }
        if (target.getRole() == Role.OWNER && actor.getRole() != Role.OWNER) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "只有 OWNER 可以修改 OWNER 的角色");
        }
        if (newRole == Role.OWNER && actor.getRole() != Role.OWNER) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "只有 OWNER 可以授予 OWNER 角色");
        }
        // #12 不变量：空间必须始终至少有一名 ACTIVE OWNER，禁止把最后一名 OWNER 降级（指导书 3.5）。
        if (target.getRole() == Role.OWNER && newRole != Role.OWNER) {
            long ownerCount = membershipRepository.countByWorkspaceIdAndRoleAndStatus(
                    workspaceId, Role.OWNER, MembershipStatus.ACTIVE);
            if (ownerCount <= 1) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "空间至少需要一名 OWNER，请先转移所有权");
            }
        }
        if (newRole == Role.OWNER && !targetUserId.equals(actorId)) {
            actor.setRole(Role.ADMIN);
            membershipRepository.save(actor);
        }
        target.setRole(newRole);
        membershipRepository.save(target);
        // #11 所有权转移必须同步更新 Workspace.ownerId，保持聚合根与成员关系一致。
        if (newRole == Role.OWNER) {
            Workspace workspace = workspaceRepository.findById(workspaceId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "空间不存在"));
            if (!targetUserId.equals(workspace.getOwnerId())) {
                workspace.setOwnerId(targetUserId);
                workspaceRepository.save(workspace);
            }
        }
        User user = userRepository.findById(targetUserId).orElseThrow();
        auditService.record(actorId, workspaceId, AuditAction.MEMBERSHIP_ROLE_CHANGE, "membership",
                target.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of("role", newRole.name()));
        return MemberView.of(target, user);
    }

    public void removeMember(UUID workspaceId, UUID actorId, UUID targetUserId) {
        Membership actor = permissionService.requireActiveMember(workspaceId, actorId);
        Membership target = membershipRepository.findByWorkspaceIdAndUserId(workspaceId, targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "目标用户不是该空间成员"));
        boolean self = actorId.equals(targetUserId);
        if (!self) {
            permissionService.requireCanManageMembers(workspaceId, actorId);
            if (target.getRole() == Role.OWNER && actor.getRole() != Role.OWNER) {
                throw new BusinessException(ErrorCode.PERMISSION_DENIED, "不能移除 OWNER");
            }
        } else if (actor.getRole() == Role.OWNER) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "OWNER 请先转移所有权再退出空间");
        }
        softDelete(target);
        auditService.record(actorId, workspaceId, AuditAction.MEMBERSHIP_REMOVE, "membership",
                target.getId().toString(), AuditAction.RESULT_SUCCESS,
                Map.of("target", targetUserId.toString(), "self", self));
    }

    public List<MemberView> listMembers(UUID workspaceId, UUID viewerId) {
        permissionService.requireActiveMember(workspaceId, viewerId);
        List<Membership> memberships = membershipRepository.findByWorkspaceIdAndStatus(workspaceId, MembershipStatus.ACTIVE);
        if (memberships.isEmpty()) {
            return List.of();
        }
        Set<UUID> userIds = memberships.stream().map(Membership::getUserId).collect(Collectors.toSet());
        Map<UUID, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return memberships.stream()
                .map(m -> MemberView.of(m, userMap.get(m.getUserId())))
                .filter(Objects::nonNull)
                .toList();
    }

    public List<InviteView> listMyInvitations(UUID userId) {
        List<Membership> pending = membershipRepository.findByUserIdAndStatus(userId, MembershipStatus.PENDING);
        if (pending.isEmpty()) {
            return List.of();
        }
        Set<UUID> workspaceIds = pending.stream().map(Membership::getWorkspaceId).collect(Collectors.toSet());
        Map<UUID, Workspace> workspaceMap = workspaceRepository.findAllById(workspaceIds).stream()
                .collect(Collectors.toMap(Workspace::getId, Function.identity()));
        return pending.stream()
                .filter(m -> m.getExpiresAt() == null || !m.getExpiresAt().isBefore(Instant.now()))
                .map(m -> {
                    Workspace w = workspaceMap.get(m.getWorkspaceId());
                    return w == null ? null
                            : new InviteView(w.getId(), w.getName(), w.getType(), m.getRole(), m.getCreatedAt());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private Membership pendingFor(UUID workspaceId, UUID userId) {
        Membership membership = membershipRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "没有待处理的邀请"));
        if (membership.getStatus() != MembershipStatus.PENDING) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "该邀请状态不可操作");
        }
        // #19 过期邀请不可接受/拒绝（指导书 15.3）。
        if (membership.getExpiresAt() != null && membership.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.INVITE_EXPIRED, "邀请已过期，请联系管理员重新邀请");
        }
        return membership;
    }

    private void softDelete(Membership membership) {
        membership.setStatus(MembershipStatus.REMOVED);
        membership.setDeletedAt(Instant.now());
        membershipRepository.save(membership);
    }
}
