package com.actionloop.api.service;

import com.actionloop.api.account.AccountDeletionService;
import com.actionloop.api.domain.Device;
import com.actionloop.api.domain.DevicePlatform;
import com.actionloop.api.domain.DeviceStatus;
import com.actionloop.api.domain.User;
import com.actionloop.api.domain.UserStatus;
import com.actionloop.api.domain.Workspace;
import com.actionloop.api.repository.DeviceRepository;
import com.actionloop.api.repository.UserRepository;
import com.actionloop.api.security.JwtService;
import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.web.dto.LoginRequest;
import com.actionloop.api.web.dto.MeResponse;
import com.actionloop.api.web.dto.RefreshRequest;
import com.actionloop.api.web.dto.RegisterRequest;
import com.actionloop.api.web.dto.SessionResponse;
import com.actionloop.api.web.dto.UserResponse;
import com.actionloop.shared.audit.AuditAction;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 认证服务：注册/登录/刷新轮换/注销，全部写审计（统一走 {@link AuditService}）。
 * - 密码 Argon2id 哈希，不存明文。
 * - refresh 轮换 + 重用检测：上送 jti 与设备记录不一致即撤销整个 refresh_family。
 * - 错误消息不区分“用户不存在”与“密码错误”，避免账户枚举。
 */
@Service
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final WorkspaceService workspaceService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final AccountDeletionService accountDeletionService;

    public AuthService(UserRepository userRepository, DeviceRepository deviceRepository,
                       WorkspaceService workspaceService, JwtService jwtService,
                       PasswordEncoder passwordEncoder, AuditService auditService,
                       AccountDeletionService accountDeletionService) {
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
        this.workspaceService = workspaceService;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.accountDeletionService = accountDeletionService;
    }

    public SessionResponse register(RegisterRequest req) {
        if (userRepository.existsByEmailIgnoreCase(req.email())) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "该邮箱已注册");
        }
        if (userRepository.findByUsernameIgnoreCase(req.username()).isPresent()) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "该用户名已被占用");
        }
        User user = new User();
        user.setEmail(req.email().trim().toLowerCase());
        user.setUsername(req.username().trim());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setDisplayName(req.displayName() != null && !req.displayName().isBlank()
                ? req.displayName() : req.username());
        user.setStatus(UserStatus.ACTIVE);
        user.setTimezone(req.timezone() != null && !req.timezone().isBlank() ? req.timezone() : "Asia/Shanghai");
        user.setLocale(req.locale() != null && !req.locale().isBlank() ? req.locale() : "zh-CN");
        userRepository.save(user);

        Workspace personal = workspaceService.provisionPersonal(user);
        Device device = createDevice(user, req.devicePlatform(), req.deviceName());
        SessionResponse session = issueSession(user, device);
        auditService.record(user.getId(), personal.getId(), AuditAction.AUTH_REGISTER, "user",
                user.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of("device", device.getId().toString()));
        return session;
    }

    public SessionResponse login(LoginRequest req) {
        Optional<User> byEmail = userRepository.findByEmailIgnoreCase(req.login());
        Optional<User> candidate = byEmail.isPresent() ? byEmail : userRepository.findByUsernameIgnoreCase(req.login());
        User user = candidate.orElseThrow(() ->
                new BusinessException(ErrorCode.AUTH_INVALID, "邮箱/用户名或密码错误"));
        if (user.getStatus() == UserStatus.DELETED) {
            throw new BusinessException(ErrorCode.AUTH_INVALID, "邮箱/用户名或密码错误");
        }
        if (user.getStatus() == UserStatus.LOCKED) {
            throw new BusinessException(ErrorCode.AUTH_INVALID, "账户已锁定，请联系管理员");
        }
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_INVALID, "邮箱/用户名或密码错误");
        }

        Workspace personal = workspaceService.provisionPersonal(user);
        Device device = createDevice(user, req.devicePlatform(), req.deviceName());
        SessionResponse session = issueSession(user, device);
        auditService.record(user.getId(), personal.getId(), AuditAction.AUTH_LOGIN, "device",
                device.getId().toString(), AuditAction.RESULT_SUCCESS,
                Map.of("platform", req.devicePlatform().name()));
        return session;
    }

    /**
     * 刷新令牌轮换。BusinessException 不回滚事务：重用检测时需持久化设备撤销，
     * 而其余校验失败时尚未写入，noRollbackFor 无副作用。
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public SessionResponse refresh(RefreshRequest req) {
        Claims claims;
        try {
            claims = jwtService.parseRefreshToken(req.refreshToken());
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.AUTH_EXPIRED, "登录已过期，请重新登录");
        } catch (JwtException e) {
            throw new BusinessException(ErrorCode.AUTH_INVALID, "刷新令牌无效");
        }
        UUID userId = UUID.fromString(claims.getSubject());
        UUID deviceId = UUID.fromString(claims.get("deviceId", String.class));
        String family = claims.get("fam", String.class);
        String jti = claims.getId();

        Device device = deviceRepository.findWithLockById(deviceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID, "设备不存在或已撤销"));
        if (!userId.equals(device.getUserId()) || device.getStatus() != DeviceStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.AUTH_INVALID, "设备会话已撤销，请重新登录");
        }
        // 账户状态复核：锁定/注销不再签发新令牌，使撤销立即生效（指导书 15.3）。
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID, "账户不存在或已撤销"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.AUTH_INVALID, "账户已锁定或已注销，请重新登录");
        }

        if (!Objects.equals(device.getRefreshFamily(), family)
                || !Objects.equals(device.getLastRefreshJti(), jti)) {
            revokeDevice(device);
            deviceRepository.save(device);
            auditService.record(userId, null, AuditAction.AUTH_REFRESH, "device", deviceId.toString(),
                    AuditAction.RESULT_DENIED, Map.of("reason", "refresh_token_reuse"));
            throw new BusinessException(ErrorCode.AUTH_INVALID, "检测到刷新令牌被重用，会话已撤销，请重新登录");
        }

        String newJti = UUID.randomUUID().toString();
        device.setLastRefreshJti(newJti);
        device.setLastSeenAt(Instant.now());
        deviceRepository.save(device);

        SessionResponse session = SessionResponse.of(
                jwtService.issueAccessToken(user.getId(), device.getId(), user.getEmail()),
                jwtService.issueRefreshToken(user.getId(), device.getId(), family, newJti),
                jwtService.accessTtlSeconds(), UserResponse.of(user));
        auditService.record(userId, null, AuditAction.AUTH_REFRESH, "device", deviceId.toString(),
                AuditAction.RESULT_SUCCESS, Map.of());
        return session;
    }

    public void logout(UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.AUTH_INVALID, "未认证");
        }
        Device device = deviceRepository.findById(principal.deviceId()).orElse(null);
        if (device != null) {
            revokeDevice(device);
            deviceRepository.save(device);
            auditService.record(principal.userId(), null, AuditAction.AUTH_LOGOUT, "device",
                    principal.deviceId().toString(), AuditAction.RESULT_SUCCESS, Map.of());
        }
    }

    public MeResponse me(UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.AUTH_INVALID, "未认证");
        }
        User user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID, "账户不存在"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.AUTH_INVALID, "账户不可用");
        }
        return new MeResponse(UserResponse.of(user), workspaceService.listMyWorkspaces(user.getId()),
                accountDeletionService.view(user));
    }

    private Device createDevice(User user, DevicePlatform platform, String name) {
        Device device = new Device();
        device.setUserId(user.getId());
        device.setPlatform(platform);
        device.setDeviceName(name);
        device.setRefreshFamily(UUID.randomUUID().toString());
        device.setLastRefreshJti(UUID.randomUUID().toString());
        device.setStatus(DeviceStatus.ACTIVE);
        device.setLastSeenAt(Instant.now());
        return deviceRepository.save(device);
    }

    private SessionResponse issueSession(User user, Device device) {
        return SessionResponse.of(
                jwtService.issueAccessToken(user.getId(), device.getId(), user.getEmail()),
                jwtService.issueRefreshToken(user.getId(), device.getId(),
                        device.getRefreshFamily(), device.getLastRefreshJti()),
                jwtService.accessTtlSeconds(), UserResponse.of(user));
    }

    private void revokeDevice(Device device) {
        device.setStatus(DeviceStatus.REVOKED);
        device.setRefreshFamily(null);
        device.setLastRefreshJti(null);
    }
}
