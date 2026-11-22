package com.actionloop.api.web;

import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.EventStreamService;
import com.actionloop.api.service.SyncService;
import com.actionloop.api.web.dto.PushSyncRequest;
import com.actionloop.api.web.dto.ServerEventView;
import com.actionloop.api.web.dto.SyncOperationView;
import com.actionloop.shared.web.ApiResponse;
import com.actionloop.shared.web.CursorPage;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 离线同步端点（指导书 13.2 / 11.4）。
 * push 批量上传离线操作（opId 幂等 + baseVersion 冲突）；pull 游标增量拉取，断点补洞。
 */
@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    private final SyncService syncService;
    private final EventStreamService eventStreamService;

    public SyncController(SyncService syncService, EventStreamService eventStreamService) {
        this.syncService = syncService;
        this.eventStreamService = eventStreamService;
    }

    private String reqId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "req_unknown";
    }

    @PostMapping("/push")
    public ApiResponse<List<SyncOperationView>> push(@AuthenticationPrincipal UserPrincipal me,
                                                     @Valid @RequestBody PushSyncRequest request) {
        return ApiResponse.of(syncService.push(me.userId(), me.deviceId(), request.operations()), reqId());
    }

    @GetMapping("/pull")
    public ApiResponse<CursorPage<SyncOperationView>> pull(@AuthenticationPrincipal UserPrincipal me,
                                                           @RequestParam(required = false) String cursor,
                                                           @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(syncService.pull(me.userId(), cursor, limit), reqId());
    }

    /** 空间增量事件：客户端用已知最大 seq 补洞，避免依赖 WebSocket 一直在线。 */
    @GetMapping("/events")
    public ApiResponse<List<ServerEventView>> events(@AuthenticationPrincipal UserPrincipal me,
                                                     @RequestParam UUID workspaceId,
                                                     @RequestParam(defaultValue = "0") long afterSeq,
                                                     @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.of(eventStreamService.list(workspaceId, me.userId(), afterSeq, limit), reqId());
    }
}
