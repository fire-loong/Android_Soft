package com.actionloop.api.export;

import com.actionloop.api.domain.User;
import com.actionloop.api.domain.UserStatus;
import com.actionloop.api.repository.UserRepository;
import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.AuditService;
import com.actionloop.api.service.ExportService;
import com.actionloop.api.web.dto.CreateExportRequest;
import com.actionloop.api.web.dto.ExportView;
import com.actionloop.shared.audit.AuditAction;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 高敏导出前的近期重新认证（指导书 13.2 二次认证 / 15.3）。
 *
 * <p>本类依赖 Web 安全层的 {@link PasswordEncoder}，放在 API 专用包中，
 * 不进入 worker 组件扫描；worker 只使用 {@link ExportService} 执行快照生成。</p>
 */
@Service
public class ExportRequestService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final ExportService exportService;

    public ExportRequestService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                AuditService auditService, ExportService exportService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.exportService = exportService;
    }

    public ExportView request(UserPrincipal principal, CreateExportRequest request) {
        User user = userRepository.findById(principal.userId())
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID, "账户不可用"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            auditService.record(user.getId(), request.workspaceId(), AuditAction.EXPORT_CREATE, "export",
                    null, AuditAction.RESULT_DENIED, Map.of("reason", "recent_reauth_failed"));
            throw new BusinessException(ErrorCode.AUTH_INVALID, "密码错误，无法创建导出");
        }
        return exportService.enqueue(user.getId(), request.workspaceId(), request.exportType());
    }
}
