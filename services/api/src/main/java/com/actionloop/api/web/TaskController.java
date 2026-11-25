package com.actionloop.api.web;

import com.actionloop.api.domain.TaskStatus;
import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.TaskService;
import com.actionloop.api.web.dto.CreateTaskRequest;
import com.actionloop.api.web.dto.PageView;
import com.actionloop.api.web.dto.TaskView;
import com.actionloop.api.web.dto.TransitionRequest;
import com.actionloop.api.web.dto.UpdateTaskRequest;
import com.actionloop.shared.web.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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

import java.util.UUID;

/**
 * 事项端点（指导书 13.2 / WP03）。
 * 状态变更只能走 POST /transitions，PATCH 不接受 status 字段。
 */
@RestController
@RequestMapping("/api/v1")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    private String reqId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "req_unknown";
    }

    @PostMapping("/tasks")
    public ApiResponse<TaskView> create(@AuthenticationPrincipal UserPrincipal me,
                                        @Valid @RequestBody CreateTaskRequest request) {
        return ApiResponse.of(taskService.create(me.userId(), request), reqId());
    }

    @GetMapping("/tasks")
    public ApiResponse<PageView<TaskView>> list(@AuthenticationPrincipal UserPrincipal me,
                                                @RequestParam UUID workspaceId,
                                                @RequestParam(required = false) TaskStatus status,
                                                @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        Page<TaskView> page = taskService.list(workspaceId, status, me.userId(), pageable);
        return ApiResponse.of(PageView.of(page), reqId());
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<TaskView> detail(@AuthenticationPrincipal UserPrincipal me,
                                        @RequestParam UUID workspaceId,
                                        @PathVariable UUID id) {
        return ApiResponse.of(taskService.get(workspaceId, id, me.userId()), reqId());
    }

    @PatchMapping("/tasks/{id}")
    public ApiResponse<TaskView> update(@AuthenticationPrincipal UserPrincipal me,
                                        @RequestParam UUID workspaceId,
                                        @PathVariable UUID id,
                                        @Valid @RequestBody UpdateTaskRequest request) {
        return ApiResponse.of(taskService.update(workspaceId, id, me.userId(), request), reqId());
    }

    @PostMapping("/tasks/{id}/transitions")
    public ApiResponse<TaskView> transition(@AuthenticationPrincipal UserPrincipal me,
                                            @RequestParam UUID workspaceId,
                                            @PathVariable UUID id,
                                            @Valid @RequestBody TransitionRequest request) {
        return ApiResponse.of(taskService.transition(workspaceId, id, me.userId(), request), reqId());
    }

    @DeleteMapping("/tasks/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UserPrincipal me,
                                    @RequestParam UUID workspaceId,
                                    @RequestParam Integer baseVersion,
                                    @PathVariable UUID id) {
        taskService.delete(workspaceId, id, me.userId(), baseVersion);
        return ApiResponse.of(null, reqId());
    }
}
