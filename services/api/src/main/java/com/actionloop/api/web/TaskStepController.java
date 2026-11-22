package com.actionloop.api.web;

import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.TaskStepService;
import com.actionloop.api.web.dto.CreateStepRequest;
import com.actionloop.api.web.dto.StepView;
import com.actionloop.api.web.dto.UpdateStepRequest;
import com.actionloop.shared.web.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class TaskStepController {

    private final TaskStepService stepService;

    public TaskStepController(TaskStepService stepService) {
        this.stepService = stepService;
    }

    private String reqId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "req_unknown";
    }

    @GetMapping("/tasks/{taskId}/steps")
    public ApiResponse<List<StepView>> list(@AuthenticationPrincipal UserPrincipal me,
                                            @RequestParam UUID workspaceId,
                                            @PathVariable UUID taskId) {
        return ApiResponse.of(stepService.list(workspaceId, taskId, me.userId()), reqId());
    }

    @PostMapping("/tasks/{taskId}/steps")
    public ApiResponse<StepView> create(@AuthenticationPrincipal UserPrincipal me,
                                        @RequestParam UUID workspaceId,
                                        @PathVariable UUID taskId,
                                        @Valid @RequestBody CreateStepRequest request) {
        return ApiResponse.of(stepService.create(workspaceId, taskId, me.userId(), request), reqId());
    }

    @PatchMapping("/tasks/{taskId}/steps/{id}")
    public ApiResponse<StepView> update(@AuthenticationPrincipal UserPrincipal me,
                                        @RequestParam UUID workspaceId,
                                        @PathVariable UUID taskId,
                                        @PathVariable UUID id,
                                        @Valid @RequestBody UpdateStepRequest request) {
        return ApiResponse.of(stepService.update(workspaceId, taskId, id, me.userId(), request), reqId());
    }

    @DeleteMapping("/tasks/{taskId}/steps/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UserPrincipal me,
                                    @RequestParam UUID workspaceId,
                                    @PathVariable UUID taskId,
                                    @PathVariable UUID id) {
        stepService.delete(workspaceId, taskId, id, me.userId());
        return ApiResponse.of(null, reqId());
    }
}
