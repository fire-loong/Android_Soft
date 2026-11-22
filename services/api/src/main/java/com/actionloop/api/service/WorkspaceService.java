package com.actionloop.api.service;

import com.actionloop.api.domain.Membership;
import com.actionloop.api.domain.MembershipStatus;
import com.actionloop.api.domain.Role;
import com.actionloop.api.domain.User;
import com.actionloop.api.domain.Workspace;
import com.actionloop.api.domain.WorkspaceType;
import com.actionloop.api.repository.MembershipRepository;
import com.actionloop.api.repository.WorkspaceRepository;
import com.actionloop.api.web.dto.CreateWorkspaceRequest;
import com.actionloop.api.web.dto.WorkspaceDetailView;
import com.actionloop.api.web.dto.WorkspaceView;
import com.actionloop.shared.audit.AuditAction;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 空间与成员服务（阶段 2 基础）：注册时自动开通个人空间并授予 OWNER。
 * 完整团队空间、邀请与权限矩阵在 p2-3 补齐。
 */
@Service
@Transactional
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final MembershipRepository membershipRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;

    public WorkspaceService(WorkspaceRepository workspaceRepository, MembershipRepository membershipRepository,
                            PermissionService permissionService, AuditService auditService) {
        this.workspaceRepository = workspaceRepository;
        this.membershipRepository = membershipRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    /** 为用户开通个人空间（幂等：已存在则返回现有空间）。 */
    public Workspace provisionPersonal(User user) {
        return workspaceRepository.findByOwnerIdAndType(user.getId(), WorkspaceType.PERSONAL)
                .orElseGet(() -> {
                    Workspace ws = new Workspace();
                    ws.setType(WorkspaceType.PERSONAL);
                    ws.setOwnerId(user.getId());
                    ws.setName((user.getDisplayName() != null ? user.getDisplayName() : user.getUsername()) + " 的个人空间");
                    ws.setCreatedBy(user.getId());
                    workspaceRepository.save(ws);

                    Membership membership = new Membership();
                    membership.setWorkspaceId(ws.getId());
                    membership.setUserId(user.getId());
                    membership.setRole(Role.OWNER);
                    membership.setStatus(MembershipStatus.ACTIVE);
                    membership.setJoinedAt(Instant.now());
                    membership.setCreatedBy(user.getId());
                    membershipRepository.save(membership);
                    return ws;
                });
    }

    /** 列出用户当前所有有效成员关系的空间。 */
    public List<WorkspaceView> listMyWorkspaces(UUID userId) {
        List<Membership> memberships = membershipRepository.findByUserIdAndStatus(userId, MembershipStatus.ACTIVE);
        if (memberships.isEmpty()) {
            return List.of();
        }
        Set<UUID> workspaceIds = memberships.stream().map(Membership::getWorkspaceId).collect(Collectors.toSet());
        Map<UUID, Workspace> workspaceMap = workspaceRepository.findAllById(workspaceIds).stream()
                .collect(Collectors.toMap(Workspace::getId, Function.identity()));
        return memberships.stream()
                .map(m -> {
                    Workspace w = workspaceMap.get(m.getWorkspaceId());
                    return w == null ? null
                            : new WorkspaceView(w.getId(), w.getName(), w.getType(), m.getRole(), m.getJoinedAt());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /** 创建团队空间，创建者自动成为 OWNER。 */
    public WorkspaceView createTeam(UUID userId, CreateWorkspaceRequest request) {
        WorkspaceType type = request.type() != null ? request.type() : WorkspaceType.TEAM;
        if (type == WorkspaceType.PERSONAL) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "个人空间由系统自动创建，不可手动新建");
        }
        Workspace workspace = new Workspace();
        workspace.setType(type);
        workspace.setOwnerId(userId);
        workspace.setName(request.name().trim());
        workspace.setCreatedBy(userId);
        workspaceRepository.save(workspace);

        Membership membership = new Membership();
        membership.setWorkspaceId(workspace.getId());
        membership.setUserId(userId);
        membership.setRole(Role.OWNER);
        membership.setStatus(MembershipStatus.ACTIVE);
        membership.setJoinedAt(Instant.now());
        membership.setCreatedBy(userId);
        membershipRepository.save(membership);

        auditService.record(userId, workspace.getId(), AuditAction.WORKSPACE_CREATE, "workspace",
                workspace.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of("type", type.name()));
        return new WorkspaceView(workspace.getId(), workspace.getName(), workspace.getType(),
                Role.OWNER, membership.getJoinedAt());
    }

    /** 空间详情（含当前用户角色）；非成员越权拒绝。 */
    public WorkspaceDetailView getDetail(UUID workspaceId, UUID userId) {
        Membership membership = permissionService.requireActiveMember(workspaceId, userId);
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "空间不存在"));
        return new WorkspaceDetailView(workspace.getId(), workspace.getName(), workspace.getType(),
                workspace.getOwnerId(), membership.getRole(), workspace.getCreatedAt());
    }
}
