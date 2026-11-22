package com.actionloop.api.service;

import com.actionloop.api.ai.AiProperties;
import com.actionloop.api.domain.CandidateStatus;
import com.actionloop.api.domain.CandidateTask;
import com.actionloop.api.domain.Derivation;
import com.actionloop.api.domain.Material;
import com.actionloop.api.domain.MaterialState;
import com.actionloop.api.domain.ReminderChannel;
import com.actionloop.api.domain.Role;
import com.actionloop.api.domain.SourceItem;
import com.actionloop.api.domain.Task;
import com.actionloop.api.domain.TaskSource;
import com.actionloop.api.domain.TaskSourceRelation;
import com.actionloop.api.domain.TaskStatus;
import com.actionloop.api.domain.TaskStep;
import com.actionloop.api.domain.TaskStepStatus;
import com.actionloop.api.domain.TimePrecision;
import com.actionloop.api.repository.CandidateTaskRepository;
import com.actionloop.api.repository.EvidenceRefRepository;
import com.actionloop.api.repository.MaterialRepository;
import com.actionloop.api.repository.SourceItemRepository;
import com.actionloop.api.repository.TaskRepository;
import com.actionloop.api.repository.TaskSourceRepository;
import com.actionloop.api.repository.TaskStepRepository;
import com.actionloop.api.web.dto.CandidateView;
import com.actionloop.api.web.dto.DismissCandidateRequest;
import com.actionloop.api.web.dto.InboxConfirmRequest;
import com.actionloop.api.web.dto.InboxConfirmResponse;
import com.actionloop.api.web.dto.ReminderInput;
import com.actionloop.api.web.dto.ReplaceRemindersRequest;
import com.actionloop.shared.audit.AuditAction;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import com.actionloop.shared.web.CursorPage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 待确认收件箱（指导书 4.3 / 13.2）。
 * confirm 在单事务内创建事项、来源关系、步骤、材料与提醒；未确认候选永不产生提醒。
 * dismiss 不生成任何提醒，可恢复查看。
 */
@Service
@Transactional(noRollbackFor = BusinessException.class)
public class InboxService {

    private static final int MAX_LIMIT = 100;

    private final CandidateTaskRepository candidateRepository;
    private final SourceItemRepository sourceRepository;
    private final EvidenceRefRepository evidenceRefRepository;
    private final TaskRepository taskRepository;
    private final TaskStepRepository stepRepository;
    private final MaterialRepository materialRepository;
    private final TaskSourceRepository taskSourceRepository;
    private final ReminderService reminderService;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final EventService eventService;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    public InboxService(CandidateTaskRepository candidateRepository, SourceItemRepository sourceRepository,
                        EvidenceRefRepository evidenceRefRepository,
                        TaskRepository taskRepository, TaskStepRepository stepRepository,
                        MaterialRepository materialRepository, TaskSourceRepository taskSourceRepository,
                        ReminderService reminderService, PermissionService permissionService,
                        AuditService auditService, EventService eventService, AiProperties aiProperties,
                        ObjectMapper objectMapper) {
        this.candidateRepository = candidateRepository;
        this.sourceRepository = sourceRepository;
        this.evidenceRefRepository = evidenceRefRepository;
        this.taskRepository = taskRepository;
        this.stepRepository = stepRepository;
        this.materialRepository = materialRepository;
        this.taskSourceRepository = taskSourceRepository;
        this.reminderService = reminderService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.eventService = eventService;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    public CursorPage<CandidateView> list(UUID workspaceId, UUID userId, CandidateStatus status,
                                          String cursor, int limit) {
        permissionService.requireActiveMember(workspaceId, userId);
        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        int pageNo = parseCursor(cursor);
        Pageable pageable = PageRequest.of(pageNo, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<CandidateTask> page = status == null
                ? candidateRepository.findByWorkspaceId(workspaceId, pageable)
                : candidateRepository.findByWorkspaceIdAndStatus(workspaceId, status, pageable);
        List<CandidateView> items = page.getContent().stream().map(this::view).toList();
        boolean hasMore = page.hasNext();
        return CursorPage.of(items, hasMore ? String.valueOf(pageNo + 1) : null, hasMore);
    }

    public CandidateView get(UUID workspaceId, UUID candidateId, UUID userId) {
        permissionService.requireActiveMember(workspaceId, userId);
        CandidateTask candidate = load(workspaceId, candidateId);
        return view(candidate);
    }

    public InboxConfirmResponse confirm(UUID userId, UUID candidateId, InboxConfirmRequest req) {
        CandidateTask candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "候选不存在"));
        UUID workspaceId = candidate.getWorkspaceId();
        permissionService.requireAtLeastRole(workspaceId, userId, Role.MEMBER);
        if (candidate.getStatus() != CandidateStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "候选已处理，不能重复确认");
        }
        checkVersion(candidate, req.baseVersion());

        SourceItem source = sourceRepository.findByWorkspaceIdAndId(workspaceId, candidate.getSourceItemId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SOURCE_EXPIRED, "来源已删除，请重新导入"));

        String title = blank(req.title()) ? candidate.getTitle() : req.title().trim();
        Instant dueAt = req.dueAt() != null ? req.dueAt() : candidate.getDueAt();
        LocalDate dueDate = req.dueDate() != null ? req.dueDate() : candidate.getDueDate();
        String dueTimezone = blank(req.dueTimezone()) ? candidate.getDueTimezone() : req.dueTimezone();
        TimePrecision precision = req.timePrecision() != null ? req.timePrecision() : candidate.getTimePrecision();
        if (dueAt != null || dueDate != null) {
            double dueConfidence = fieldConfidence(candidate, "due");
            boolean multipleDates = String.valueOf(candidate.getReasonsJson()).contains("MULTIPLE_DATES");
            if ((dueConfidence < aiProperties.getMinConfidenceForReminder() || multipleDates)
                    && !Boolean.TRUE.equals(req.dueDateConfirmed())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "日期置信度不足或存在多个候选日期，请确认后再创建截止提醒",
                        Map.of("dueConfidence", dueConfidence, "multipleDates", multipleDates,
                                "field", "dueDateConfirmed"));
            }
        }
        if (req.assigneeId() != null) {
            permissionService.requireActiveMember(workspaceId, req.assigneeId());
        }

        Task task = new Task();
        task.setWorkspaceId(workspaceId);
        task.setTitle(title.length() > 200 ? title.substring(0, 200) : title);
        task.setStatus(TaskStatus.ACTIVE);
        task.setOwnerId(userId);
        task.setAssigneeId(req.assigneeId() != null ? req.assigneeId() : userId);
        task.setDueAt(dueAt);
        task.setDueDate(dueDate);
        task.setDueTimezone(dueTimezone);
        task.setTimePrecision(precision == null ? TimePrecision.NONE : precision);
        task.setPriority(0);
        task.setDescription(buildDescription(candidate, req.notes()));
        task.setDerivation(Derivation.USER_CONFIRMED_AI);
        task.setConfidence(candidate.getConfidence());
        task.setModelVersion(candidate.getModelVersion());
        task.setPromptVersion(candidate.getPromptVersion());
        task.setCreatedBy(userId);
        taskRepository.saveAndFlush(task);

        linkSource(task, source, TaskSourceRelation.ORIGIN, source.getContentPreview());
        if (dueAt != null || dueDate != null) {
            linkSource(task, source, TaskSourceRelation.DUE_DATE, quoteForDue(candidate, source));
        }
        List<String> materials = req.materials() != null ? req.materials() : parseMaterials(candidate);
        if (!materials.isEmpty()) {
            linkSource(task, source, TaskSourceRelation.MATERIAL, String.join("、", materials));
        }

        List<String> steps = req.steps() != null ? req.steps() : defaultSteps(candidate);
        int position = 0;
        for (String stepTitle : steps) {
            if (blank(stepTitle)) {
                continue;
            }
            TaskStep step = new TaskStep();
            step.setTaskId(task.getId());
            step.setTitle(stepTitle.trim().length() > 200 ? stepTitle.trim().substring(0, 200) : stepTitle.trim());
            step.setPosition(position++);
            step.setStatus(TaskStepStatus.TODO);
            step.setAssigneeId(task.getAssigneeId());
            step.setCreatedBy(userId);
            stepRepository.save(step);
        }

        int materialCount = 0;
        for (String name : materials) {
            if (blank(name)) {
                continue;
            }
            Material material = new Material();
            material.setTaskId(task.getId());
            material.setName(name.trim().length() > 120 ? name.trim().substring(0, 120) : name.trim());
            material.setState(MaterialState.MISSING);
            material.setCreatedBy(userId);
            materialRepository.save(material);
            materialCount++;
        }

        List<ReminderInput> reminders = req.reminders();
        if (reminders == null) {
            reminders = defaultReminders(candidate, task);
        }
        int reminderCount = 0;
        if (!reminders.isEmpty()) {
            reminderCount = reminderService.replace(workspaceId, task.getId(), userId,
                    new ReplaceRemindersRequest(reminders)).size();
        }

        candidate.setStatus(CandidateStatus.CONFIRMED);
        candidate.setConfirmedTaskId(task.getId());
        candidateRepository.save(candidate);

        eventService.emit(workspaceId, task.getId(), "TASK", "TASK_CREATED", task.getVersion(),
                Map.of("taskId", task.getId().toString(), "title", task.getTitle(),
                        "candidateId", candidate.getId().toString()));
        auditService.record(userId, workspaceId, AuditAction.CANDIDATE_CONFIRM, "candidate",
                candidate.getId().toString(), AuditAction.RESULT_SUCCESS,
                Map.of("taskId", task.getId().toString(), "reminders", reminderCount,
                        "steps", position, "materials", materialCount));
        List<UUID> evidenceRefIds = evidenceRefRepository.findBySourceItemId(source.getId()).stream()
                .map(com.actionloop.api.domain.EvidenceRef::getId).toList();
        return new InboxConfirmResponse(task.getId(), candidate.getId(), task.getVersion(), position,
                reminderCount, evidenceRefIds, task.getCreatedAt());
    }

    public CandidateView dismiss(UUID userId, UUID candidateId, DismissCandidateRequest req) {
        CandidateTask candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "候选不存在"));
        permissionService.requireAtLeastRole(candidate.getWorkspaceId(), userId, Role.MEMBER);
        if (candidate.getStatus() != CandidateStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "候选已处理");
        }
        checkVersion(candidate, req.baseVersion());
        candidate.setStatus(CandidateStatus.DISMISSED);
        candidate.setDismissedReason(blank(req.reason()) ? "用户忽略" : req.reason().trim());
        candidateRepository.save(candidate);
        auditService.record(userId, candidate.getWorkspaceId(), AuditAction.CANDIDATE_DISMISS, "candidate",
                candidateId.toString(), AuditAction.RESULT_SUCCESS, Map.of());
        return view(candidate);
    }

    private void linkSource(Task task, SourceItem source, TaskSourceRelation relation, String quote) {
        TaskSource link = new TaskSource();
        link.setTaskId(task.getId());
        link.setSourceItemId(source.getId());
        link.setRelationType(relation);
        link.setQuote(quote == null ? null : (quote.length() > 500 ? quote.substring(0, 500) : quote));
        link.setDerivation(Derivation.USER_CONFIRMED_AI);
        taskSourceRepository.save(link);
    }

    private List<ReminderInput> defaultReminders(CandidateTask candidate, Task task) {
        if (task.getDueAt() == null) {
            return List.of();
        }
        double confidence = candidate.getConfidence() == null ? 0 : candidate.getConfidence().doubleValue();
        if (confidence < aiProperties.getMinConfidenceForReminder()) {
            return List.of();
        }
        Instant trigger = task.getDueAt().minus(Duration.ofHours(1));
        Instant earliest = Instant.now().plus(Duration.ofMinutes(1));
        if (trigger.isBefore(earliest)) {
            trigger = earliest;
        }
        return List.of(new ReminderInput(trigger, ReminderChannel.IN_APP));
    }

    private List<String> defaultSteps(CandidateTask candidate) {
        if (!blank(candidate.getNextAction())) {
            return List.of(candidate.getNextAction());
        }
        return List.of();
    }

    private String buildDescription(CandidateTask candidate, String notes) {
        StringBuilder sb = new StringBuilder();
        if (!blank(candidate.getSummary())) {
            sb.append(candidate.getSummary());
        }
        if (!blank(candidate.getLocation())) {
            sb.append(sb.isEmpty() ? "" : "\n").append("地点：").append(candidate.getLocation());
        }
        if (!blank(notes)) {
            sb.append(sb.isEmpty() ? "" : "\n").append(notes.trim());
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private String quoteForDue(CandidateTask candidate, SourceItem source) {
        String reasons = candidate.getReasonsJson();
        if (reasons != null) {
            for (String part : reasons.split("\"")) {
                if (part.contains("DATE_OPTION")) {
                    continue;
                }
            }
        }
        return source.getContentPreview();
    }

    private List<String> parseMaterials(CandidateTask candidate) {
        try {
            return objectMapper.readValue(candidate.getMaterialsJson(), new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private double fieldConfidence(CandidateTask candidate, String field) {
        try {
            Map<String, Object> map = objectMapper.readValue(candidate.getFieldConfidenceJson(),
                    new TypeReference<Map<String, Object>>() {
                    });
            Object v = map.get(field);
            return v instanceof Number n ? n.doubleValue() : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private CandidateView view(CandidateTask candidate) {
        SourceItem source = sourceRepository.findByWorkspaceIdAndId(candidate.getWorkspaceId(),
                candidate.getSourceItemId()).orElse(null);
        return CandidateView.of(candidate,
                source == null ? null : source.getSourceApp(),
                source == null ? null : source.getContentPreview());
    }

    private CandidateTask load(UUID workspaceId, UUID candidateId) {
        return candidateRepository.findByWorkspaceIdAndId(workspaceId, candidateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "候选不存在"));
    }

    private void checkVersion(CandidateTask candidate, Integer baseVersion) {
        if (baseVersion == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少 baseVersion");
        }
        if (!baseVersion.equals(candidate.getVersion())) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "候选已被他人处理，请刷新");
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

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
