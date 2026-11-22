package com.actionloop.api.service;

import com.actionloop.api.domain.SearchDocument;
import com.actionloop.api.domain.SourceItem;
import com.actionloop.api.domain.Task;
import com.actionloop.api.repository.EvidenceRefRepository;
import com.actionloop.api.repository.SearchDocumentRepository;
import com.actionloop.api.repository.SourceItemRepository;
import com.actionloop.api.repository.TaskRepository;
import com.actionloop.api.repository.TaskSourceRepository;
import com.actionloop.api.web.dto.EvidenceRefView;
import com.actionloop.api.web.dto.MemoryContext;
import com.actionloop.api.web.dto.MemoryHit;
import com.actionloop.api.web.dto.SourceView;
import com.actionloop.api.web.dto.TaskView;
import com.actionloop.shared.audit.AuditAction;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import com.actionloop.shared.web.CursorPage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 个人记忆检索与情境重现（指导书 5.x / 13.3）。
 * 检索前先做空间 ACL；PERSONAL 文档仅 owner 可见；命中返回引用坐标，不泄露无权限内容。
 */
@Service
public class MemoryService {

    private static final int MAX_LIMIT = 100;

    private final SearchIndexService searchIndexService;
    private final SearchDocumentRepository documentRepository;
    private final SourceItemRepository sourceRepository;
    private final EvidenceRefRepository evidenceRefRepository;
    private final TaskSourceRepository taskSourceRepository;
    private final TaskRepository taskRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public MemoryService(SearchIndexService searchIndexService, SearchDocumentRepository documentRepository,
                         SourceItemRepository sourceRepository, EvidenceRefRepository evidenceRefRepository,
                         TaskSourceRepository taskSourceRepository, TaskRepository taskRepository,
                         PermissionService permissionService, AuditService auditService, ObjectMapper objectMapper) {
        this.searchIndexService = searchIndexService;
        this.documentRepository = documentRepository;
        this.sourceRepository = sourceRepository;
        this.evidenceRefRepository = evidenceRefRepository;
        this.taskSourceRepository = taskSourceRepository;
        this.taskRepository = taskRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CursorPage<MemoryHit> search(UUID workspaceId, UUID userId, String query, String docType,
                                        String cursor, int limit) {
        permissionService.requireActiveMember(workspaceId, userId);
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "检索词不能为空");
        }
        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        int offset = parseCursor(cursor);
        List<SearchIndexService.Hit> hits = searchIndexService.search(workspaceId, userId, query.trim(),
                docType, offset, size + 1);
        boolean hasMore = hits.size() > size;
        if (hasMore) {
            hits = hits.subList(0, size);
        }
        List<MemoryHit> items = hits.stream()
                .map(h -> new MemoryHit(h.id(), h.title(), h.snippet(), h.docType(),
                        h.sourceItemId(), h.assetId(), h.score(), h.createdAt(),
                        privacyNote(h.ownerScope())))
                .toList();
        auditService.record(userId, workspaceId, AuditAction.MEMORY_SEARCH, "workspace",
                workspaceId.toString(), AuditAction.RESULT_SUCCESS,
                Map.of("queryHash", SourceService.sha256(query.trim()), "queryLength", query.length(),
                        "hits", items.size()));
        return CursorPage.of(items, hasMore ? String.valueOf(offset + size) : null, hasMore);
    }

    @Transactional
    public MemoryContext context(UUID workspaceId, UUID documentId, UUID userId) {
        permissionService.requireActiveMember(workspaceId, userId);
        SearchDocument doc = documentRepository.findById(documentId)
                .filter(d -> workspaceId.equals(d.getWorkspaceId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "记忆文档不存在"));
        if (isPersonal(doc.getOwnerScope()) && !userId.toString().equals(ownerId(doc.getOwnerScope()))) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "无权查看他人的个人记忆");
        }
        SourceItem source = doc.getSourceItemId() == null ? null
                : sourceRepository.findByWorkspaceIdAndId(workspaceId, doc.getSourceItemId()).orElse(null);
        List<EvidenceRefView> evidence = source == null ? List.of()
                : evidenceRefRepository.findBySourceItemId(source.getId()).stream()
                .map(EvidenceRefView::of).toList();
        List<TaskView> relatedTasks = source == null ? List.of() : relatedTasks(workspaceId, source.getId());
        List<String> notes = new ArrayList<>();
        notes.add(isPersonal(doc.getOwnerScope()) ? "个人记忆：仅本人可见" : "空间记忆：当前空间成员可见");
        if (doc.getSourceItemId() != null && source == null) {
            notes.add("原始来源已删除，仅保留检索索引");
        }
        auditService.record(userId, workspaceId, AuditAction.MEMORY_CONTEXT, "search_document",
                documentId.toString(), AuditAction.RESULT_SUCCESS, Map.of());
        return new MemoryContext(doc.getId(), doc.getTitle(), doc.getContentTokens(), doc.getDocType(),
                privacyNote(doc.getOwnerScope()),
                source == null ? null : SourceView.of(source, evidence, List.of()),
                evidence, relatedTasks, notes);
    }

    private List<TaskView> relatedTasks(UUID workspaceId, UUID sourceId) {
        List<UUID> taskIds = taskSourceRepository.findBySourceItemId(sourceId).stream()
                .map(ts -> ts.getTaskId()).distinct().toList();
        if (taskIds.isEmpty()) {
            return List.of();
        }
        List<Task> tasks = taskRepository.findAllById(taskIds);
        return tasks.stream().filter(t -> workspaceId.equals(t.getWorkspaceId())).map(TaskView::of).toList();
    }

    private boolean isPersonal(String ownerScope) {
        return "PERSONAL".equals(scopeValue(ownerScope, "visibility"));
    }

    private String ownerId(String ownerScope) {
        return scopeValue(ownerScope, "ownerId");
    }

    private String scopeValue(String json, String key) {
        try {
            Map<String, Object> map = objectMapper.readValue(json == null ? "{}" : json,
                    new TypeReference<Map<String, Object>>() {
                    });
            Object v = map.get(key);
            return v == null ? null : v.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String privacyNote(String ownerScope) {
        return isPersonal(ownerScope) ? "个人记忆：仅本人可见，未上传云端" : "空间记忆：空间成员可见";
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
}
