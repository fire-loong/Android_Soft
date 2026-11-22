package com.actionloop.api.web;

import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.SourceService;
import com.actionloop.api.web.dto.EvidenceRefView;
import com.actionloop.api.web.dto.SourceRecheckResponse;
import com.actionloop.api.web.dto.SourceShareRequest;
import com.actionloop.api.web.dto.SourceView;
import com.actionloop.shared.web.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 来源采集、原文回溯与官方来源复查（指导书 13.2 / WP04+WP06）。 */
@RestController
@RequestMapping("/api/v1")
public class SourceController {

    private final SourceService sourceService;

    public SourceController(SourceService sourceService) {
        this.sourceService = sourceService;
    }

    private String reqId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "req_unknown";
    }

    @PostMapping("/sources/share")
    public ApiResponse<SourceView> share(@AuthenticationPrincipal UserPrincipal me,
                                         @Valid @RequestBody SourceShareRequest request) {
        return ApiResponse.of(sourceService.share(me.userId(), request), reqId());
    }

    @GetMapping("/sources/{sourceId}")
    public ApiResponse<SourceView> get(@AuthenticationPrincipal UserPrincipal me,
                                       @PathVariable UUID sourceId,
                                       @RequestParam UUID workspaceId) {
        return ApiResponse.of(sourceService.get(workspaceId, sourceId, me.userId()), reqId());
    }

    @GetMapping("/sources")
    public ApiResponse<Page<SourceView>> list(@AuthenticationPrincipal UserPrincipal me,
                                              @RequestParam UUID workspaceId,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(sourceService.list(workspaceId, me.userId(), page, size), reqId());
    }

    @DeleteMapping("/sources/{sourceId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UserPrincipal me,
                                    @PathVariable UUID sourceId,
                                    @RequestParam UUID workspaceId,
                                    @RequestParam Integer baseVersion) {
        sourceService.delete(workspaceId, sourceId, me.userId(), baseVersion);
        return ApiResponse.of(null, reqId());
    }

    @PostMapping("/sources/{sourceId}/recheck")
    public ApiResponse<SourceRecheckResponse> recheck(@AuthenticationPrincipal UserPrincipal me,
                                                      @PathVariable UUID sourceId,
                                                      @RequestParam UUID workspaceId) {
        return ApiResponse.of(sourceService.recheck(workspaceId, sourceId, me.userId()), reqId());
    }

    @GetMapping("/sources/{sourceId}/changes")
    public ApiResponse<List<EvidenceRefView>> changes(@AuthenticationPrincipal UserPrincipal me,
                                                      @PathVariable UUID sourceId,
                                                      @RequestParam UUID workspaceId) {
        SourceView view = sourceService.get(workspaceId, sourceId, me.userId());
        return ApiResponse.of(view.evidence(), reqId());
    }
}
