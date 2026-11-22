package com.actionloop.api.web;

import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.MemoryService;
import com.actionloop.api.web.dto.MemoryContext;
import com.actionloop.api.web.dto.MemoryHit;
import com.actionloop.shared.web.ApiResponse;
import com.actionloop.shared.web.CursorPage;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 截图/文件个人记忆与情境重现（指导书 13.3 / WP05）。 */
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    private String reqId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "req_unknown";
    }

    @GetMapping("/search")
    public ApiResponse<CursorPage<MemoryHit>> search(@AuthenticationPrincipal UserPrincipal me,
                                                     @RequestParam UUID workspaceId,
                                                     @RequestParam String q,
                                                     @RequestParam(required = false) String type,
                                                     @RequestParam(required = false) String cursor,
                                                     @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(memoryService.search(workspaceId, me.userId(), q, type, cursor, limit), reqId());
    }

    @GetMapping("/{documentId}/context")
    public ApiResponse<MemoryContext> context(@AuthenticationPrincipal UserPrincipal me,
                                              @PathVariable UUID documentId,
                                              @RequestParam UUID workspaceId) {
        return ApiResponse.of(memoryService.context(workspaceId, documentId, me.userId()), reqId());
    }
}
