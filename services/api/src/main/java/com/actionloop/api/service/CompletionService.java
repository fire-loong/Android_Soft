package com.actionloop.api.service;

import com.actionloop.api.domain.Completion;
import com.actionloop.api.domain.CompletionAsset;
import com.actionloop.api.domain.ReviewState;
import com.actionloop.api.domain.Role;
import com.actionloop.api.domain.Task;
import com.actionloop.api.domain.TaskStep;
import com.actionloop.api.domain.TaskStepStatus;
import com.actionloop.api.repository.CompletionAssetRepository;
import com.actionloop.api.repository.CompletionRepository;
import com.actionloop.api.repository.EvidenceAssetRepository;
import com.actionloop.api.repository.TaskRepository;
import com.actionloop.api.repository.TaskStepRepository;
import com.actionloop.api.web.dto.CompletionView;
import com.actionloop.api.web.dto.PendingCompletionView;
import com.actionloop.api.web.dto.ReviewCompletionRequest;
import com.actionloop.api.web.dto.SubmitCompletionRequest;
import com.actionloop.shared.audit.AuditAction;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 步骤完成凭证与核验（指导书 13.2 / 3.1 Completion）。
 * - submit：追加 Completion，不改旧记录；步骤进入 PENDING_REVIEW。
 * - review：仅授权核验人（事项负责人/经办人/ADMIN+）；通过→DONE，驳回→IN_PROGRESS 且必须填写理由。
 */
@Service
public class CompletionService {

    private final CompletionRepository completionRepository;
    private final CompletionAssetRepository completionAssetRepository;
    private final TaskStepRepository stepRepository;
    private final TaskRepository taskRepository;
    private final EvidenceAssetRepository assetRepository;
    private final TaskGuard taskGuard;
    private final PermissionService permissionService;
    private final AuditService auditService;

    public CompletionService(CompletionRepository completionRepository,
                             CompletionAssetRepository completionAssetRepository,
                             TaskStepRepository stepRepository, TaskRepository taskRepository,
                             EvidenceAssetRepository assetRepository, TaskGuard taskGuard,
                             PermissionService permissionService, AuditService auditService) {
        this.completionRepository = completionRepository;
        this.completionAssetRepository = completionAssetRepository;
        this.stepRepository = stepRepository;
        this.taskRepository = taskRepository;
        this.assetRepository = assetRepository;
        this.taskGuard = taskGuard;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    public List<CompletionView> list(UUID stepId, UUID userId) {
        TaskStep step = loadStep(stepId);
        Task task = loadTask(step.getTaskId());
        taskGuard.loadForRead(task.getWorkspaceId(), task.getId(), userId);
        return completionRepository.findByTaskStepIdOrderBySubmittedAtDesc(stepId).stream()
                .map(c -> CompletionView.of(c, assetIdsOf(c.getId()))).toList();
    }

    /**
     * 跨事项待核验队列（指导书 9.2 协同空间）。
     * 只返回当前用户有权核验的事项（负责人/经办人/ADMIN+），避免暴露无权限内容。
     */
    public List<PendingCompletionView> pendingForReview(UUID workspaceId, UUID userId) {
        permissionService.requireActiveMember(workspaceId, userId);
        boolean admin = permissionService.hasAtLeastRole(workspaceId, userId, Role.ADMIN);
        List<Completion> pending = completionRepository.findPendingForWorkspace(
                workspaceId, PageRequest.of(0, 200));
        if (pending.isEmpty()) {
            return List.of();
        }
        Map<UUID, TaskStep> steps = stepRepository.findAllById(
                pending.stream().map(Completion::getTaskStepId).distinct().toList())
                .stream().collect(Collectors.toMap(TaskStep::getId, s -> s));
        Map<UUID, Task> tasks = taskRepository.findAllById(
                steps.values().stream().map(TaskStep::getTaskId).distinct().toList())
                .stream().collect(Collectors.toMap(Task::getId, t -> t));
        List<PendingCompletionView> result = new ArrayList<>();
        for (Completion c : pending) {
            TaskStep step = steps.get(c.getTaskStepId());
            if (step == null) continue;
            Task task = tasks.get(step.getTaskId());
            if (task == null) continue;
            boolean involved = task.getOwnerId().equals(userId)
                    || (task.getAssigneeId() != null && task.getAssigneeId().equals(userId));
            if (!admin && !involved) {
                continue;
            }
            result.add(new PendingCompletionView(c.getId(), task.getId(), task.getTitle(),
                    step.getId(), step.getTitle(), c.getSubmitterId(), c.getStatement(),
                    c.getSubmittedAt(), assetIdsOf(c.getId())));
            if (result.size() >= 100) {
                break;
            }
        }
        return result;
    }

    @Transactional
    public CompletionView submit(UUID stepId, UUID userId, SubmitCompletionRequest req) {
        TaskStep step = loadStep(stepId);
        Task task = loadTask(step.getTaskId());
        if (step.getStatus() == TaskStepStatus.DONE || step.getStatus() == TaskStepStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "步骤已完成或已取消，不能提交凭证");
        }
        if (step.getStatus() == TaskStepStatus.PENDING_REVIEW) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "该步骤已有待核验凭证");
        }
        // #13：完成凭证提交至少需 MEMBER，且仅限步骤经办人或事项可修改方；VIEWER 不可提交。
        permissionService.requireAtLeastRole(task.getWorkspaceId(), userId, Role.MEMBER);
        boolean stepAssignee = step.getAssigneeId() != null && step.getAssigneeId().equals(userId);
        if (!stepAssignee) {
            taskGuard.requireCanModify(task.getWorkspaceId(), userId, task);
        }
        List<UUID> assetIds = req.assetIds() != null ? req.assetIds() : List.of();
        for (UUID assetId : assetIds) {
            if (assetRepository.findByWorkspaceIdAndId(task.getWorkspaceId(), assetId).isEmpty()) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "凭证中的证据资产不存在");
            }
        }
        Completion c = new Completion();
        c.setTaskStepId(stepId);
        c.setSubmitterId(userId);
        c.setStatement(req.statement());
        c.setReviewState(ReviewState.PENDING);
        c.setSubmittedAt(Instant.now());
        completionRepository.save(c);
        List<CompletionAsset> links = new ArrayList<>();
        for (UUID assetId : assetIds) {
            links.add(new CompletionAsset(c.getId(), assetId));
        }
        if (!links.isEmpty()) {
            completionAssetRepository.saveAll(links);
        }
        step.setStatus(TaskStepStatus.PENDING_REVIEW);
        stepRepository.saveAndFlush(step);
        auditService.record(userId, task.getWorkspaceId(), AuditAction.COMPLETION_SUBMIT, "completion",
                c.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of("step", stepId.toString()));
        return CompletionView.of(c, assetIds);
    }

    @Transactional
    public CompletionView review(UUID completionId, UUID userId, ReviewCompletionRequest req) {
        Completion c = completionRepository.findById(completionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "完成凭证不存在"));
        TaskStep step = loadStep(c.getTaskStepId());
        Task task = loadTask(step.getTaskId());
        taskGuard.requireCanModify(task.getWorkspaceId(), userId, task);
        if (c.getReviewState() != ReviewState.PENDING) {
            // 防重复核验：已通过/已驳回的凭证不能被再次改写，避免把 DONE 步骤回退
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "该凭证已核验，不能重复处理");
        }
        if (step.getStatus() != TaskStepStatus.PENDING_REVIEW) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "步骤当前不处于待核验状态");
        }
        if (!req.approve() && (req.reason() == null || req.reason().isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "驳回必须填写理由");
        }
        c.setReviewState(req.approve() ? ReviewState.APPROVED : ReviewState.REJECTED);
        c.setReviewerId(userId);
        c.setReviewReason(req.reason());
        c.setReviewedAt(Instant.now());
        completionRepository.save(c);
        step.setStatus(req.approve() ? TaskStepStatus.DONE : TaskStepStatus.IN_PROGRESS);
        stepRepository.saveAndFlush(step);
        auditService.record(userId, task.getWorkspaceId(), AuditAction.COMPLETION_REVIEW, "completion",
                c.getId().toString(), AuditAction.RESULT_SUCCESS,
                Map.of("approve", req.approve().toString()));
        return CompletionView.of(c, assetIdsOf(c.getId()));
    }

    private TaskStep loadStep(UUID stepId) {
        return stepRepository.findById(stepId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "步骤不存在"));
    }

    private Task loadTask(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "事项不存在"));
    }

    private List<UUID> assetIdsOf(UUID completionId) {
        return completionAssetRepository.findByCompletionId(completionId).stream()
                .map(CompletionAsset::getAssetId).toList();
    }
}
