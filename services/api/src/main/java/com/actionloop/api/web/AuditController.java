package com.actionloop.api.web;

import com.actionloop.api.domain.AuditLog;
import com.actionloop.api.domain.Role;
import com.actionloop.api.repository.AuditLogRepository;
import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.PermissionService;
import com.actionloop.shared.web.ApiResponse;
import com.actionloop.shared.web.CursorPage;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 审计查询（指导书 15.4 / WP13）：仅 ADMIN+ 可读，追加写不可删改。 */
@RestController
@RequestMapping("/api/v1")
public class AuditController {

    public record AuditView(UUID id, UUID actorId, String action, String objectType, String objectId,
                            String result, String requestId, String metadata, Instant createdAt) {
        static AuditView of(AuditLog a) {
            return new AuditView(a.getId(), a.getActorId(), a.getAction(), a.getObjectType(),
                    a.getObjectId(), a.getResult(), a.getRequestId(), a.getMetadata(), a.getCreatedAt());
        }
    }

    private final AuditLogRepository auditLogRepository;
    private final PermissionService permissionService;

    public AuditController(AuditLogRepository auditLogRepository, PermissionService permissionService) {
        this.auditLogRepository = auditLogRepository;
        this.permissionService = permissionService;
    }

    private String reqId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "req_unknown";
    }

    @GetMapping("/audit")
    public ApiResponse<CursorPage<AuditView>> list(@AuthenticationPrincipal UserPrincipal me,
                                                   @RequestParam UUID workspaceId,
                                                   @RequestParam(required = false) String objectType,
                                                   @RequestParam(required = false) String objectId,
                                                   @RequestParam(required = false) String action,
                                                   @RequestParam(required = false) String result,
                                                   @RequestParam(required = false) String cursor,
                                                   @RequestParam(defaultValue = "20") int limit) {
        permissionService.requireAtLeastRole(workspaceId, me.userId(), Role.ADMIN);
        int size = Math.min(Math.max(limit, 1), 100);
        int pageNo = parseCursor(cursor);
        PageRequest pageable = PageRequest.of(pageNo, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.equal(root.get("workspaceId"), workspaceId));
            if (objectType != null && !objectType.isBlank()) {
                preds.add(cb.equal(root.get("objectType"), objectType));
            }
            if (objectId != null && !objectId.isBlank()) {
                preds.add(cb.equal(root.get("objectId"), objectId));
            }
            if (action != null && !action.isBlank()) {
                preds.add(cb.equal(root.get("action"), action));
            }
            if (result != null && !result.isBlank()) {
                preds.add(cb.equal(root.get("result"), result));
            }
            return cb.and(preds.toArray(new Predicate[0]));
        };
        Page<AuditLog> page = auditLogRepository.findAll(spec, pageable);
        return ApiResponse.of(CursorPage.of(page.getContent().stream().map(AuditView::of).toList(),
                page.hasNext() ? String.valueOf(pageNo + 1) : null, page.hasNext()), reqId());
    }

    private static int parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
