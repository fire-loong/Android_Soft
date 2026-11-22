package com.actionloop.api.web;

import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.ProcessService;
import com.actionloop.api.web.dto.CreateProcessRequest;
import com.actionloop.api.web.dto.CreateProcessVersionRequest;
import com.actionloop.api.web.dto.InstantiateProcessRequest;
import com.actionloop.api.web.dto.ProcessTemplateView;
import com.actionloop.api.web.dto.ProcessVersionView;
import com.actionloop.api.web.dto.PublishProcessVersionRequest;
import com.actionloop.api.web.dto.ReplaceProcessStepsRequest;
import com.actionloop.api.web.dto.TaskView;
import com.actionloop.shared.web.ApiResponse;
import com.actionloop.shared.web.CursorPage;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 办事流程（指导书 13.2 / WP06）：模板 → 草稿版本 → 来源核验 → 发布 → 实例化为事项。 */
@RestController
@RequestMapping("/api/v1")
public class ProcessController {

    private final ProcessService processService;

    public ProcessController(ProcessService processService) {
        this.processService = processService;
    }

    private String reqId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "req_unknown";
    }

    @GetMapping("/processes")
    public ApiResponse<CursorPage<ProcessTemplateView>> list(@AuthenticationPrincipal UserPrincipal me,
                                                             @RequestParam UUID workspaceId,
                                                             @RequestParam(required = false) String cursor,
                                                             @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(processService.list(workspaceId, me.userId(), cursor, limit), reqId());
    }

    @GetMapping("/processes/{templateId}")
    public ApiResponse<ProcessTemplateView> get(@AuthenticationPrincipal UserPrincipal me,
                                                @PathVariable UUID templateId,
                                                @RequestParam UUID workspaceId) {
        return ApiResponse.of(processService.get(workspaceId, templateId, me.userId()), reqId());
    }

    @PostMapping("/processes")
    public ApiResponse<ProcessTemplateView> create(@AuthenticationPrincipal UserPrincipal me,
                                                   @Valid @RequestBody CreateProcessRequest request) {
        return ApiResponse.of(processService.create(me.userId(), request), reqId());
    }

    @PostMapping("/processes/{templateId}/versions")
    public ApiResponse<ProcessVersionView> createVersion(@AuthenticationPrincipal UserPrincipal me,
                                                         @PathVariable UUID templateId,
                                                         @Valid @RequestBody CreateProcessVersionRequest request) {
        return ApiResponse.of(processService.createVersion(me.userId(), templateId, request), reqId());
    }

    @PutMapping("/process-versions/{versionId}/steps")
    public ApiResponse<ProcessVersionView> replaceSteps(@AuthenticationPrincipal UserPrincipal me,
                                                        @PathVariable UUID versionId,
                                                        @Valid @RequestBody ReplaceProcessStepsRequest request) {
        return ApiResponse.of(processService.replaceSteps(me.userId(), versionId, request), reqId());
    }

    @PostMapping("/process-versions/{versionId}/publish")
    public ApiResponse<ProcessVersionView> publish(@AuthenticationPrincipal UserPrincipal me,
                                                   @PathVariable UUID versionId,
                                                   @RequestBody(required = false) PublishProcessVersionRequest request) {
        PublishProcessVersionRequest body = request == null
                ? new PublishProcessVersionRequest(null, false, null) : request;
        return ApiResponse.of(processService.publish(me.userId(), versionId, body), reqId());
    }

    @PostMapping("/process-versions/{versionId}/instantiate")
    public ApiResponse<TaskView> instantiate(@AuthenticationPrincipal UserPrincipal me,
                                             @PathVariable UUID versionId,
                                             @Valid @RequestBody InstantiateProcessRequest request) {
        return ApiResponse.of(processService.instantiate(me.userId(), versionId, request), reqId());
    }
}
