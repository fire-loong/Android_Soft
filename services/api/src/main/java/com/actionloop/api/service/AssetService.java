package com.actionloop.api.service;

import com.actionloop.api.domain.EvidenceAsset;
import com.actionloop.api.domain.Role;
import com.actionloop.api.domain.UploadSession;
import com.actionloop.api.domain.UploadSessionState;
import com.actionloop.api.repository.EvidenceAssetRepository;
import com.actionloop.api.repository.UploadSessionRepository;
import com.actionloop.api.security.JwtProperties;
import com.actionloop.api.service.storage.FileStorageProvider;
import com.actionloop.api.service.storage.StorageProperties;
import com.actionloop.api.web.dto.AssetView;
import com.actionloop.api.web.dto.CompleteUploadRequest;
import com.actionloop.api.web.dto.InitUploadRequest;
import com.actionloop.api.web.dto.InitUploadResponse;
import com.actionloop.api.web.dto.UploadTargetView;
import com.actionloop.shared.audit.AuditAction;
import com.actionloop.shared.domain.UuidV7;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * 证据文件上传与私有下载（指导书 11.6 / 13.2）。
 * - init：校验配额/MIME/大小，同空间同 sha256 去重，分配临时对象。
 * - complete：HeadObject 等价校验长度与哈希，通过后落 EvidenceAsset。
 * - 下载走短期签名 URL；桶/目录不公开，不暴露真实文件名。
 */
@Service
public class AssetService {

    private final EvidenceAssetRepository assetRepository;
    private final UploadSessionRepository sessionRepository;
    private final FileStorageProvider storage;
    private final StorageProperties props;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final JwtProperties jwt;

    public AssetService(EvidenceAssetRepository assetRepository, UploadSessionRepository sessionRepository,
                        FileStorageProvider storage, StorageProperties props,
                        PermissionService permissionService, AuditService auditService, JwtProperties jwt) {
        this.assetRepository = assetRepository;
        this.sessionRepository = sessionRepository;
        this.storage = storage;
        this.props = props;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.jwt = jwt;
    }

    public record DownloadContent(InputStream stream, String contentType, long contentLength) {
    }

    @Transactional
    public InitUploadResponse init(UUID userId, InitUploadRequest req, String apiBaseUrl) {
        String sha = req.sha256().toLowerCase();
        if (req.sizeBytes() > props.getMaxFileSizeBytes()) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "文件超过大小上限");
        }
        permissionService.requireAtLeastRole(req.workspaceId(), userId, Role.MEMBER);
        long used = assetRepository.sumSizeBytesByWorkspaceId(req.workspaceId());
        if (used + req.sizeBytes() > props.getQuotaBytesPerWorkspace()) {
            throw new BusinessException(ErrorCode.QUOTA_EXCEEDED, "空间存储配额不足");
        }
        // 去重：同空间同内容不重复占用对象存储（指导书 11.x）
        EvidenceAsset existing = assetRepository.findByWorkspaceIdAndSha256(req.workspaceId(), sha).orElse(null);
        if (existing != null) {
            auditService.record(userId, req.workspaceId(), AuditAction.ASSET_UPLOAD, "asset",
                    existing.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of("dedup", true));
            return new InitUploadResponse(null, null, null, AssetView.of(existing));
        }

        UUID sessionId = UuidV7.generate();
        String tempKey = "_incoming/" + sessionId;
        String finalKey = req.workspaceId() + "/" + sha;
        UploadSession session = new UploadSession();
        session.setId(sessionId);
        session.setWorkspaceId(req.workspaceId());
        session.setCreatedBy(userId);
        session.setSha256(sha);
        session.setMime(req.mime());
        session.setSizeBytes(req.sizeBytes());
        session.setFileName(req.fileName());
        session.setStorageKey(finalKey);
        session.setTempKey(tempKey);
        session.setState(UploadSessionState.INITIATED);
        session.setExpiresAt(Instant.now().plus(Duration.ofMinutes(props.getUploadTtlMinutes())));
        sessionRepository.save(session);

        UploadTargetView target;
        String presign = storage.presignPut(tempKey, Duration.ofMinutes(props.getUploadTtlMinutes()));
        if (presign != null) {
            target = new UploadTargetView("PUT", presign, Map.of(), false);
        } else {
            target = new UploadTargetView("PUT",
                    apiBaseUrl + "/api/v1/assets/uploads/" + sessionId + "/content", Map.of(), true);
        }
        auditService.record(userId, req.workspaceId(), AuditAction.ASSET_UPLOAD, "upload_session",
                sessionId.toString(), AuditAction.RESULT_SUCCESS, Map.of("size", req.sizeBytes()));
        return new InitUploadResponse(sessionId, target, (long) props.getUploadTtlMinutes() * 60, null);
    }

    public void putContent(UUID sessionId, UUID userId, InputStream body, long declaredSize) throws java.io.IOException {
        UploadSession session = loadSession(sessionId, userId);
        if ("s3".equals(storage.providerType())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "当前存储类型请使用预签名地址直传，不经过 API 代理");
        }
        if (declaredSize > props.getMaxFileSizeBytes()) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "文件超过大小上限");
        }
        // 声明长度可被客户端伪造：在流读取阶段强制上限，防止绕过 50MB 限制耗尽磁盘（指导书 11.6 / 15.3）。
        InputStream limited = new SizeLimitedInputStream(body, props.getMaxFileSizeBytes());
        try {
            storage.putObject(session.getTempKey(), limited, declaredSize);
        } catch (SizeLimitExceededException e) {
            storage.deleteObject(session.getTempKey());
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "文件超过大小上限 " + props.getMaxFileSizeBytes() + " 字节");
        }
    }

    @Transactional
    public AssetView complete(UUID sessionId, UUID userId, CompleteUploadRequest req) {
        UploadSession session = loadSession(sessionId, userId);
        if (!req.sha256().equalsIgnoreCase(session.getSha256())
                || !req.sizeBytes().equals(session.getSizeBytes())) {
            fail(session, "complete 声明与初始化不一致");
            throw new BusinessException(ErrorCode.ASSET_INTEGRITY_MISMATCH, "上传声明与初始化不一致");
        }
        if (!storage.exists(session.getTempKey())) {
            fail(session, "未找到上传内容");
            throw new BusinessException(ErrorCode.UPLOAD_INCOMPLETE, "未找到上传内容，请先上传字节");
        }
        long actualSize = storage.size(session.getTempKey());
        if (actualSize != session.getSizeBytes()) {
            fail(session, "大小不匹配");
            throw new BusinessException(ErrorCode.ASSET_INTEGRITY_MISMATCH, "文件大小与声明不一致");
        }
        try {
            String actualHash = storage.computeSha256(session.getTempKey());
            // 失败关闭：无法计算哈希（actualHash 为 null）也视为不匹配，绝不跳过完整性校验（指导书 11.6）。
            if (actualHash == null || !actualHash.equalsIgnoreCase(session.getSha256())) {
                fail(session, "哈希不匹配");
                throw new BusinessException(ErrorCode.ASSET_INTEGRITY_MISMATCH, "文件哈希与声明不一致");
            }
        } catch (java.io.IOException e) {
            fail(session, "哈希计算失败");
            throw new BusinessException(ErrorCode.ASSET_INTEGRITY_MISMATCH, "无法计算文件哈希");
        }

        String finalKey = session.getStorageKey();
        EvidenceAsset asset = assetRepository.findByWorkspaceIdAndSha256(session.getWorkspaceId(), session.getSha256())
                .orElseGet(() -> createAsset(session, finalKey, userId));
        // 去重命中（含并发）：清理临时对象
        storage.deleteObject(session.getTempKey());

        session.setState(UploadSessionState.COMPLETED);
        session.setCompletedAt(Instant.now());
        session.setAssetId(asset.getId());
        sessionRepository.save(session);
        auditService.record(userId, session.getWorkspaceId(), AuditAction.ASSET_COMPLETE, "asset",
                asset.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of("size", asset.getSizeBytes()));
        return AssetView.of(asset);
    }

    public AssetView getAsset(UUID workspaceId, UUID assetId, UUID userId) {
        permissionService.requireActiveMember(workspaceId, userId);
        return AssetView.of(loadAsset(workspaceId, assetId));
    }

    public String downloadUrl(UUID workspaceId, UUID assetId, UUID userId, String apiBaseUrl) {
        permissionService.requireActiveMember(workspaceId, userId);
        EvidenceAsset asset = loadAsset(workspaceId, assetId);
        String presign = storage.presignGet(asset.getStorageKey(), Duration.ofMinutes(props.getDownloadTtlMinutes()));
        if (presign != null) {
            return presign;
        }
        long expires = Instant.now().getEpochSecond() + (long) props.getDownloadTtlMinutes() * 60;
        String token = signToken(assetId, expires, workspaceId, userId);
        return apiBaseUrl + "/api/v1/assets/" + assetId + "/download?expires=" + expires
                + "&workspaceId=" + workspaceId + "&userId=" + userId + "&token=" + token;
    }

    public DownloadContent openDownload(UUID assetId, long expires, UUID workspaceId, UUID userId, String token) {
        String expected = signToken(assetId, expires, workspaceId, userId);
        if (!constantTimeEquals(expected, token)) {
            throw new BusinessException(ErrorCode.AUTH_INVALID, "下载签名无效");
        }
        if (Instant.now().getEpochSecond() > expires) {
            throw new BusinessException(ErrorCode.SESSION_EXPIRED, "下载链接已过期");
        }
        EvidenceAsset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "证据不存在"));
        if (!asset.getWorkspaceId().equals(workspaceId)) {
            throw new BusinessException(ErrorCode.AUTH_INVALID, "下载签名与空间不匹配");
        }
        // ACL 撤销：签发链接的用户必须仍是该空间有效成员，被移除/锁定后旧链接立即失效（指导书 11.6）。
        permissionService.requireActiveMember(workspaceId, userId);
        try {
            return new DownloadContent(storage.getObject(asset.getStorageKey()),
                    sanitizeDownloadMime(asset.getMime()), asset.getSizeBytes());
        } catch (java.io.IOException e) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "证据对象不可用");
        }
    }

    private EvidenceAsset createAsset(UploadSession session, String finalKey, UUID userId) {
        if (!storage.exists(finalKey)) {
            try (java.io.InputStream in = storage.getObject(session.getTempKey())) {
                storage.putObject(finalKey, in, session.getSizeBytes());
            } catch (java.io.IOException e) {
                throw new IllegalStateException("对象落盘失败", e);
            }
        }
        storage.deleteObject(session.getTempKey());
        EvidenceAsset a = new EvidenceAsset();
        a.setWorkspaceId(session.getWorkspaceId());
        a.setSha256(session.getSha256());
        a.setMime(session.getMime());
        a.setSizeBytes(session.getSizeBytes());
        a.setStorageKey(finalKey);
        a.setCreatedBy(userId);
        try {
            return assetRepository.saveAndFlush(a);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // 并发同内容上传：唯一索引兜底，返回已存在行（指导书 11.6 同内容去重，不重复占对象存储）。
            return assetRepository.findByWorkspaceIdAndSha256(session.getWorkspaceId(), session.getSha256())
                    .orElseThrow(() -> race);
        }
    }

    private UploadSession loadSession(UUID sessionId, UUID userId) {
        UploadSession session = sessionRepository.findByIdAndCreatedBy(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "上传会话不存在"));
        // 成员被移除/锁定后，未完成的上传会话立即失效，避免撤权后继续写入对象存储（指导书 11.6）。
        permissionService.requireAtLeastRole(session.getWorkspaceId(), userId, Role.MEMBER);
        if (session.getExpiresAt().isBefore(Instant.now())) {
            if (session.getState() == UploadSessionState.INITIATED) {
                session.setState(UploadSessionState.EXPIRED);
                sessionRepository.save(session);
            }
            throw new BusinessException(ErrorCode.SESSION_EXPIRED, "上传会话已过期");
        }
        if (session.getState() != UploadSessionState.INITIATED) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "上传会话状态异常：" + session.getState());
        }
        return session;
    }

    private EvidenceAsset loadAsset(UUID workspaceId, UUID assetId) {
        return assetRepository.findByWorkspaceIdAndId(workspaceId, assetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "证据不存在"));
    }

    private void fail(UploadSession session, String reason) {
        session.setState(UploadSessionState.FAILED);
        session.setFailureReason(reason);
        sessionRepository.save(session);
        storage.deleteObject(session.getTempKey());
    }

    private String signToken(UUID assetId, long expires, UUID workspaceId, UUID userId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwt.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String msg = assetId + "|" + expires + "|" + workspaceId + "|" + userId;
            return HexFormat.of().formatHex(mac.doFinal(msg.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** 内联渲染会执行脚本的 MIME 一律降为 octet-stream，配合附件下载头防存储型 XSS（指导书 11.6）。 */
    private static final java.util.Set<String> UNSAFE_INLINE_MIME = java.util.Set.of(
            "text/html", "application/xhtml+xml", "image/svg+xml",
            "text/javascript", "application/javascript", "application/x-javascript", "text/ecmascript");

    private static String sanitizeDownloadMime(String mime) {
        if (mime == null) {
            return "application/octet-stream";
        }
        String lower = mime.toLowerCase(java.util.Locale.ROOT).split(";")[0].trim();
        if (UNSAFE_INLINE_MIME.contains(lower) || lower.endsWith("+xml")) {
            return "application/octet-stream";
        }
        return mime;
    }

    /** 读取字节数超过 max 即抛 SizeLimitExceededException，用于在流式上传阶段强制执行大小上限。 */
    private static final class SizeLimitedInputStream extends InputStream {
        private final InputStream in;
        private final long max;
        private long count = 0;

        SizeLimitedInputStream(InputStream in, long max) {
            this.in = in;
            this.max = max;
        }

        @Override
        public int read() throws java.io.IOException {
            int b = in.read();
            if (b != -1) {
                count++;
                checkLimit();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                count += n;
                checkLimit();
            }
            return n;
        }

        private void checkLimit() throws SizeLimitExceededException {
            if (count > max) {
                throw new SizeLimitExceededException();
            }
        }

        @Override
        public void close() throws java.io.IOException {
            in.close();
        }
    }

    private static final class SizeLimitExceededException extends java.io.IOException {
    }
}
