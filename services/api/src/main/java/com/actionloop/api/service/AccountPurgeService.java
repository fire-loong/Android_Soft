package com.actionloop.api.service;

import com.actionloop.api.domain.Device;
import com.actionloop.api.domain.DeviceStatus;
import com.actionloop.api.domain.ExportJob;
import com.actionloop.api.domain.Membership;
import com.actionloop.api.domain.MembershipStatus;
import com.actionloop.api.domain.UploadSession;
import com.actionloop.api.domain.User;
import com.actionloop.api.domain.UserStatus;
import com.actionloop.api.domain.Workspace;
import com.actionloop.api.repository.DeviceRepository;
import com.actionloop.api.repository.EvidenceAssetRepository;
import com.actionloop.api.repository.ExportJobRepository;
import com.actionloop.api.repository.MembershipRepository;
import com.actionloop.api.repository.OutboxEventRepository;
import com.actionloop.api.repository.UploadSessionRepository;
import com.actionloop.api.repository.UserRepository;
import com.actionloop.api.repository.WorkspaceRepository;
import com.actionloop.api.service.storage.FileStorageProvider;
import com.actionloop.shared.audit.AuditAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 注销到期清除（指导书 5.x / 13.2 / 15.5）。
 *
 * <p>对每个到期账户在一个独立事务内执行：</p>
 * <ol>
 *   <li>校验仍是所有者且含其他活跃成员的共享空间 → 放弃本次清除（要求先转让），写 DENIED 审计；</li>
 *   <li>删除其名下空间的对象存储内容（证据、导出、上传临时对象）；</li>
 *   <li>清理无外键关联的 upload_sessions / outbox_events，再删除 workspace 行（其余表由数据库级
 *       ON DELETE CASCADE 清理）；</li>
 *   <li>其它空间的成员关系置为 REMOVED，撤销全部设备会话；</li>
 *   <li>账户匿名化并置为 DELETED（保留行以维持审计与历史外键，不再持有可识别信息）。</li>
 * </ol>
 *
 * <p>清除失败不吞错：事务回滚后账户仍保留过期计划，下个扫描周期重试。</p>
 * <p>该类不依赖 PasswordEncoder，因此可被 API 与 worker 同时装配（worker 定时任务见
 * {@code com.actionloop.worker.account.AccountDeletionPurgeJob}）。</p>
 */
@Service
public class AccountPurgeService {

    private static final Logger log = LoggerFactory.getLogger(AccountPurgeService.class);

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final MembershipRepository membershipRepository;
    private final DeviceRepository deviceRepository;
    private final EvidenceAssetRepository assetRepository;
    private final ExportJobRepository exportJobRepository;
    private final UploadSessionRepository uploadSessionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final FileStorageProvider storage;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public AccountPurgeService(UserRepository userRepository, WorkspaceRepository workspaceRepository,
                               MembershipRepository membershipRepository, DeviceRepository deviceRepository,
                               EvidenceAssetRepository assetRepository, ExportJobRepository exportJobRepository,
                               UploadSessionRepository uploadSessionRepository,
                               OutboxEventRepository outboxEventRepository, FileStorageProvider storage,
                               AuditService auditService, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.membershipRepository = membershipRepository;
        this.deviceRepository = deviceRepository;
        this.assetRepository = assetRepository;
        this.exportJobRepository = exportJobRepository;
        this.uploadSessionRepository = uploadSessionRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.storage = storage;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 扫描并清除所有到期账户。返回本轮成功清除数。
     * 单个账户失败只影响自身（独立事务），不影响其它账户与下轮重试。
     */
    public int purgeDue(Instant now) {
        List<UUID> due = userRepository.findDueForDeletion(UserStatus.ACTIVE, now).stream()
                .map(User::getId)
                .toList();
        if (due.isEmpty()) {
            return 0;
        }
        int purged = 0;
        int blocked = 0;
        for (UUID userId : due) {
            try {
                Outcome outcome = transactionTemplate.execute(status -> purgeOne(userId, now));
                if (outcome == Outcome.PURGED) {
                    purged++;
                } else if (outcome == Outcome.BLOCKED) {
                    blocked++;
                }
            } catch (RuntimeException e) {
                log.error("account purge failed user={}: {}", userId, e.toString(), e);
            }
        }
        log.info("account purge tick: due={} purged={} blocked={} failed={}",
                due.size(), purged, blocked, due.size() - purged - blocked);
        return purged;
    }

    private enum Outcome { PURGED, BLOCKED, SKIPPED }

    private Outcome purgeOne(UUID userId, Instant now) {
        Optional<User> locked = userRepository.findWithLockById(userId);
        if (locked.isEmpty()) {
            return Outcome.SKIPPED;
        }
        User user = locked.get();
        if (user.getStatus() != UserStatus.ACTIVE
                || user.getDeletionScheduledAt() == null
                || user.getDeletionScheduledAt().isAfter(now)) {
            return Outcome.SKIPPED;
        }

        List<Workspace> owned = workspaceRepository.findByOwnerId(userId);
        List<Workspace> shared = owned.stream()
                .filter(w -> membershipRepository.countByWorkspaceIdAndStatusAndUserIdNot(
                        w.getId(), MembershipStatus.ACTIVE, userId) > 0)
                .toList();
        if (!shared.isEmpty()) {
            // 冷却期内新增了成员：必须转让后才能注销，账户保持可用并下轮重试
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("reason", "owner_transfer_required");
            meta.put("workspaceIds", shared.stream().map(w -> w.getId().toString()).toList());
            auditService.record(userId, null, AuditAction.ACCOUNT_DELETE_PURGE, "user",
                    userId.toString(), AuditAction.RESULT_DENIED, meta);
            log.warn("account purge blocked by shared workspace ownership user={} workspaces={}",
                    userId, shared.size());
            return Outcome.BLOCKED;
        }

        int objectsDeleted = 0;
        for (Workspace workspace : owned) {
            objectsDeleted += deleteWorkspaceContent(workspace.getId());
            workspaceRepository.delete(workspace);
        }

        Set<UUID> removedWorkspaceIds = owned.stream().map(Workspace::getId).collect(Collectors.toSet());
        int membershipsRemoved = 0;
        for (MembershipStatus status : List.of(MembershipStatus.ACTIVE, MembershipStatus.PENDING)) {
            for (Membership membership : membershipRepository.findByUserIdAndStatus(userId, status)) {
                if (removedWorkspaceIds.contains(membership.getWorkspaceId())) {
                    continue;
                }
                membership.setStatus(MembershipStatus.REMOVED);
                membership.setDeletedAt(now);
                membershipRepository.save(membership);
                membershipsRemoved++;
            }
        }

        int devicesRevoked = 0;
        for (Device device : deviceRepository.findByUserId(userId)) {
            if (device.getStatus() == DeviceStatus.ACTIVE) {
                device.setStatus(DeviceStatus.REVOKED);
                device.setRefreshFamily(null);
                device.setLastRefreshJti(null);
                deviceRepository.save(device);
                devicesRevoked++;
            }
        }

        // 匿名化墓碑：保留行（审计与历史外键仍有效），但不再持有邮箱/用户名/密码哈希等可识别信息。
        // password_hash 写入不含 Argon2 前缀的随机标记，Argon2PasswordEncoder.matches 会对非法编码返回 false。
        user.setEmail("deleted+" + userId + "@deleted.actionloop.invalid");
        user.setUsername("deleted_" + userId);
        user.setDisplayName("已注销用户");
        user.setPasswordHash("deleted:" + UUID.randomUUID());
        user.setStatus(UserStatus.DELETED);
        userRepository.save(user);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("workspacesDeleted", owned.size());
        meta.put("objectsDeleted", objectsDeleted);
        meta.put("membershipsRemoved", membershipsRemoved);
        meta.put("devicesRevoked", devicesRevoked);
        auditService.record(userId, null, AuditAction.ACCOUNT_DELETE_PURGE, "user",
                userId.toString(), AuditAction.RESULT_SUCCESS, meta);
        log.info("account purged user={} workspaces={} objects={} memberships={} devices={}",
                userId, owned.size(), objectsDeleted, membershipsRemoved, devicesRevoked);
        return Outcome.PURGED;
    }

    /**
     * 删除空间的对象存储内容并清理无外键的附属表。
     * 对象删除失败直接抛出，使事务回滚（数据库记录保留，下轮重试），不产生无法追溯的孤儿对象。
     */
    private int deleteWorkspaceContent(UUID workspaceId) {
        int deleted = 0;
        for (String key : assetRepository.findStorageKeysIncludingDeleted(workspaceId)) {
            storage.deleteObject(key);
            deleted++;
        }
        for (ExportJob export : exportJobRepository.findByWorkspaceId(workspaceId)) {
            if (export.getStorageKey() != null) {
                storage.deleteObject(export.getStorageKey());
                deleted++;
            }
        }
        List<UploadSession> sessions = uploadSessionRepository.findByWorkspaceId(workspaceId);
        for (UploadSession session : sessions) {
            storage.deleteObject(session.getTempKey());
        }
        uploadSessionRepository.deleteAll(sessions);
        outboxEventRepository.deleteByWorkspaceId(workspaceId);
        return deleted;
    }
}
