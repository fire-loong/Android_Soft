package com.actionloop.api.web;

import com.actionloop.api.domain.CandidateStatus;
import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.InboxService;
import com.actionloop.api.web.dto.CandidateView;
import com.actionloop.api.web.dto.DismissCandidateRequest;
import com.actionloop.api.web.dto.InboxConfirmRequest;
import com.actionloop.api.web.dto.InboxConfirmResponse;
import com.actionloop.shared.web.ApiResponse;
import com.actionloop.shared.web.CursorPage;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 待确认收件箱（指导书 13.2 WP04）：AI/规则只产出候选，用户确认才创建事项。 */
@RestController
@RequestMapping("/api/v1/inbox")
public class InboxController {

    private final InboxService inboxService;

    public InboxController(InboxService inboxService) {
        this.inboxService = inboxService;
    }

    private String reqId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "req_unknown";
    }

    @GetMapping("/candidates")
    public ApiResponse<CursorPage<CandidateView>> list(@AuthenticationPrincipal UserPrincipal me,
                                                       @RequestParam UUID workspaceId,
                                                       @RequestParam(required = false) CandidateStatus status,
                                                       @RequestParam(required = false) String cursor,
                                                       @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(inboxService.list(workspaceId, me.userId(), status, cursor, limit), reqId());
    }

    @GetMapping("/candidates/{candidateId}")
    public ApiResponse<CandidateView> get(@AuthenticationPrincipal UserPrincipal me,
                                          @PathVariable UUID candidateId,
                                          @RequestParam UUID workspaceId) {
        return ApiResponse.of(inboxService.get(workspaceId, candidateId, me.userId()), reqId());
    }

    @PostMapping("/candidates/{candidateId}/confirm")
    public ApiResponse<InboxConfirmResponse> confirm(@AuthenticationPrincipal UserPrincipal me,
                                                     @PathVariable UUID candidateId,
                                                     @Valid @RequestBody InboxConfirmRequest request) {
        return ApiResponse.of(inboxService.confirm(me.userId(), candidateId, request), reqId());
    }

    @PostMapping("/candidates/{candidateId}/dismiss")
    public ApiResponse<CandidateView> dismiss(@AuthenticationPrincipal UserPrincipal me,
                                              @PathVariable UUID candidateId,
                                              @Valid @RequestBody DismissCandidateRequest request) {
        return ApiResponse.of(inboxService.dismiss(me.userId(), candidateId, request), reqId());
    }
}
