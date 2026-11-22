package com.actionloop.api.web;

import com.actionloop.api.domain.Task;
import com.actionloop.api.repository.SourceItemRepository;
import com.actionloop.api.repository.TaskSourceRepository;
import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.TaskGuard;
import com.actionloop.api.web.dto.TaskSourceView;
import com.actionloop.shared.web.ApiResponse;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 事项证据回溯：读取事项关联的来源与原文引文（指导书 3.1 / 9.2）。 */
@RestController
@RequestMapping("/api/v1")
public class TaskSourceController {

    private final TaskSourceRepository taskSourceRepository;
    private final SourceItemRepository sourceRepository;
    private final TaskGuard taskGuard;

    public TaskSourceController(TaskSourceRepository taskSourceRepository,
                                SourceItemRepository sourceRepository,
                                TaskGuard taskGuard) {
        this.taskSourceRepository = taskSourceRepository;
        this.sourceRepository = sourceRepository;
        this.taskGuard = taskGuard;
    }

    private String reqId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "req_unknown";
    }

    @GetMapping("/tasks/{taskId}/sources")
    public ApiResponse<List<TaskSourceView>> list(@AuthenticationPrincipal UserPrincipal me,
                                                  @PathVariable UUID taskId,
                                                  @RequestParam UUID workspaceId) {
        Task task = taskGuard.loadForRead(workspaceId, taskId, me.userId());
        List<TaskSourceView> views = taskSourceRepository.findByTaskId(task.getId()).stream()
                .map(link -> TaskSourceView.of(link,
                        sourceRepository.findByWorkspaceIdAndId(workspaceId, link.getSourceItemId()).orElse(null)))
                .toList();
        return ApiResponse.of(views, reqId());
    }
}
