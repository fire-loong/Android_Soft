package com.actionloop.api.service;

import com.actionloop.api.ai.MaaSOcrProvider;
import com.actionloop.api.ai.OcrProperties;
import com.actionloop.api.ai.OcrProvider;
import com.actionloop.api.domain.EvidenceAsset;
import com.actionloop.api.domain.Job;
import com.actionloop.api.domain.OcrResult;
import com.actionloop.api.domain.ScanState;
import com.actionloop.api.repository.EvidenceAssetRepository;
import com.actionloop.api.repository.OcrResultRepository;
import com.actionloop.api.service.storage.FileStorageProvider;
import com.actionloop.api.web.dto.OcrRequest;
import com.actionloop.api.web.dto.OcrResultView;
import com.actionloop.shared.audit.AuditAction;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 显式授权的同步云端 OCR（m7b 过渡实现，m7c 再接 jobs worker）。
 * 外部调用不包在数据库事务中；失败状态在独立事务落库，避免异常回滚抹掉失败证据。
 */
@Service
public class OcrService {

    private final EvidenceAssetRepository assetRepository;
    private final OcrResultRepository resultRepository;
    private final FileStorageProvider storage;
    private final OcrProvider provider;
    private final OcrProperties props;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final JobService jobService;
    private final TransactionTemplate transactionTemplate;

    public OcrService(EvidenceAssetRepository assetRepository, OcrResultRepository resultRepository,
                      FileStorageProvider storage, OcrProvider provider, OcrProperties props,
                      PermissionService permissionService, AuditService auditService,
                      JobService jobService, PlatformTransactionManager transactionManager) {
        this.assetRepository = assetRepository;
        this.resultRepository = resultRepository;
        this.storage = storage;
        this.provider = provider;
        this.props = props;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.jobService = jobService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Job enqueue(UUID userId, UUID assetId, OcrRequest request) {
        permissionService.requireActiveMember(request.workspaceId(), userId);
        EvidenceAsset asset = loadAsset(request.workspaceId(), assetId);
        if (!Boolean.TRUE.equals(request.cloudAuthorized())) {
            auditService.record(userId, request.workspaceId(), AuditAction.ASSET_OCR, "asset", assetId.toString(),
                    AuditAction.RESULT_DENIED, Map.of("reason", "CLOUD_AUTHORIZATION_REQUIRED"));
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "上传图片到云端 OCR 前必须显式授权");
        }
        String model = resolveModel(request.model());
        requireImage(asset);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspaceId", request.workspaceId().toString());
        payload.put("assetId", assetId.toString());
        payload.put("model", model);
        payload.put("cloudAuthorized", true);
        payload.put("requestedBy", userId.toString());
        String requestId = MDC.get("requestId");
        if (requestId != null && !requestId.isBlank()) {
            payload.put("requestId", requestId);
        }
        return jobService.enqueue(request.workspaceId(), userId, "ASSET_OCR", "asset:" + assetId,
                "asset:" + assetId + ":ocr:" + model, payload);
    }

    public OcrResultView recognize(UUID userId, UUID assetId, OcrRequest request) {
        permissionService.requireActiveMember(request.workspaceId(), userId);
        EvidenceAsset asset = loadAsset(request.workspaceId(), assetId);
        if (!Boolean.TRUE.equals(request.cloudAuthorized())) {
            auditService.record(userId, request.workspaceId(), AuditAction.ASSET_OCR, "asset", assetId.toString(),
                    AuditAction.RESULT_DENIED, Map.of("reason", "CLOUD_AUTHORIZATION_REQUIRED"));
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "上传图片到云端 OCR 前必须显式授权");
        }
        String model = resolveModel(request.model());
        requireImage(asset);

        Optional<OcrResult> cached = findResult(assetId, model);
        if (cached.isPresent()) {
            return OcrResultView.of(cached.get());
        }
        if (!"maas".equalsIgnoreCase(props.getProvider())) {
            throw new BusinessException(ErrorCode.OCR_UNAVAILABLE, "未配置可用的云端 OCR Provider");
        }

        byte[] content;
        try (InputStream input = storage.getObject(asset.getStorageKey())) {
            content = input.readAllBytes();
        } catch (IOException | RuntimeException e) {
            throw persistFailure(userId, request.workspaceId(), assetId, model,
                    new BusinessException(ErrorCode.OCR_UNAVAILABLE, "无法读取待识别证据"));
        }
        verifyContent(asset, content, request.workspaceId(), userId, model);

        String text;
        try {
            text = provider.recognize(new OcrProvider.OcrInput(content, asset.getMime(), model));
        } catch (RuntimeException e) {
            BusinessException failure = e instanceof BusinessException business
                    ? business : new BusinessException(ErrorCode.OCR_UNAVAILABLE, "OCR 服务暂不可用");
            throw persistFailure(userId, request.workspaceId(), assetId, model, failure);
        }

        try {
            return transactionTemplate.execute(status -> {
                // 与撤权串行化：锁定成员关系到本事务提交，避免复核后、提交前被撤权。
                permissionService.requireActiveMemberForUpdate(request.workspaceId(), userId);
                EvidenceAsset current = loadAsset(request.workspaceId(), assetId);
                Optional<OcrResult> raced = findResult(assetId, model);
                if (raced.isPresent()) {
                    return OcrResultView.of(raced.get());
                }
                OcrResult result = new OcrResult();
                result.setAssetId(assetId);
                result.setProvider(OcrProvider.PROVIDER);
                result.setModel(model);
                result.setPromptVersion(MaaSOcrProvider.PROMPT_VERSION);
                result.setInputSha256(asset.getSha256());
                result.setText(text);
                result.setCoordinatesAvailable(false);
                result.setCreatedBy(userId);
                OcrResult saved = resultRepository.saveAndFlush(result);

                current.setScanState(ScanState.INDEXED);
                current.setOcrFailureCode(null);
                assetRepository.save(current);
                auditService.record(userId, request.workspaceId(), AuditAction.ASSET_OCR, "asset", assetId.toString(),
                        AuditAction.RESULT_SUCCESS, Map.of("provider", OcrProvider.PROVIDER, "model", model,
                                "coordinatesAvailable", false));
                return OcrResultView.of(saved);
            });
        } catch (DataIntegrityViolationException race) {
            permissionService.requireActiveMember(request.workspaceId(), userId);
            return findResult(assetId, model).map(OcrResultView::of).orElseThrow(() -> race);
        }
    }

    public OcrResultView getResult(UUID userId, UUID workspaceId, UUID assetId, String requestedModel) {
        permissionService.requireActiveMember(workspaceId, userId);
        loadAsset(workspaceId, assetId);
        String model = resolveModel(requestedModel);
        return findResult(assetId, model).map(OcrResultView::of)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "该模型尚未生成 OCR 结果"));
    }

    private void verifyContent(EvidenceAsset asset, byte[] content, UUID workspaceId, UUID userId, String model) {
        String actualHash;
        try {
            actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        if (content.length != asset.getSizeBytes() || !actualHash.equalsIgnoreCase(asset.getSha256())) {
            throw persistFailure(userId, workspaceId, asset.getId(), model,
                    new BusinessException(ErrorCode.ASSET_INTEGRITY_MISMATCH, "证据对象已变化，未上传到云端 OCR"));
        }
    }

    /** 已开始且授权过的请求即使中途撤权也保存失败证据；随后 ACL 复核会向调用者返回 403。 */
    private BusinessException persistFailure(UUID userId, UUID workspaceId, UUID assetId, String model,
                                             BusinessException failure) {
        markFailure(userId, workspaceId, assetId, model, failure.code().name());
        permissionService.requireActiveMember(workspaceId, userId);
        return failure;
    }

    private void markFailure(UUID userId, UUID workspaceId, UUID assetId, String model, String failureCode) {
        transactionTemplate.executeWithoutResult(status -> {
            // 仅由已通过初始 ACL + 云端授权的 persistFailure 调用；撤权竞态也不能抹掉失败证据。
            EvidenceAsset asset = loadAsset(workspaceId, assetId);
            asset.setScanState(ScanState.OCR_FAILED);
            asset.setOcrFailureCode(failureCode);
            assetRepository.save(asset);
            auditService.record(userId, workspaceId, AuditAction.ASSET_OCR, "asset", assetId.toString(),
                    AuditAction.RESULT_FAILURE, Map.of("provider", OcrProvider.PROVIDER, "model", model,
                            "failureCode", failureCode));
        });
    }

    private Optional<OcrResult> findResult(UUID assetId, String model) {
        return resultRepository.findByAssetIdAndProviderAndModelAndPromptVersion(
                assetId, OcrProvider.PROVIDER, model, MaaSOcrProvider.PROMPT_VERSION);
    }

    private EvidenceAsset loadAsset(UUID workspaceId, UUID assetId) {
        return assetRepository.findByWorkspaceIdAndId(workspaceId, assetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "证据不存在"));
    }

    private String resolveModel(String requested) {
        String model = requested == null || requested.isBlank() ? props.getModel() : requested.trim();
        if (!props.isModelAllowed(model)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "OCR 模型不在白名单中，未切换其他模型");
        }
        return model;
    }

    private static void requireImage(EvidenceAsset asset) {
        String mime = asset.getMime() == null ? "" : asset.getMime().toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (!mime.equals("image/png") && !mime.equals("image/jpeg")) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "当前云端 OCR 仅支持 PNG/JPEG 图片");
        }
    }
}
