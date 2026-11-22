package com.actionloop.api.web;

import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.ReminderService;
import com.actionloop.api.web.dto.ReminderView;
import com.actionloop.api.web.dto.ReplaceRemindersRequest;
import com.actionloop.shared.web.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 事项提醒：GET 读取，PUT 整体替换（去重、旧计划取消，指导书 13.2）。 */
@RestController
@RequestMapping("/api/v1")
public class ReminderController {

    private final ReminderService reminderService;

    public ReminderController(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    private String reqId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "req_unknown";
    }

    @GetMapping("/tasks/{taskId}/reminders")
    public ApiResponse<List<ReminderView>> list(@AuthenticationPrincipal UserPrincipal me,
                                                @RequestParam UUID workspaceId,
                                                @PathVariable UUID taskId) {
        return ApiResponse.of(reminderService.list(workspaceId, taskId, me.userId()), reqId());
    }

    /** 工作区级批量拉取（单请求消除 N+1），返回空间内全部事项的提醒。 */
    @GetMapping("/workspaces/{workspaceId}/reminders")
    public ApiResponse<List<ReminderView>> listForWorkspace(@AuthenticationPrincipal UserPrincipal me,
                                                            @PathVariable UUID workspaceId) {
        return ApiResponse.of(reminderService.listForWorkspace(workspaceId, me.userId()), reqId());
    }

    @PutMapping("/tasks/{taskId}/reminders")
    public ApiResponse<List<ReminderView>> replace(@AuthenticationPrincipal UserPrincipal me,
                                                   @RequestParam UUID workspaceId,
                                                   @PathVariable UUID taskId,
                                                   @Valid @RequestBody ReplaceRemindersRequest request) {
        return ApiResponse.of(reminderService.replace(workspaceId, taskId, me.userId(), request), reqId());
    }
}
