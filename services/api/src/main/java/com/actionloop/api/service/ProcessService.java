package com.actionloop.api.service;

import com.actionloop.api.domain.Derivation;
import com.actionloop.api.domain.EvidenceRef;
import com.actionloop.api.domain.ProcessReviewState;
import com.actionloop.api.domain.ProcessStep;
import com.actionloop.api.domain.ProcessStepSource;
import com.actionloop.api.domain.ProcessTemplate;
import com.actionloop.api.domain.ProcessVersion;
import com.actionloop.api.domain.Role;
import com.actionloop.api.domain.Task;
import com.actionloop.api.domain.TaskStatus;
import com.actionloop.api.domain.TaskStep;
import com.actionloop.api.domain.TaskStepStatus;
import com.actionloop.api.domain.TimePrecision;
import com.actionloop.api.repository.EvidenceRefRepository;
import com.actionloop.api.repository.ProcessStepRepository;
import com.actionloop.api.repository.ProcessStepSourceRepository;
import com.actionloop.api.repository.ProcessTemplateRepository;
import com.actionloop.api.repository.ProcessVersionRepository;
import com.actionloop.api.repository.TaskRepository;
import com.actionloop.api.repository.TaskStepRepository;
import com.actionloop.api.web.dto.CreateProcessRequest;
import com.actionloop.api.web.dto.CreateProcessVersionRequest;
import com.actionloop.api.web.dto.InstantiateProcessRequest;
import com.actionloop.api.web.dto.ProcessStepInput;
import com.actionloop.api.web.dto.ProcessStepView;
import com.actionloop.api.web.dto.ProcessTemplateView;
import com.actionloop.api.web.dto.ProcessVersionView;
import com.actionloop.api.web.dto.PublishProcessVersionRequest;
import com.actionloop.api.web.dto.ReplaceProcessStepsRequest;
import com.actionloop.api.web.dto.TaskView;
import com.actionloop.shared.audit.AuditAction;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import com.actionloop.shared.web.CursorPage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 办事流程模板与版本（指导书 6.x / WP06）。
 * 发布规则：所有必填步骤必须有有效证据引用；VERIFIED 要求复核人 != 创建人；
 * 否则只能以 PERSONAL_UNVERIFIED 发布并在客户端显著标注“非官方”。
 */
@Service
@Transactional
public class ProcessService {

    private static final int MAX_LIMIT = 100;

    private final ProcessTemplateRepository templateRepository;
    private final ProcessVersionRepository versionRepository;
    private final ProcessStepRepository stepRepository;
    private final ProcessStepSourceRepository stepSourceRepository;
    private final EvidenceRefRepository evidenceRefRepository;
    private final TaskRepository taskRepository;
    private final TaskStepRepository taskStepRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final EventService eventService;

    public ProcessService(ProcessTemplateRepository templateRepository, ProcessVersionRepository versionRepository,
                          ProcessStepRepository stepRepository, ProcessStepSourceRepository stepSourceRepository,
                          EvidenceRefRepository evidenceRefRepository, TaskRepository taskRepository,
                          TaskStepRepository taskStepRepository, PermissionService permissionService,
                          AuditService auditService, EventService eventService) {
        this.templateRepository = templateRepository;
        this.versionRepository = versionRepository;
        this.stepRepository = stepRepository;
        this.stepSourceRepository = stepSourceRepository;
        this.evidenceRefRepository = evidenceRefRepository;
        this.taskRepository = taskRepository;
        this.taskStepRepository = taskStepRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.eventService = eventService;
    }

    public CursorPage<ProcessTemplateView> list(UUID workspaceId, UUID userId, String cursor, int limit) {
        permissionService.requireActiveMember(workspaceId, userId);
        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        int pageNo = parseCursor(cursor);
        Pageable pageable = PageRequest.of(pageNo, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ProcessTemplate> page = templateRepository.findByOwnerWorkspaceId(workspaceId, pageable);
        List<UUID> templateIds = page.getContent().stream().map(ProcessTemplate::getId).toList();
        Map<UUID, List<ProcessVersionView>> versionsByTemplate = templateIds.isEmpty() ? Map.of()
                : versionRepository.findByTemplateIdInOrderByVersionNoDesc(templateIds).stream()
                        .collect(Collectors.groupingBy(ProcessVersion::getTemplateId,
                                Collectors.mapping(v -> ProcessVersionView.of(v, List.of()), Collectors.toList())));
        List<ProcessTemplateView> items = page.getContent().stream()
                .map(t -> ProcessTemplateView.of(t, versionsByTemplate.getOrDefault(t.getId(), List.of())))
                .toList();
        return CursorPage.of(items, page.hasNext() ? String.valueOf(pageNo + 1) : null, page.hasNext());
    }

    public ProcessTemplateView get(UUID workspaceId, UUID templateId, UUID userId) {
        permissionService.requireActiveMember(workspaceId, userId);
        ProcessTemplate template = loadTemplate(workspaceId, templateId);
        List<ProcessVersionView> versions = versionRepository.findByTemplateIdOrderByVersionNoDesc(templateId)
                .stream().map(v -> ProcessVersionView.of(v, stepViews(v.getId()))).toList();
        return ProcessTemplateView.of(template, versions);
    }

    @Transactional
    public ProcessTemplateView create(UUID userId, CreateProcessRequest req) {
        permissionService.requireAtLeastRole(req.workspaceId(), userId, Role.MEMBER);
        ProcessTemplate template = new ProcessTemplate();
        template.setOwnerWorkspaceId(req.workspaceId());
        template.setTitle(req.title().trim());
        template.setRegionCode(req.regionCode());
        template.setScopeJson(req.scopeJson());
        template.setCreatedBy(userId);
        templateRepository.saveAndFlush(template);
        auditService.record(userId, req.workspaceId(), AuditAction.PROCESS_CREATE, "process_template",
                template.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of());
        eventService.emit(req.workspaceId(), template.getId(), "PROCESS", "PROCESS_CREATED",
                template.getVersion(), Map.of("templateId", template.getId().toString()));
        return ProcessTemplateView.of(template, List.of());
    }

    @Transactional
    public ProcessVersionView createVersion(UUID userId, UUID templateId, CreateProcessVersionRequest req) {
        ProcessTemplate template = templateRepository.findWithLockById(templateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "流程模板不存在"));
        permissionService.requireAtLeastRole(template.getOwnerWorkspaceId(), userId, Role.MEMBER);
        int nextNo = versionRepository.findFirstByTemplateIdOrderByVersionNoDesc(templateId)
                .map(v -> v.getVersionNo() + 1).orElse(1);
        ProcessVersion version = new ProcessVersion();
        version.setTemplateId(templateId);
        version.setVersionNo(nextNo);
        version.setReviewState(ProcessReviewState.DRAFT);
        version.setCreatedBy(userId);
        versionRepository.saveAndFlush(version);
        if (req.steps() != null && !req.steps().isEmpty()) {
            writeSteps(version, template.getOwnerWorkspaceId(), req.steps());
        }
        auditService.record(userId, template.getOwnerWorkspaceId(), AuditAction.PROCESS_VERSION_CREATE,
                "process_version", version.getId().toString(), AuditAction.RESULT_SUCCESS,
                Map.of("versionNo", nextNo));
        return ProcessVersionView.of(version, stepViews(version.getId()));
    }

    @Transactional
    public ProcessVersionView replaceSteps(UUID userId, UUID versionId, ReplaceProcessStepsRequest req) {
        ProcessVersion version = loadVersion(versionId);
        ProcessTemplate template = templateRepository.findById(version.getTemplateId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "流程模板不存在"));
        permissionService.requireAtLeastRole(template.getOwnerWorkspaceId(), userId, Role.MEMBER);
        requireDraft(version);
        List<ProcessStep> existing = stepRepository.findByVersionIdOrderByPositionAsc(versionId);
        existing.forEach(s -> stepSourceRepository.deleteByProcessStepId(s.getId()));
        stepRepository.deleteByVersionId(versionId);
        writeSteps(version, template.getOwnerWorkspaceId(), req.steps());
        return ProcessVersionView.of(version, stepViews(versionId));
    }

    @Transactional
    public ProcessVersionView publish(UUID userId, UUID versionId, PublishProcessVersionRequest req) {
        ProcessVersion version = loadVersion(versionId);
        ProcessTemplate template = templateRepository.findById(version.getTemplateId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "流程模板不存在"));
        UUID workspaceId = template.getOwnerWorkspaceId();
        permissionService.requireAtLeastRole(workspaceId, userId, Role.MEMBER);
        requireDraft(version);
        List<ProcessStep> steps = stepRepository.findByVersionIdOrderByPositionAsc(versionId);
        if (steps.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "流程至少需要一个步骤");
        }
        List<String> missing = new ArrayList<>();
        StringBuilder snapshot = new StringBuilder();
        for (ProcessStep step : steps) {
            List<ProcessStepSource> sources = stepSourceRepository.findByProcessStepId(step.getId());
            // 证据可能已被软删：发布时以当前有效证据为准，避免“已撤回来源”仍支撑官方结论
            List<ProcessStepSource> validSources = sources.stream()
                    .filter(s -> evidenceRefRepository.findById(s.getEvidenceRefId()).isPresent())
                    .toList();
            if (Boolean.TRUE.equals(step.getRequired()) && validSources.isEmpty()) {
                missing.add("第 " + (step.getPosition() + 1) + " 步缺少权威来源");
            }
            validSources.stream().map(ProcessStepSource::getEvidenceRefId).sorted()
                    .forEach(id -> snapshot.append(id));
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.SOURCE_CLAIM_UNVERIFIED,
                    "必填步骤缺少有效来源，不能发布为已核验流程", Map.of("missing", missing));
        }
        UUID reviewerId = req.reviewerId() == null ? userId : req.reviewerId();
        boolean differentReviewer = !reviewerId.equals(version.getCreatedBy());
        if (differentReviewer) {
            // 复核必须由复核人本人发起：禁止在请求体里填他人 UUID 冒充已复核
            if (!reviewerId.equals(userId)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "复核必须由复核人本人发起");
            }
            permissionService.requireAtLeastRole(workspaceId, reviewerId, Role.MEMBER);
            version.setReviewerId(reviewerId);
            version.setReviewState(ProcessReviewState.VERIFIED);
        } else if (Boolean.TRUE.equals(req.allowPersonalUnverified())) {
            version.setReviewState(ProcessReviewState.PERSONAL_UNVERIFIED);
        } else {
            throw new BusinessException(ErrorCode.SOURCE_CLAIM_UNVERIFIED,
                    "需要与创建人不同的复核人才能标记为官方核验；个人使用请显式选择“个人经验（非官方）”");
        }
        version.setPublishedBy(userId);
        version.setPublishedAt(Instant.now());
        version.setSourceSnapshotHash(sha256(snapshot.toString()));
        versionRepository.save(version);
        template.setCurrentVersionId(version.getId());
        templateRepository.save(template);
        auditService.record(userId, workspaceId, AuditAction.PROCESS_VERSION_PUBLISH, "process_version",
                versionId.toString(), AuditAction.RESULT_SUCCESS,
                Map.of("state", version.getReviewState().name(), "reviewerId", reviewerId.toString()));
        eventService.emit(workspaceId, versionId, "PROCESS_VERSION", "PROCESS_VERSION_PUBLISHED",
                version.getVersion(), Map.of("templateId", template.getId().toString(),
                        "reviewState", version.getReviewState().name()));
        return ProcessVersionView.of(version, stepViews(versionId));
    }

    @Transactional
    public TaskView instantiate(UUID userId, UUID versionId, InstantiateProcessRequest req) {
        ProcessVersion version = loadVersion(versionId);
        ProcessTemplate template = templateRepository.findById(version.getTemplateId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "流程模板不存在"));
        permissionService.requireAtLeastRole(req.workspaceId(), userId, Role.MEMBER);
        if (!req.workspaceId().equals(template.getOwnerWorkspaceId())) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "不能在非模板所属空间使用该流程");
        }
        if (version.getReviewState() == ProcessReviewState.DRAFT
                || version.getReviewState() == ProcessReviewState.IN_REVIEW) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "流程尚未发布，不能使用");
        }
        if (version.getValidTo() != null && version.getValidTo().isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.SOURCE_EXPIRED, "流程版本已过期，请使用最新版本");
        }
        if (req.assigneeId() != null) {
            permissionService.requireActiveMember(req.workspaceId(), req.assigneeId());
        }
        Task task = new Task();
        task.setWorkspaceId(req.workspaceId());
        task.setTitle(req.title() == null || req.title().isBlank()
                ? template.getTitle() : req.title().trim());
        task.setStatus(TaskStatus.ACTIVE);
        task.setOwnerId(userId);
        task.setAssigneeId(req.assigneeId() != null ? req.assigneeId() : userId);
        task.setDueAt(req.dueAt());
        task.setDueDate(req.dueDate());
        task.setDueTimezone(req.dueTimezone());
        task.setTimePrecision(req.timePrecision() == null ? TimePrecision.NONE : req.timePrecision());
        task.setDerivation(version.getReviewState() == ProcessReviewState.VERIFIED
                ? Derivation.OFFICIAL : Derivation.USER);
        task.setCreatedBy(userId);
        taskRepository.saveAndFlush(task);

        List<ProcessStep> steps = stepRepository.findByVersionIdOrderByPositionAsc(versionId).stream()
                .sorted(Comparator.comparing(ProcessStep::getPosition)).toList();
        int position = 0;
        for (ProcessStep step : steps) {
            TaskStep taskStep = new TaskStep();
            taskStep.setTaskId(task.getId());
            taskStep.setProcessStepId(step.getId());
            taskStep.setTitle(step.getInstruction());
            taskStep.setPosition(position++);
            taskStep.setStatus(TaskStepStatus.TODO);
            taskStep.setAssigneeId(task.getAssigneeId());
            taskStep.setCreatedBy(userId);
            taskStepRepository.save(taskStep);
        }
        auditService.record(userId, req.workspaceId(), AuditAction.PROCESS_INSTANTIATE, "task",
                task.getId().toString(), AuditAction.RESULT_SUCCESS,
                Map.of("versionId", versionId.toString(), "steps", position));
        eventService.emit(req.workspaceId(), task.getId(), "TASK", "TASK_CREATED", task.getVersion(),
                Map.of("taskId", task.getId().toString(), "fromProcessVersion", versionId.toString()));
        return TaskView.of(task);
    }

    private void writeSteps(ProcessVersion version, UUID workspaceId, List<ProcessStepInput> inputs) {
        int position = 0;
        for (ProcessStepInput input : inputs) {
            ProcessStep step = new ProcessStep();
            step.setVersionId(version.getId());
            step.setPosition(position++);
            step.setInstruction(input.instruction().trim());
            step.setRequired(input.required() == null || input.required());
            step.setOfficialEntryUrl(input.officialEntryUrl());
            stepRepository.saveAndFlush(step);
            if (input.sourceRefIds() != null) {
                for (UUID refId : input.sourceRefIds()) {
                    EvidenceRef ref = evidenceRefRepository.findByIdAndWorkspaceId(refId, workspaceId)
                            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                                    "证据引用不存在或不属于该空间"));
                    ProcessStepSource link = new ProcessStepSource();
                    link.setProcessStepId(step.getId());
                    link.setEvidenceRefId(ref.getId());
                    link.setClaimType("STEP");
                    stepSourceRepository.save(link);
                }
            }
        }
    }

    private List<ProcessStepView> stepViews(UUID versionId) {
        return stepRepository.findByVersionIdOrderByPositionAsc(versionId).stream()
                .map(step -> ProcessStepView.of(step,
                        stepSourceRepository.findByProcessStepId(step.getId()).stream()
                                .map(ProcessStepSource::getEvidenceRefId).toList()))
                .toList();
    }

    private ProcessTemplate loadTemplate(UUID workspaceId, UUID templateId) {
        return templateRepository.findByIdAndOwnerWorkspaceId(templateId, workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "流程模板不存在"));
    }

    private ProcessVersion loadVersion(UUID versionId) {
        return versionRepository.findById(versionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "流程版本不存在"));
    }

    private void requireDraft(ProcessVersion version) {
        if (version.getReviewState() != ProcessReviewState.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "已发布版本不可修改");
        }
    }

    private static int parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "游标格式错误");
        }
    }

    private static String sha256(String text) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

