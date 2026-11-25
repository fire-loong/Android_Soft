package com.actionloop.api.account;

import com.actionloop.api.domain.MembershipStatus;
import com.actionloop.api.domain.Role;
import com.actionloop.api.domain.User;
import com.actionloop.api.domain.UserStatus;
import com.actionloop.api.domain.Workspace;
import com.actionloop.api.repository.MembershipRepository;
import com.actionloop.api.repository.UserRepository;
import com.actionloop.api.repository.WorkspaceRepository;
import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.AuditService;
import com.actionloop.api.web.dto.AccountDeletionView;
import com.actionloop.api.web.dto.RequestAccountDeletionRequest;
import com.actionloop.shared.audit.AuditAction;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 账户注销的请求/取消（指导书 5.x / 13.2 / 15.3 / 15.5）。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>必须再次输入密码（近期重新认证），失败写 DENIED 审计。</li>
 *   <li>默认冷却 7 天（{@code ACTIONLOOP_ACCOUNT_DELETION_COOLDOWN_DAYS}），冷却期内账户可用且可取消。</li>
 *   <li>若用户仍是含其他活跃成员的共享空间所有者，拒绝注销并要求先转让（409）。</li>
 *   <li>重复请求幂等，不延长既有冷却期。</li>
 * </ul>
 *
 * <p>本类依赖 Web 安全层的 {@link PasswordEncoder}，因此放在 API 专用包中，不进入 worker 组件扫描；
 * worker 只使用 {@code AccountPurgeService}。到期清除见 {@link com.actionloop.api.service.AccountPurgeService}。</p>
 */
@Service
public class AccountDeletionService {

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final MembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final int cooldownDays;

    public AccountDeletionService(UserRepository userRepository, WorkspaceRepository workspaceRepository,
                                  MembershipRepository membershipRepository, PasswordEncoder passwordEncoder,
                                  AuditService auditService,
                                  @Value("${app.account.deletion-cooldown-days:7}") int cooldownDays) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.cooldownDays = Math.max(0, cooldownDays);
    }

    public int cooldownDays() {
        return cooldownDays;
    }

    /** 注销状态视图；无待执行注销时返回 state=NONE。 */
    public AccountDeletionView view(User user) {
        return AccountDeletionView.of(user, cooldownDays);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public AccountDeletionView request(UserPrincipal principal, RequestAccountDeletionRequest request) {
        User user = loadActive(principal);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            auditService.record(user.getId(), null, AuditAction.ACCOUNT_DELETE, "user",
                    user.getId().toString(), AuditAction.RESULT_DENIED,
                    Map.of("reason", "recent_reauth_failed"));
            throw new BusinessException(ErrorCode.AUTH_INVALID, "密码错误，无法发起注销");
        }
        if (user.getDeletionScheduledAt() != null) {
            // 幂等：重复请求不延长冷却期，避免反复请求把注销无限推迟
            return view(user);
        }
        List<Workspace> blocking = findOwnedSharedWorkspaces(user.getId());
        if (!blocking.isEmpty()) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("reason", "owner_transfer_required");
            meta.put("workspaceCount", blocking.size());
            meta.put("workspaceIds", blocking.stream().map(w -> w.getId().toString()).toList());
            auditService.record(user.getId(), null, AuditAction.ACCOUNT_DELETE, "user",
                    user.getId().toString(), AuditAction.RESULT_DENIED, meta);
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "你仍是共享空间的最后所有者，请先转让空间所有权后再注销");
        }

        Instant now = Instant.now();
        user.setDeletionRequestedAt(now);
        user.setDeletionScheduledAt(now.plus(cooldownDays, ChronoUnit.DAYS));
        userRepository.save(user);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("scheduledAt", user.getDeletionScheduledAt().toString());
        meta.put("cooldownDays", cooldownDays);
        if (request.reason() != null && !request.reason().isBlank()) {
            meta.put("reason", request.reason().trim());
        }
        auditService.record(user.getId(), null, AuditAction.ACCOUNT_DELETE, "user",
                user.getId().toString(), AuditAction.RESULT_SUCCESS, meta);
        return view(user);
    }

    /** 取消待执行注销；无待执行注销时幂等返回 NONE。取消不需要再次输入密码（保护性操作）。 */
    @Transactional
    public AccountDeletionView cancel(UserPrincipal principal) {
        User user = loadActive(principal);
        if (user.getDeletionScheduledAt() == null) {
            return view(user);
        }
        Instant scheduledAt = user.getDeletionScheduledAt();
        user.setDeletionRequestedAt(null);
        user.setDeletionScheduledAt(null);
        userRepository.save(user);
        auditService.record(user.getId(), null, AuditAction.ACCOUNT_DELETE_CANCEL, "user",
                user.getId().toString(), AuditAction.RESULT_SUCCESS,
                Map.of("cancelledScheduledAt", scheduledAt.toString()));
        return view(user);
    }

    private User loadActive(UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.AUTH_INVALID, "未认证");
        }
        User user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID, "账户不存在"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.AUTH_INVALID, "账户不可用");
        }
        return user;
    }

    /** 用户是所有者且空间仍有其他活跃成员的共享空间；注销前必须先转让。 */
    private List<Workspace> findOwnedSharedWorkspaces(UUID userId) {
        Set<UUID> workspaceIds = new LinkedHashSet<>();
        workspaceRepository.findByOwnerId(userId).forEach(w -> workspaceIds.add(w.getId()));
        membershipRepository.findByUserIdAndRoleAndStatus(userId, Role.OWNER, MembershipStatus.ACTIVE)
                .forEach(m -> workspaceIds.add(m.getWorkspaceId()));
        return workspaceIds.stream()
                .map(workspaceRepository::findById)
                .flatMap(java.util.Optional::stream)
                .filter(w -> membershipRepository.countByWorkspaceIdAndStatusAndUserIdNot(
                        w.getId(), MembershipStatus.ACTIVE, userId) > 0)
                .toList();
    }
}
