package com.actionloop.api.service;

import com.actionloop.api.domain.AuditLog;
import com.actionloop.api.repository.AuditLogRepository;
import com.actionloop.api.web.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * 统一审计落库：所有写操作都应通过本服务记录 actor / workspace / action / object / result。
 * 元数据序列化失败不阻塞主流程；requestId 从 MDC 取，缺失则为 null。
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    public void record(UUID actorId, UUID workspaceId, String action, String objectType,
                       String objectId, String result, Map<String, Object> metadata) {
        AuditLog log = new AuditLog();
        log.setActorId(actorId);
        log.setWorkspaceId(workspaceId);
        log.setAction(action);
        log.setObjectType(objectType);
        log.setObjectId(objectId);
        log.setResult(result);
        log.setRequestId(MDC.get(RequestIdFilter.MDC_KEY));
        if (metadata != null && !metadata.isEmpty()) {
            try {
                log.setMetadata(objectMapper.writeValueAsString(metadata));
            } catch (Exception ignored) {
                // 审计元数据序列化失败不阻塞主流程
            }
        }
        auditLogRepository.save(log);
    }
}
