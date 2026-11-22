package com.actionloop.api.service;

import com.actionloop.api.domain.ExportJob;
import com.actionloop.api.domain.Role;
import com.actionloop.api.repository.ExportJobRepository;
import com.actionloop.api.web.dto.CreateExportRequest;
import com.actionloop.api.web.dto.ExportView;
import com.actionloop.api.service.storage.FileStorageProvider;
import com.actionloop.shared.audit.AuditAction;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 空间数据导出（指导书 11.7 / WP13）：私有对象 + 短期有效，仅请求者与管理员可下载。
 * 导出不包含密码哈希、令牌、原始屏幕帧；来源仅导出预览文本。
 */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final ExportJobRepository exportJobRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final FileStorageProvider storage;
    private final ExportBundleBuilder bundleBuilder;

    public ExportService(ExportJobRepository exportJobRepository, PermissionService permissionService,
                         AuditService auditService, FileStorageProvider storage,
                         ExportBundleBuilder bundleBuilder) {
        this.exportJobRepository = exportJobRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.storage = storage;
        this.bundleBuilder = bundleBuilder;
    }

    public ExportView create(UUID userId, CreateExportRequest req) {
        permissionService.requireAtLeastRole(req.workspaceId(), userId, Role.ADMIN);
        ExportJob job = new ExportJob();
        job.setWorkspaceId(req.workspaceId());
        job.setRequestedBy(userId);
        job.setExportType(req.exportType() == null ? "WORKSPACE_JSON" : req.exportType());
        job.setState("PENDING");
        exportJobRepository.saveAndFlush(job);
        try {
            byte[] payload = bundleBuilder.build(req.workspaceId());
            String key = "exports/" + req.workspaceId() + "/" + job.getId() + ".json";
            storage.putObject(key, new ByteArrayInputStream(payload), payload.length);
            job.setStorageKey(key);
            job.setSizeBytes((long) payload.length);
            job.setState("READY");
            job.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        } catch (Exception e) {
            log.warn("export build failed workspace={} export={}: {}", req.workspaceId(), job.getId(), e.toString());
            job.setState("FAILED");
            job.setErrorCode("EXPORT_FAILED");
        }
        job.setUpdatedAt(Instant.now());
        exportJobRepository.save(job);
        auditService.record(userId, req.workspaceId(), AuditAction.EXPORT_CREATE, "export",
                job.getId().toString(),
                "READY".equals(job.getState()) ? AuditAction.RESULT_SUCCESS : AuditAction.RESULT_FAILURE,
                Map.of("state", job.getState(), "size", job.getSizeBytes() == null ? 0 : job.getSizeBytes()));
        return ExportView.of(job);
    }

    public List<ExportView> listMine(UUID userId) {
        return exportJobRepository.findByRequestedByOrderByCreatedAtDesc(userId).stream()
                .map(ExportView::of).toList();
    }

    public ExportView get(UUID userId, UUID exportId) {
        return ExportView.of(loadReadable(userId, exportId));
    }

    public byte[] download(UUID userId, UUID exportId) {
        ExportJob job = loadReadable(userId, exportId);
        if (!"READY".equals(job.getState()) || job.getStorageKey() == null) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "导出尚未完成");
        }
        if (job.getExpiresAt() != null && job.getExpiresAt().isBefore(Instant.now())) {
            job.setState("EXPIRED");
            job.setUpdatedAt(Instant.now());
            exportJobRepository.save(job);
            if (job.getStorageKey() != null) {
                storage.deleteObject(job.getStorageKey());
            }
            throw new BusinessException(ErrorCode.SOURCE_EXPIRED, "导出已过期，请重新生成");
        }
        try (var in = storage.getObject(job.getStorageKey())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "导出文件不可用");
        }
    }

    /** 指导书 11.7：仅请求者与空间管理员可读取导出。*/
    private ExportJob loadReadable(UUID userId, UUID exportId) {
        ExportJob job = exportJobRepository.findById(exportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "导出任务不存在"));
        boolean allowed = userId.equals(job.getRequestedBy())
                || permissionService.hasAtLeastRole(job.getWorkspaceId(), userId, Role.ADMIN);
        if (!allowed) {
            // 不向非成员泄露导出任务是否存在
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "导出任务不存在");
        }
        return job;
    }
}

