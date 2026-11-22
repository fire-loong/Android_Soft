package com.actionloop.api.web;

import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.AssistService;
import com.actionloop.api.web.dto.AssistEventView;
import com.actionloop.api.web.dto.AssistSessionView;
import com.actionloop.api.web.dto.AssistSignalRequest;
import com.actionloop.api.web.dto.AssistTurnCredentials;
import com.actionloop.api.web.dto.CreateAssistSessionRequest;
import com.actionloop.shared.web.ApiResponse;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 协助会话与信令（指导书 13.5 / WP08+WP11）。服务端拒绝一切远程控制类信令。 */
@RestController
@RequestMapping("/api/v1/assist")
public class AssistController {

    private final AssistService assistService;

    public AssistController(AssistService assistService) {
        this.assistService = assistService;
    }

    private String reqId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "req_unknown";
    }

    @PostMapping("/sessions")
    public ApiResponse<AssistSessionView> create(@AuthenticationPrincipal UserPrincipal me,
                                                 @Valid @RequestBody CreateAssistSessionRequest request) {
        return ApiResponse.of(assistService.create(me.userId(), request), reqId());
    }

    @GetMapping("/sessions")
    public ApiResponse<List<AssistSessionView>> mine(@AuthenticationPrincipal UserPrincipal me) {
        return ApiResponse.of(assistService.listMine(me.userId()), reqId());
    }

    @PostMapping("/sessions/{sessionId}/accept")
    public ApiResponse<AssistSessionView> accept(@AuthenticationPrincipal UserPrincipal me,
                                                 @PathVariable UUID sessionId) {
        return ApiResponse.of(assistService.accept(sessionId, me.userId()), reqId());
    }

    @GetMapping("/sessions/{sessionId}/events")
    public ApiResponse<List<AssistEventView>> events(@AuthenticationPrincipal UserPrincipal me,
                                                     @PathVariable UUID sessionId,
                                                     @RequestParam(defaultValue = "0") long afterSeq) {
        return ApiResponse.of(assistService.events(sessionId, me.userId(), afterSeq), reqId());
    }

    @PostMapping("/sessions/{sessionId}/signal")
    public ApiResponse<AssistEventView> signal(@AuthenticationPrincipal UserPrincipal me,
                                               @PathVariable UUID sessionId,
                                               @Valid @RequestBody AssistSignalRequest request) {
        return ApiResponse.of(assistService.signal(sessionId, me.userId(), request), reqId());
    }

    @PostMapping("/sessions/{sessionId}/stop")
    public ApiResponse<AssistSessionView> stop(@AuthenticationPrincipal UserPrincipal me,
                                               @PathVariable UUID sessionId,
                                               @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return ApiResponse.of(assistService.stop(sessionId, me.userId(), reason), reqId());
    }

    @PostMapping("/sessions/{sessionId}/turn-credentials")
    public ApiResponse<AssistTurnCredentials> turnCredentials(@AuthenticationPrincipal UserPrincipal me,
                                                             @PathVariable UUID sessionId) {
        return ApiResponse.of(assistService.turnCredentials(sessionId, me.userId()), reqId());
    }
}
