package com.actionloop.api.service;

import com.actionloop.api.domain.Task;
import com.actionloop.api.domain.TaskStep;
import com.actionloop.api.domain.TaskStepStatus;
import com.actionloop.api.repository.TaskStepRepository;
import com.actionloop.api.web.dto.CreateStepRequest;
import com.actionloop.api.web.dto.StepView;
import com.actionloop.api.web.dto.UpdateStepRequest;
import com.actionloop.shared.audit.AuditAction;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 事项步骤（指导书 13.2 / 3.1 TaskStep）。position 在事项内唯一；软删除；乐观锁。
 */
@Service
public class TaskStepService {

    private final TaskStepRepository stepRepository;
    private final TaskGuard taskGuard;
    private final PermissionService permissionService;
    private final AuditService auditService;

    public TaskStepService(TaskStepRepository stepRepository, TaskGuard taskGuard,
                           PermissionService permissionService, AuditService auditService) {
        this.stepRepository = stepRepository;
        this.taskGuard = taskGuard;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    public List<StepView> list(UUID workspaceId, UUID taskId, UUID userId) {
        taskGuard.loadForRead(workspaceId, taskId, userId);
        return stepRepository.findByTaskIdOrderByPositionAsc(taskId).stream().map(StepView::of).toList();
    }

    @Transactional
    public StepView create(UUID workspaceId, UUID taskId, UUID userId, CreateStepRequest req) {
        Task task = taskGuard.loadInWorkspace(workspaceId, taskId);
        taskGuard.requireCanModify(workspaceId, userId, task);
        int position = req.position() != null ? req.position() : stepRepository.maxPositionByTaskId(taskId) + 1;
        if (req.position() != null && stepRepository.existsByTaskIdAndPosition(taskId, req.position())) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "步骤位置已存在");
        }
        TaskStep step = new TaskStep();
        step.setTaskId(taskId);
        step.setTitle(req.title().trim());
        step.setInstruction(req.instruction());
        step.setPosition(position);
        // #7：步骤经办人必须是该空间的有效成员。
        if (req.assigneeId() != null) {
            permissionService.requireActiveMember(workspaceId, req.assigneeId());
        }
        step.setAssigneeId(req.assigneeId());
        step.setStatus(req.status() != null ? req.status() : TaskStepStatus.TODO);
        step.setCompletionRuleJson(req.completionRuleJson() != null && !req.completionRuleJson().isBlank()
                ? req.completionRuleJson() : "{\"type\":\"SELF_CONFIRM\"}");
        step.setCreatedBy(userId);
        stepRepository.save(step);
        auditService.record(userId, workspaceId, AuditAction.STEP_CREATE, "step",
                step.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of("position", position));
        return StepView.of(step);
    }

    @Transactional
    public StepView update(UUID workspaceId, UUID taskId, UUID stepId, UUID userId, UpdateStepRequest req) {
        Task task = taskGuard.loadInWorkspace(workspaceId, taskId);
        taskGuard.requireCanModify(workspaceId, userId, task);
        TaskStep step = loadStep(taskId, stepId);
        checkVersion(step, req.baseVersion());
        if (req.title() != null) {
            step.setTitle(req.title().trim());
        }
        if (req.instruction() != null) {
            step.setInstruction(req.instruction());
        }
        if (req.position() != null && !req.position().equals(step.getPosition())) {
            if (stepRepository.existsByTaskIdAndPosition(taskId, req.position())) {
                throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "步骤位置已存在");
            }
            step.setPosition(req.position());
        }
        if (req.assigneeId() != null) {
            permissionService.requireActiveMember(workspaceId, req.assigneeId());
            step.setAssigneeId(req.assigneeId());
        }
        if (req.status() != null) {
            step.setStatus(req.status());
        }
        if (req.completionRuleJson() != null) {
            step.setCompletionRuleJson(req.completionRuleJson());
        }
        stepRepository.saveAndFlush(step);
        auditService.record(userId, workspaceId, AuditAction.STEP_UPDATE, "step",
                step.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of());
        return StepView.of(step);
    }

    @Transactional
    public void delete(UUID workspaceId, UUID taskId, UUID stepId, UUID userId) {
        Task task = taskGuard.loadInWorkspace(workspaceId, taskId);
        taskGuard.requireCanModify(workspaceId, userId, task);
        TaskStep step = loadStep(taskId, stepId);
        step.setDeletedAt(Instant.now());
        stepRepository.save(step);
        auditService.record(userId, workspaceId, AuditAction.STEP_DELETE, "step",
                step.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of());
    }

    private TaskStep loadStep(UUID taskId, UUID stepId) {
        return stepRepository.findByTaskIdAndId(taskId, stepId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "步骤不存在"));
    }

    private void checkVersion(TaskStep step, Integer baseVersion) {
        if (baseVersion == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少 baseVersion");
        }
        if (!baseVersion.equals(step.getVersion())) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "步骤已被他人修改，请刷新后重试");
        }
    }
}
