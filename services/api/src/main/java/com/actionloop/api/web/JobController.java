package com.actionloop.api.web;

import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.JobService;
import com.actionloop.api.web.dto.JobView;
import com.actionloop.shared.web.ApiResponse;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/{id}")
    public ApiResponse<JobView> get(@AuthenticationPrincipal UserPrincipal me,
                                    @PathVariable UUID id, @RequestParam UUID workspaceId) {
        return ApiResponse.of(JobView.of(jobService.getAuthorized(workspaceId, id, me.userId())), requestId());
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<JobView> retry(@AuthenticationPrincipal UserPrincipal me,
                                      @PathVariable UUID id, @RequestParam UUID workspaceId) {
        return ApiResponse.of(JobView.of(jobService.retry(workspaceId, id, me.userId())), requestId());
    }

    private String requestId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id == null ? "req_unknown" : id;
    }
}
