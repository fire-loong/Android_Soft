package com.actionloop.api.service;

import com.actionloop.api.domain.ExportJob;
import com.actionloop.api.domain.Role;
import com.actionloop.api.repository.ExportJobRepository;
import com.actionloop.api.service.storage.FileStorageProvider;
import com.actionloop.api.web.RequestIdFilter;
import com.actionloop.api.web.dto.ExportView;
import com.actionloop.shared.audit.AuditAction;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 空间数据导出（指导书 11.7 / 13.2 / WP13）。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>请求阶段只做权限与入队，实际快照由 worker 异步生成（指导书 13.2「异步」）。</li>
 *   <li>导出对象私有，下载仍需登录并按请求者/空间管理员校验；有效期为短期（默认 24 小时）。</li>
 *   <li>密码重新认证在 {@code com.actionloop.api.export.ExportRequestService}（仅 API 进程），
 *       本类保持 worker 可扫描，不依赖 Web 安全层的 PasswordEncoder。</li>
 * </ul>
 */
@Service
public class ExportService {

    /** worker 消费的任务类型；payload 仅含 exportId 与 workspaceId。 */
    public static final String JOB_TYPE = "EXPORT_BUILD";

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final ExportJobRepository exportJobRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final FileStorageProvider storage;
    private final ExportBundleBuilder bundleBuilder;
    private final JobService jobService;
    private final int expiryHours;

    public ExportService(ExportJobRepository exportJobRepository, PermissionService permissionService,
                         AuditService auditService, FileStorageProvider storage,
                         ExportBundleBuilder bundleBuilder, JobService jobService,
                         @Value("${app.export.expiry-hours:24}") int expiryHours) {
        this.exportJobRepository = exportJobRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.storage = storage;
        this.bundleBuilder = bundleBuilder;
        this.jobService = jobService;
        this.expiryHours = Math.max(1, expiryHours);
    }

    /** 创建 PENDING 导出并入队；返回立即可见的任务视图（指导书 13.2 异步接口）。 */
    @Transactional
    public ExportView enqueue(UUID userId, UUID workspaceId, String exportType) {
        permissionService.requireAtLeastRole(workspaceId, userId, Role.ADMIN);
        ExportJob job = new ExportJob();
        job.setWorkspaceId(workspaceId);
        job.setRequestedBy(userId);
        job.setExportType(exportType == null || exportType.isBlank() ? "WORKSPACE_JSON" : exportType);
        job.setState("PENDING");
        exportJobRepository.saveAndFlush(job);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exportId", job.getId().toString());
        payload.put("workspaceId", workspaceId.toString());
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            payload.put("requestId", requestId);
        }
        jobService.enqueue(workspaceId, userId, JOB_TYPE, "export:" + job.getId(), null, payload);

        auditService.record(userId, workspaceId, AuditAction.EXPORT_CREATE, "export",
                job.getId().toString(), AuditAction.RESULT_SUCCESS,
                Map.of("state", job.getState(), "expiryHours", expiryHours));
        return ExportView.of(job);
    }

    /**
     * worker 执行快照生成。失败时把任务标为 FAILED 并写审计，但不抛出异常：
     * 用户可从任务列表看到失败原因并重新发起，避免盲目重试反复覆盖状态。
     */
    @Transactional
    public void process(UUID exportId) {
        ExportJob job = exportJobRepository.findWithLockById(exportId).orElse(null);
        if (job == null || !"PENDING".equals(job.getState())) {
            return;
        }
        try {
            byte[] payload = bundleBuilder.build(job.getWorkspaceId());
            String key = "exports/" + job.getWorkspaceId() + "/" + job.getId() + ".json";
            storage.putObject(key, new ByteArrayInputStream(payload), payload.length);
            job.setStorageKey(key);
            job.setSizeBytes((long) payload.length);
            job.setState("READY");
            job.setExpiresAt(Instant.now().plus(expiryHours, ChronoUnit.HOURS));
        } catch (Exception e) {
            log.warn("export build failed workspace={} export={}: {}", job.getWorkspaceId(), job.getId(), e.toString());
            job.setState("FAILED");
            job.setErrorCode("EXPORT_FAILED");
        }
        job.setUpdatedAt(Instant.now());
        exportJobRepository.save(job);
        auditService.record(job.getRequestedBy(), job.getWorkspaceId(), AuditAction.EXPORT_CREATE, "export",
                job.getId().toString(),
                "READY".equals(job.getState()) ? AuditAction.RESULT_SUCCESS : AuditAction.RESULT_FAILURE,
                Map.of("state", job.getState(), "size", job.getSizeBytes() == null ? 0 : job.getSizeBytes()));
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
        if ("EXPIRED".equals(job.getState())) {
            throw new BusinessException(ErrorCode.SOURCE_EXPIRED, "导出已过期，请重新生成");
        }
        if (!"READY".equals(job.getState()) || job.getStorageKey() == null) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "导出尚未完成");
        }
        if (job.getExpiresAt() != null && job.getExpiresAt().isBefore(Instant.now())) {
            // 先删对象再落 EXPIRED：删除失败时保留 READY，用户重试仍可下载，不会出现“已标记过期但文件还在”
            try {
                storage.deleteObject(job.getStorageKey());
            } catch (Exception e) {
                log.warn("export object delete failed export={}: {}", job.getId(), e.toString());
            }
            job.setState("EXPIRED");
            job.setUpdatedAt(Instant.now());
            exportJobRepository.save(job);
            throw new BusinessException(ErrorCode.SOURCE_EXPIRED, "导出已过期，请重新生成");
        }
        try (var in = storage.getObject(job.getStorageKey())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "导出文件不可用");
        }
    }

    /**
     * 定期清理已过期的导出对象（指导书 13.2「短期下载」）：README/UI 承诺“过期自动删除”，
     * 不能只在用户点击下载时才删除。返回处理数量；单个账户/对象失败不影响其它记录。
     */
    public int purgeExpired(Instant now) {
        List<ExportJob> due = exportJobRepository.findByStateAndExpiresAtBefore("READY", now);
        int purged = 0;
        for (ExportJob job : due) {
            if (job.getStorageKey() != null) {
                try {
                    storage.deleteObject(job.getStorageKey());
                } catch (Exception e) {
                    log.warn("expired export object delete failed export={}: {}", job.getId(), e.toString());
                    continue;
                }
            }
            job.setState("EXPIRED");
            job.setUpdatedAt(now);
            exportJobRepository.save(job);
            purged++;
        }
        return purged;
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
