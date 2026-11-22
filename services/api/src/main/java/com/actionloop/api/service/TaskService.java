package com.actionloop.api.service;

import com.actionloop.api.domain.Role;
import com.actionloop.api.domain.Task;
import com.actionloop.api.domain.TaskStatus;
import com.actionloop.api.repository.CommentRepository;
import com.actionloop.api.repository.MaterialRepository;
import com.actionloop.api.repository.ReminderRepository;
import com.actionloop.api.repository.TaskRepository;
import com.actionloop.api.repository.TaskStepRepository;
import com.actionloop.api.web.dto.CreateTaskRequest;
import com.actionloop.api.web.dto.TaskView;
import com.actionloop.api.web.dto.TransitionRequest;
import com.actionloop.api.web.dto.UpdateTaskRequest;
import com.actionloop.shared.audit.AuditAction;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 事项服务：统一主链路的核心聚合（指导书 3.x / WP03）。
 * - 所有写操作先做空间成员权限校验，VIEWER 只能查看。
 * - 状态变更只能走状态机，禁止任意 status 赋值。
 * - 更新/转换强制 baseVersion 乐观锁，冲突返回 VERSION_CONFLICT。
 * - AI 只产生候选，不能在本服务直接改状态（状态转换必须由用户请求触发）。
 */
@Service
@Transactional(noRollbackFor = BusinessException.class)
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskStepRepository stepRepository;
    private final MaterialRepository materialRepository;
    private final CommentRepository commentRepository;
    private final ReminderRepository reminderRepository;
    private final PermissionService permissionService;
    private final TaskStateMachine stateMachine;
    private final AuditService auditService;

    public TaskService(TaskRepository taskRepository, TaskStepRepository stepRepository,
                       MaterialRepository materialRepository, CommentRepository commentRepository,
                       ReminderRepository reminderRepository, PermissionService permissionService,
                       TaskStateMachine stateMachine, AuditService auditService) {
        this.taskRepository = taskRepository;
        this.stepRepository = stepRepository;
        this.materialRepository = materialRepository;
        this.commentRepository = commentRepository;
        this.reminderRepository = reminderRepository;
        this.permissionService = permissionService;
        this.stateMachine = stateMachine;
        this.auditService = auditService;
    }

    public TaskView create(UUID userId, CreateTaskRequest req) {
        permissionService.requireAtLeastRole(req.workspaceId(), userId, Role.MEMBER);
        TaskStatus initial = req.status() != null ? req.status() : TaskStatus.ACTIVE;
        if (!stateMachine.isInitialAllowed(initial)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "事项初始状态不合法：" + initial);
        }
        Task task = new Task();
        task.setWorkspaceId(req.workspaceId());
        task.setTitle(req.title().trim());
        task.setStatus(initial);
        task.setOwnerId(userId);
        // #7：经办人必须是该空间的有效成员，禁止指派给非成员（指导书 3.5 权限矩阵）。
        if (req.assigneeId() != null) {
            permissionService.requireActiveMember(req.workspaceId(), req.assigneeId());
        }
        task.setAssigneeId(req.assigneeId() != null ? req.assigneeId() : userId);
        task.setDueAt(req.dueAt());
        task.setDueDate(req.dueDate());
        task.setDueTimezone(req.dueTimezone());
        task.setTimePrecision(req.timePrecision());
        task.setPriority(req.priority() != null ? req.priority() : 0);
        task.setDescription(req.description());
        task.setCreatedBy(userId);
        taskRepository.save(task);
        auditService.record(userId, req.workspaceId(), AuditAction.TASK_CREATE, "task",
                task.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of("status", initial.name()));
        return TaskView.of(task);
    }

    public TaskView get(UUID workspaceId, UUID taskId, UUID userId) {
        permissionService.requireActiveMember(workspaceId, userId);
        return TaskView.of(loadInWorkspace(workspaceId, taskId));
    }

    public Page<TaskView> list(UUID workspaceId, TaskStatus status, UUID userId, Pageable pageable) {
        permissionService.requireActiveMember(workspaceId, userId);
        Page<Task> page = status != null
                ? taskRepository.findByWorkspaceIdAndStatus(workspaceId, status, pageable)
                : taskRepository.findByWorkspaceId(workspaceId, pageable);
        return page.map(TaskView::of);
    }

    public TaskView update(UUID workspaceId, UUID taskId, UUID userId, UpdateTaskRequest req) {
        permissionService.requireAtLeastRole(workspaceId, userId, Role.MEMBER);
        Task task = loadInWorkspace(workspaceId, taskId);
        requireCanModify(workspaceId, userId, task);
        checkVersion(task, req.baseVersion());
        if (req.assigneeId() != null) {
            // 先做权限校验再修改任何字段，避免异常后托管实体被 flush 造成越权写入
            permissionService.requireActiveMember(workspaceId, req.assigneeId());
        }
        if (req.title() != null) {
            task.setTitle(req.title().trim());
        }
        if (req.assigneeId() != null) {
            task.setAssigneeId(req.assigneeId());
        }
        // PATCH 语义（指导书 13.1）：缺字段表示不变，空值才表示清空。仅非 null 才覆盖。
        if (req.dueAt() != null) {
            task.setDueAt(req.dueAt());
        }
        if (req.dueDate() != null) {
            task.setDueDate(req.dueDate());
        }
        if (req.dueTimezone() != null) {
            task.setDueTimezone(req.dueTimezone());
        }
        if (req.timePrecision() != null) {
            task.setTimePrecision(req.timePrecision());
        }
        if (req.priority() != null) {
            task.setPriority(req.priority());
        }
        if (req.description() != null) {
            task.setDescription(req.description());
        }
        taskRepository.saveAndFlush(task);
        auditService.record(userId, workspaceId, AuditAction.TASK_UPDATE, "task",
                task.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of());
        return TaskView.of(task);
    }

    public TaskView transition(UUID workspaceId, UUID taskId, UUID userId, TransitionRequest req) {
        permissionService.requireAtLeastRole(workspaceId, userId, Role.MEMBER);
        Task task = loadInWorkspace(workspaceId, taskId);
        requireCanModify(workspaceId, userId, task);
        checkVersion(task, req.baseVersion());
        TaskStatus from = task.getStatus();
        stateMachine.requireTransition(from, req.status());
        task.setStatus(req.status());
        taskRepository.saveAndFlush(task);
        auditService.record(userId, workspaceId, AuditAction.TASK_TRANSITION, "task",
                task.getId().toString(), AuditAction.RESULT_SUCCESS,
                Map.of("from", from.name(), "to", req.status().name()));
        return TaskView.of(task);
    }

    public void delete(UUID workspaceId, UUID taskId, UUID userId, Integer baseVersion) {
        permissionService.requireAtLeastRole(workspaceId, userId, Role.MEMBER);
        Task task = loadInWorkspace(workspaceId, taskId);
        checkVersion(task, baseVersion);
        boolean isOwner = task.getOwnerId().equals(userId);
        boolean isAdmin = permissionService.hasAtLeastRole(workspaceId, userId, Role.ADMIN);
        if (!isOwner && !isAdmin) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "只有事项负责人或空间管理员可以删除事项");
        }
        // #16b：级联软删子实体（步骤/材料/评论），清理提醒调度，避免孤儿数据泄漏。
        Instant now = Instant.now();
        stepRepository.softDeleteByTaskId(taskId, now);
        materialRepository.softDeleteByTaskId(taskId, now);
        commentRepository.softDeleteByTaskId(taskId, now);
        reminderRepository.deleteByTaskId(taskId);
        task.setDeletedAt(now);
        taskRepository.save(task);
        auditService.record(userId, workspaceId, AuditAction.TASK_DELETE, "task",
                task.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of());
    }

    private Task loadInWorkspace(UUID workspaceId, UUID taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "事项不存在"));
        if (!task.getWorkspaceId().equals(workspaceId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "事项不属于该空间");
        }
        return task;
    }

    /** 编辑本人事项：负责人、经办人或空间管理员（ADMIN+）可修改。 */
    private void requireCanModify(UUID workspaceId, UUID userId, Task task) {
        boolean involved = task.getOwnerId().equals(userId)
                || (task.getAssigneeId() != null && task.getAssigneeId().equals(userId));
        boolean isAdmin = permissionService.hasAtLeastRole(workspaceId, userId, Role.ADMIN);
        if (!involved && !isAdmin) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "只能编辑本人负责或经办的事项");
        }
    }

    private void checkVersion(Task task, Integer baseVersion) {
        if (baseVersion == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少 baseVersion");
        }
        if (!baseVersion.equals(task.getVersion())) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "事项已被他人修改，请刷新后重试");
        }
    }
}
