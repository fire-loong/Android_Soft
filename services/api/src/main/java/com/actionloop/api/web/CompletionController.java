package com.actionloop.api.web;

import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.CompletionService;
import com.actionloop.api.web.dto.CompletionView;
import com.actionloop.api.web.dto.PendingCompletionView;
import com.actionloop.api.web.dto.ReviewCompletionRequest;
import com.actionloop.api.web.dto.SubmitCompletionRequest;
import com.actionloop.shared.web.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 完成凭证提交与核验（指导书 13.2）。 */
@RestController
@RequestMapping("/api/v1")
public class CompletionController {

    private final CompletionService completionService;

    public CompletionController(CompletionService completionService) {
        this.completionService = completionService;
    }

    private String reqId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "req_unknown";
    }

    @GetMapping("/steps/{stepId}/completions")
    public ApiResponse<List<CompletionView>> list(@AuthenticationPrincipal UserPrincipal me,
                                                  @PathVariable UUID stepId) {
        return ApiResponse.of(completionService.list(stepId, me.userId()), reqId());
    }

    /** 跨事项待核验队列（协同空间“凭证核验”）。 */
    @GetMapping("/workspaces/{workspaceId}/completions/pending")
    public ApiResponse<List<PendingCompletionView>> pending(@AuthenticationPrincipal UserPrincipal me,
                                                            @PathVariable UUID workspaceId) {
        return ApiResponse.of(completionService.pendingForReview(workspaceId, me.userId()), reqId());
    }

    @PostMapping("/steps/{stepId}/completions")
    public ApiResponse<CompletionView> submit(@AuthenticationPrincipal UserPrincipal me,
                                              @PathVariable UUID stepId,
                                              @RequestBody SubmitCompletionRequest request) {
        return ApiResponse.of(completionService.submit(stepId, me.userId(), request), reqId());
    }

    @PostMapping("/completions/{completionId}/review")
    public ApiResponse<CompletionView> review(@AuthenticationPrincipal UserPrincipal me,
                                              @PathVariable UUID completionId,
                                              @Valid @RequestBody ReviewCompletionRequest request) {
        return ApiResponse.of(completionService.review(completionId, me.userId(), request), reqId());
    }
}
