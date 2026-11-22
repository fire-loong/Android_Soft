package com.actionloop.api.service;

import com.actionloop.api.domain.Role;
import com.actionloop.api.domain.Task;
import com.actionloop.api.repository.TaskRepository;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 事项子资源（步骤/材料/提醒/完成凭证）共用的任务加载与修改权限校验。
 * 确保子资源不会越权挂到其他空间的事项上（指导书 3.5）。
 */
@Service
public class TaskGuard {

    private final TaskRepository taskRepository;
    private final PermissionService permissionService;

    public TaskGuard(TaskRepository taskRepository, PermissionService permissionService) {
        this.taskRepository = taskRepository;
        this.permissionService = permissionService;
    }

    public Task loadInWorkspace(UUID workspaceId, UUID taskId) {
        return taskRepository.findByWorkspaceIdAndId(workspaceId, taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "事项不存在"));
    }

    /** 读操作：先校验有效成员，再加载事项（VIEWER 可读）。 */
    public Task loadForRead(UUID workspaceId, UUID taskId, UUID userId) {
        permissionService.requireActiveMember(workspaceId, userId);
        return loadInWorkspace(workspaceId, taskId);
    }

    /** 工作区级批量读：校验有效成员后，返回该空间全部事项 id（成员可读空间内全部事项）。 */
    public java.util.List<UUID> visibleTaskIds(UUID workspaceId, UUID userId) {
        permissionService.requireActiveMember(workspaceId, userId);
        return taskRepository.findIdsByWorkspaceId(workspaceId);
    }

    /** 负责人、经办人或空间管理员（ADMIN+）可修改事项及其子资源。 */
    public void requireCanModify(UUID workspaceId, UUID userId, Task task) {
        permissionService.requireAtLeastRole(workspaceId, userId, Role.MEMBER);
        boolean involved = task.getOwnerId().equals(userId)
                || (task.getAssigneeId() != null && task.getAssigneeId().equals(userId));
        boolean isAdmin = permissionService.hasAtLeastRole(workspaceId, userId, Role.ADMIN);
        if (!involved && !isAdmin) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "只能编辑本人负责或经办的事项");
        }
    }
}
