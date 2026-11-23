package com.actionloop.api.service;

import com.actionloop.api.ai.CandidateExtraction;
import com.actionloop.api.ai.CandidateExtractionService;
import com.actionloop.api.ai.AiResultCacheService;
import com.actionloop.api.ai.ExtractionRequest;
import com.actionloop.api.ai.RuleExtractor;
import com.actionloop.api.ai.SensitiveContentDetector;
import com.actionloop.api.domain.CandidateStatus;
import com.actionloop.api.domain.CandidateTask;
import com.actionloop.api.domain.Derivation;
import com.actionloop.api.domain.EvidenceRef;
import com.actionloop.api.domain.PrivacyLevel;
import com.actionloop.api.domain.Role;
import com.actionloop.api.domain.SourceItem;
import com.actionloop.api.domain.SourceState;
import com.actionloop.api.domain.SourceType;
import com.actionloop.api.domain.TimePrecision;
import com.actionloop.api.repository.CandidateTaskRepository;
import com.actionloop.api.repository.EvidenceAssetRepository;
import com.actionloop.api.repository.EvidenceRefRepository;
import com.actionloop.api.repository.SourceItemRepository;
import com.actionloop.api.web.dto.EvidenceRefView;
import com.actionloop.api.web.dto.SourceRecheckResponse;
import com.actionloop.api.web.dto.SourceShareRequest;
import com.actionloop.api.web.dto.SourceView;
import com.actionloop.shared.audit.AuditAction;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.slf4j.MDC;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 来源采集与候选生成（指导书 4.x / 13.2）。
 * - 通知/分享/截图/文件统一落 SourceItem + EvidenceRef，可回溯原文。
 * - 候选只进收件箱，未确认不创建提醒。
 * - 敏感内容（验证码/支付/密码）默认不送云端、不生成候选。
 */
@Service
@Transactional
public class SourceService {

    private static final int MAX_LIST = 100;

    private final SourceItemRepository sourceRepository;
    private final EvidenceRefRepository evidenceRefRepository;
    private final EvidenceAssetRepository assetRepository;
    private final CandidateTaskRepository candidateRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final CandidateExtractionService extractionService;
    private final AiResultCacheService aiCacheService;
    private final RuleExtractor ruleExtractor;
    private final SensitiveContentDetector sensitiveDetector;
    private final SearchIndexService searchIndexService;
    private final SourceFetchService sourceFetchService;
    private final EventService eventService;
    private final JobService jobService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SourceService(SourceItemRepository sourceRepository, EvidenceRefRepository evidenceRefRepository,
                         EvidenceAssetRepository assetRepository, CandidateTaskRepository candidateRepository,
                         PermissionService permissionService, AuditService auditService,
                         CandidateExtractionService extractionService, AiResultCacheService aiCacheService,
                         RuleExtractor ruleExtractor, SensitiveContentDetector sensitiveDetector,
                         SearchIndexService searchIndexService,
                         SourceFetchService sourceFetchService, EventService eventService,
                         JobService jobService, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.sourceRepository = sourceRepository;
        this.evidenceRefRepository = evidenceRefRepository;
        this.assetRepository = assetRepository;
        this.candidateRepository = candidateRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.extractionService = extractionService;
        this.aiCacheService = aiCacheService;
        this.ruleExtractor = ruleExtractor;
        this.sensitiveDetector = sensitiveDetector;
        this.searchIndexService = searchIndexService;
        this.sourceFetchService = sourceFetchService;
        this.eventService = eventService;
        this.jobService = jobService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public SourceView share(UUID userId, SourceShareRequest req) {
        permissionService.requireAtLeastRole(req.workspaceId(), userId, Role.MEMBER);
        String text = RuleExtractor.normalize(req.text());
        if (text.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "来源内容不能为空");
        }
        if (req.assetId() != null) {
            assetRepository.findByWorkspaceIdAndId(req.workspaceId(), req.assetId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "证据文件不存在"));
        }
        String hash = sha256(text);
        Instant receivedAt = req.receivedAt() != null ? req.receivedAt() : Instant.now();

        SourceItem existing = sourceRepository.findFingerprint(req.workspaceId(), req.sourceApp(),
                req.sourceKey(), hash).orElse(null);
        if (existing != null) {
            auditService.record(userId, req.workspaceId(), AuditAction.SOURCE_SHARE, "source",
                    existing.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of("dedup", true));
            return view(existing, userId);
        }

        SensitiveContentDetector.Detection sensitive = sensitiveDetector.detect(text);
        PrivacyLevel privacy = sensitive.sensitive() ? PrivacyLevel.S3
                : (req.privacyLevel() != null ? req.privacyLevel() : PrivacyLevel.S2);

        // 同一 App + 来源 key 的更新通知复用原来源，避免刷屏（指导书 4.5 同一通知多次更新）
        SourceItem source = req.sourceKey() == null ? null
                : sourceRepository.findFirstByWorkspaceIdAndSourceAppAndSourceKeyOrderByReceivedAtDesc(
                        req.workspaceId(), req.sourceApp(), req.sourceKey()).orElse(null);
        boolean isUpdate = source != null;
        if (!isUpdate) {
            source = new SourceItem();
            source.setWorkspaceId(req.workspaceId());
            source.setSourceType(req.sourceType());
            source.setSourceApp(trimTo(req.sourceApp(), 128));
            source.setSourceKey(trimTo(req.sourceKey(), 256));
            source.setCreatedBy(userId);
        }
        source.setSourceTitle(trimTo(req.title(), 256));
        source.setLinkUrl(trimTo(req.linkUrl(), 2048));
        source.setReceivedAt(receivedAt);
        source.setContentPreview(text.length() > 2000 ? text.substring(0, 2000) : text);
        source.setContentHash(hash);
        source.setPrivacyLevel(privacy);
        source.setSourceState(SourceState.USER_PROVIDED);
        sourceRepository.save(source);

        EvidenceRef ref = new EvidenceRef();
        ref.setWorkspaceId(req.workspaceId());
        ref.setSourceItemId(source.getId());
        ref.setAssetId(req.assetId());
        ref.setPageNo(parsePage(req.pageRef()));
        ref.setBboxJson(req.bboxJson());
        ref.setCharStart(0);
        ref.setCharEnd(Math.min(text.length(), 2000));
        ref.setQuoteText(text.length() > 2000 ? text.substring(0, 2000) : text);
        ref.setContentHash(hash);
        ref.setCreatedBy(userId);
        evidenceRefRepository.save(ref);

        boolean personalOnly = privacy == PrivacyLevel.S3;
        searchIndexService.index(req.workspaceId(), source.getId(), req.assetId(),
                req.title() != null ? req.title() : firstLine(text), text,
                req.sourceType().name(), userId, personalOnly);

        List<CandidateTask> created = new ArrayList<>();
        if (!sensitive.sensitive() && Boolean.TRUE.equals(req.localOnly())) {
            created = generateCandidates(source, text, userId);
        } else if (!sensitive.sensitive()) {
            enqueueExtraction(source, text, req.timezone(), userId);
        }
        eventService.emit(req.workspaceId(), source.getId(), "SOURCE", isUpdate ? "SOURCE_UPDATED" : "SOURCE_CREATED",
                source.getVersion(), Map.of("sourceId", source.getId().toString(),
                        "sourceType", source.getSourceType().name(),
                        "candidateCount", created.size()));
        auditService.record(userId, req.workspaceId(), AuditAction.SOURCE_SHARE, "source",
                source.getId().toString(), AuditAction.RESULT_SUCCESS,
                Map.of("type", req.sourceType().name(), "sensitive", sensitive.sensitive(),
                        "candidates", created.size()));
        return view(source, userId);
    }

    public SourceView get(UUID workspaceId, UUID sourceId, UUID userId) {
        permissionService.requireActiveMember(workspaceId, userId);
        SourceItem source = load(workspaceId, sourceId);
        return view(source, userId);
    }

    public Page<SourceView> list(UUID workspaceId, UUID userId, int page, int size) {
        permissionService.requireActiveMember(workspaceId, userId);
        int bounded = Math.min(Math.max(size, 1), MAX_LIST);
        Pageable pageable = PageRequest.of(Math.max(page, 0), bounded,
                Sort.by(Sort.Direction.DESC, "receivedAt"));
        return sourceRepository.findByWorkspaceId(workspaceId, pageable)
                .map(s -> SourceView.of(s, evidenceRefs(s.getId()), candidateIds(s.getId())));
    }

    public void delete(UUID workspaceId, UUID sourceId, UUID userId, Integer baseVersion) {
        permissionService.requireAtLeastRole(workspaceId, userId, Role.MEMBER);
        SourceItem source = load(workspaceId, sourceId);
        if (baseVersion == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少 baseVersion");
        }
        if (!source.getVersion().equals(baseVersion)) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "来源已被修改，请刷新后重试");
        }
        long linked = candidateRepository.countBySourceItemId(sourceId);
        jobService.invalidateSource(workspaceId, sourceId);
        aiCacheService.deleteForSource(workspaceId, sourceId);
        source.setDeletedAt(Instant.now());
        sourceRepository.save(source);
        evidenceRefRepository.findBySourceItemId(sourceId).forEach(r -> {
            r.setDeletedAt(Instant.now());
            evidenceRefRepository.save(r);
        });
        auditService.record(userId, workspaceId, AuditAction.SOURCE_DELETE, "source",
                sourceId.toString(), AuditAction.RESULT_SUCCESS, Map.of("candidates", linked));
        eventService.emit(workspaceId, sourceId, "SOURCE", "SOURCE_DELETED", source.getVersion(),
                Map.of("sourceId", sourceId.toString()));
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public SourceRecheckResponse recheck(UUID workspaceId, UUID sourceId, UUID userId) {
        permissionService.requireAtLeastRole(workspaceId, userId, Role.MEMBER);
        SourceItem source = load(workspaceId, sourceId);
        if (source.getLinkUrl() == null || source.getLinkUrl().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "该来源没有可复查的链接");
        }
        String previous = source.getContentHash();
        SourceFetchService.Fetched fetched;
        try {
            fetched = sourceFetchService.fetch(source.getLinkUrl());
        } catch (SourceFetchService.BlockedException blocked) {
            source.setSourceState(SourceState.FETCH_FAILED);
            source.setLastCheckedAt(Instant.now());
            sourceRepository.save(source);
            auditService.record(userId, workspaceId, AuditAction.SOURCE_FETCH_BLOCKED, "source",
                    sourceId.toString(), AuditAction.RESULT_DENIED,
                    Map.of("reason", blocked.reason(),
                            "detail", blocked.detail() == null ? "" : blocked.detail()));
            throw blocked;
        } catch (BusinessException be) {
            source.setSourceState(SourceState.FETCH_FAILED);
            source.setLastCheckedAt(Instant.now());
            sourceRepository.save(source);
            auditService.record(userId, workspaceId, AuditAction.SOURCE_RECHECK, "source",
                    sourceId.toString(), AuditAction.RESULT_FAILURE, Map.of("status", be.code().name()));
            throw be;
        }
        boolean changed = !fetched.contentHash().equalsIgnoreCase(previous);
        source.setLastCheckedAt(Instant.now());
        if (changed) {
            source.setContentHash(fetched.contentHash());
            source.setContentPreview(fetched.text().length() > 2000
                    ? fetched.text().substring(0, 2000) : fetched.text());
            source.setSourceState(SourceState.USER_PROVIDED);
            sourceRepository.save(source);
            EvidenceRef ref = new EvidenceRef();
            ref.setWorkspaceId(workspaceId);
            ref.setSourceItemId(sourceId);
            ref.setQuoteText(source.getContentPreview());
            ref.setContentHash(fetched.contentHash());
            ref.setCreatedBy(userId);
            evidenceRefRepository.save(ref);
            searchIndexService.index(workspaceId, sourceId, null, source.getSourceTitle(),
                    fetched.text(), "SOURCE", userId, source.getPrivacyLevel() == PrivacyLevel.S3);
            eventService.emit(workspaceId, sourceId, "SOURCE", "SOURCE_CHANGED", source.getVersion(),
                    Map.of("sourceId", sourceId.toString(), "previousHash", String.valueOf(previous)));
        }
        auditService.record(userId, workspaceId, AuditAction.SOURCE_RECHECK, "source",
                sourceId.toString(), AuditAction.RESULT_SUCCESS, Map.of("changed", changed));
        return new SourceRecheckResponse(sourceId, changed, previous, source.getContentHash(),
                source.getContentPreview(), source.getLastCheckedAt(), changed);
    }

    @Transactional(readOnly = true)
    public ExtractionRequest buildExtractionRequest(UUID workspaceId, UUID sourceId, UUID userId,
                                                    String text, String timezone) {
        SourceItem source = load(workspaceId, sourceId);
        if (source.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "来源已删除");
        }
        return new ExtractionRequest(text, source.getReceivedAt(), zoneOrDefault(timezone),
                source.getSourceApp(), source.getSourceTitle(), workspaceId, sourceId, userId,
                "job-source-ai-extraction");
    }

    @Transactional
    public void persistExtraction(UUID workspaceId, UUID sourceId, UUID userId, CandidateExtraction extraction) {
        permissionService.requireActiveMemberForUpdate(workspaceId, userId);
        boolean locked = !jdbcTemplate.queryForList("""
                SELECT 1 FROM source_items WHERE workspace_id = ? AND id = ? AND deleted_at IS NULL FOR UPDATE
                """, Integer.class, workspaceId, sourceId).isEmpty();
        if (!locked) {
            return;
        }
        SourceItem source = load(workspaceId, sourceId);
        int created = saveCandidates(source, extraction, userId).size();
        eventService.emit(workspaceId, sourceId, "SOURCE", "CANDIDATES_EXTRACTED", source.getVersion(),
                Map.of("sourceId", sourceId.toString(), "candidateCount", created));
    }

    private void enqueueExtraction(SourceItem source, String text, String timezone, UUID userId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceId", source.getId().toString());
        payload.put("userId", userId.toString());
        payload.put("text", text);
        if (timezone != null && !timezone.isBlank()) {
            payload.put("timezone", timezone);
        }
        String requestId = MDC.get("requestId");
        if (requestId != null && !requestId.isBlank()) {
            payload.put("requestId", requestId);
        }
        jobService.enqueue(source.getWorkspaceId(), userId, "SOURCE_AI_EXTRACTION",
                "source:" + source.getId(), "source:" + source.getId() + ":ai:" + source.getContentHash(),
                payload);
    }

    private List<CandidateTask> generateCandidates(SourceItem source, String text, UUID userId) {
        ExtractionRequest request = new ExtractionRequest(text, source.getReceivedAt(),
                "Asia/Shanghai", source.getSourceApp(), source.getSourceTitle(),
                source.getWorkspaceId(), source.getId(), userId, null);
        CandidateExtraction extraction = ruleExtractor.extract(request);
        return saveCandidates(source, extraction, userId);
    }

    private List<CandidateTask> saveCandidates(SourceItem source, CandidateExtraction extraction, UUID userId) {
        List<CandidateTask> saved = new ArrayList<>();
        for (CandidateExtraction.CandidateDraft draft : extraction.candidates()) {
            String title = trimTo(draft.title(), 200);
            if (title == null || title.isBlank()) {
                continue;
            }
            if (candidateRepository.existsByWorkspaceIdAndSourceItemIdAndStatusAndTitleIgnoreCase(
                    source.getWorkspaceId(), source.getId(), CandidateStatus.PENDING, title)) {
                continue;
            }
            CandidateTask candidate = new CandidateTask();
            candidate.setWorkspaceId(source.getWorkspaceId());
            candidate.setSourceItemId(source.getId());
            candidate.setTitle(title);
            candidate.setSummary(trimTo(draft.summary(), 2000));
            candidate.setCategory(trimTo(draft.category(), 40));
            candidate.setRawDueText(trimTo(draft.rawDueText(), 200));
            candidate.setDueAt(parseInstant(draft.dueAt(), draft.dueTimezone()));
            candidate.setDueDate(parseDate(draft.dueDate(), draft.dueAt(), draft.dueTimezone()));
            candidate.setDueTimezone(draft.dueTimezone());
            candidate.setTimePrecision(parsePrecision(draft.timePrecision()));
            candidate.setLocation(trimTo(draft.location(), 200));
            candidate.setMaterialsJson(writeJson(draft.materials() == null ? List.of() : draft.materials()));
            candidate.setNextAction(trimTo(draft.nextAction(), 500));
            candidate.setConfidence(BigDecimal.valueOf(Math.max(0, Math.min(1, draft.confidence()))));
            candidate.setFieldConfidenceJson(writeJson(draft.fieldConfidence() == null
                    ? Map.of() : draft.fieldConfidence()));
            List<String> reasons = new ArrayList<>(draft.reasons() == null ? List.of() : draft.reasons());
            if (!extraction.warnings().isEmpty()) {
                reasons.addAll(extraction.warnings());
            }
            candidate.setReasonsJson(writeJson(reasons));
            candidate.setEvidenceQuotesJson(writeJson(draft.evidenceQuotes() == null
                    ? List.of() : draft.evidenceQuotes()));
            candidate.setUncertaintiesJson(writeJson(draft.uncertainties() == null
                    ? List.of() : draft.uncertainties()));
            candidate.setSchemaVersion(extraction.schemaVersion());
            candidate.setNeedsUserConfirmation(true);
            candidate.setDerivation("AI".equals(extraction.derivation()) ? Derivation.AI : Derivation.RULE);
            candidate.setModelVersion(extraction.modelVersion());
            candidate.setPromptVersion(extraction.promptVersion());
            candidate.setStatus(CandidateStatus.PENDING);
            candidate.setCreatedBy(userId);
            saved.add(candidateRepository.save(candidate));
        }
        return saved;
    }

    private SourceView view(SourceItem source, UUID userId) {
        return SourceView.of(source, evidenceRefs(source.getId()), candidateIds(source.getId()));
    }

    private List<EvidenceRefView> evidenceRefs(UUID sourceId) {
        return evidenceRefRepository.findBySourceItemId(sourceId).stream()
                .map(EvidenceRefView::of).toList();
    }

    private List<UUID> candidateIds(UUID sourceId) {
        return candidateRepository.findBySourceItemIdOrderByCreatedAtDesc(sourceId).stream()
                .map(CandidateTask::getId)
                .toList();
    }

    private SourceItem load(UUID workspaceId, UUID sourceId) {
        return sourceRepository.findByWorkspaceIdAndId(workspaceId, sourceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "来源不存在"));
    }

    private Instant parseInstant(String dueAt, String timezone) {
        if (dueAt == null || dueAt.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(dueAt);
        } catch (RuntimeException ignore) {
            // 继续尝试带偏移/本地时间
        }
        try {
            return OffsetDateTime.parse(dueAt).toInstant();
        } catch (RuntimeException ignore) {
            // 继续
        }
        try {
            ZoneId zone = zone(timezone);
            return LocalDateTime.parse(dueAt).atZone(zone).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private LocalDate parseDate(String dueDate, String dueAt, String timezone) {
        if (dueDate != null && !dueDate.isBlank()) {
            try {
                return LocalDate.parse(dueDate);
            } catch (RuntimeException ignore) {
                // fall through
            }
        }
        Instant instant = parseInstant(dueAt, timezone);
        return instant == null ? null : instant.atZone(zone(timezone)).toLocalDate();
    }

    private static TimePrecision parsePrecision(String value) {
        if (value == null) {
            return TimePrecision.NONE;
        }
        try {
            return TimePrecision.valueOf(value);
        } catch (RuntimeException e) {
            return TimePrecision.NONE;
        }
    }

    private static ZoneId zone(String timezone) {
        try {
            return timezone == null || timezone.isBlank() ? ZoneId.of("Asia/Shanghai") : ZoneId.of(timezone);
        } catch (RuntimeException e) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private static String zoneOrDefault(String timezone) {
        return timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone;
    }

    private Integer parsePage(String pageRef) {
        if (pageRef == null || pageRef.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(pageRef.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String firstLine(String text) {
        return text.lines().findFirst().orElse(text).trim();
    }

    private static String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    static String sha256(String text) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
