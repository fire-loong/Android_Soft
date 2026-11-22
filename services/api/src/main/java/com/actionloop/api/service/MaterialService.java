package com.actionloop.api.service;

import com.actionloop.api.domain.Material;
import com.actionloop.api.domain.MaterialState;
import com.actionloop.api.domain.Task;
import com.actionloop.api.repository.EvidenceAssetRepository;
import com.actionloop.api.repository.MaterialRepository;
import com.actionloop.api.web.dto.CreateMaterialRequest;
import com.actionloop.api.web.dto.MaterialView;
import com.actionloop.api.web.dto.UpdateMaterialRequest;
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
 * 事项材料（指导书 13.2 / 3.1 Material）。关联的证据资产必须属于同一空间。
 */
@Service
public class MaterialService {

    private final MaterialRepository materialRepository;
    private final EvidenceAssetRepository assetRepository;
    private final TaskGuard taskGuard;
    private final AuditService auditService;

    public MaterialService(MaterialRepository materialRepository, EvidenceAssetRepository assetRepository,
                           TaskGuard taskGuard, AuditService auditService) {
        this.materialRepository = materialRepository;
        this.assetRepository = assetRepository;
        this.taskGuard = taskGuard;
        this.auditService = auditService;
    }

    public List<MaterialView> list(UUID workspaceId, UUID taskId, UUID userId) {
        taskGuard.loadForRead(workspaceId, taskId, userId);
        return materialRepository.findByTaskId(taskId).stream().map(MaterialView::of).toList();
    }

    @Transactional
    public MaterialView create(UUID workspaceId, UUID taskId, UUID userId, CreateMaterialRequest req) {
        Task task = taskGuard.loadInWorkspace(workspaceId, taskId);
        taskGuard.requireCanModify(workspaceId, userId, task);
        validateAsset(workspaceId, req.assetId());
        Material m = new Material();
        m.setTaskId(taskId);
        m.setName(req.name().trim());
        m.setRequiredConditionJson(req.requiredConditionJson());
        m.setFormatRuleJson(req.formatRuleJson());
        m.setState(req.state() != null ? req.state() : MaterialState.MISSING);
        m.setEvidenceRefId(req.evidenceRefId());
        m.setAssetId(req.assetId());
        m.setCreatedBy(userId);
        materialRepository.save(m);
        auditService.record(userId, workspaceId, AuditAction.MATERIAL_CREATE, "material",
                m.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of());
        return MaterialView.of(m);
    }

    @Transactional
    public MaterialView update(UUID workspaceId, UUID taskId, UUID materialId, UUID userId, UpdateMaterialRequest req) {
        Task task = taskGuard.loadInWorkspace(workspaceId, taskId);
        taskGuard.requireCanModify(workspaceId, userId, task);
        Material m = materialRepository.findByTaskIdAndId(taskId, materialId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "材料不存在"));
        checkVersion(m, req.baseVersion());
        if (req.name() != null) {
            m.setName(req.name().trim());
        }
        if (req.requiredConditionJson() != null) {
            m.setRequiredConditionJson(req.requiredConditionJson());
        }
        if (req.formatRuleJson() != null) {
            m.setFormatRuleJson(req.formatRuleJson());
        }
        if (req.state() != null) {
            m.setState(req.state());
        }
        if (req.evidenceRefId() != null) {
            m.setEvidenceRefId(req.evidenceRefId());
        }
        if (req.assetId() != null) {
            validateAsset(workspaceId, req.assetId());
            m.setAssetId(req.assetId());
        }
        materialRepository.saveAndFlush(m);
        auditService.record(userId, workspaceId, AuditAction.MATERIAL_UPDATE, "material",
                m.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of());
        return MaterialView.of(m);
    }

    @Transactional
    public void delete(UUID workspaceId, UUID taskId, UUID materialId, UUID userId) {
        Task task = taskGuard.loadInWorkspace(workspaceId, taskId);
        taskGuard.requireCanModify(workspaceId, userId, task);
        Material m = materialRepository.findByTaskIdAndId(taskId, materialId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "材料不存在"));
        m.setDeletedAt(Instant.now());
        materialRepository.save(m);
        auditService.record(userId, workspaceId, AuditAction.MATERIAL_DELETE, "material",
                m.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of());
    }

    private void validateAsset(UUID workspaceId, UUID assetId) {
        if (assetId != null && assetRepository.findByWorkspaceIdAndId(workspaceId, assetId).isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "关联的证据资产不存在");
        }
    }

    private void checkVersion(Material m, Integer baseVersion) {
        if (baseVersion == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少 baseVersion");
        }
        if (!baseVersion.equals(m.getVersion())) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "材料已被他人修改，请刷新后重试");
        }
    }
}
