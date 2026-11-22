package com.actionloop.api.web;

import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.ExportService;
import com.actionloop.api.web.dto.CreateExportRequest;
import com.actionloop.api.web.dto.ExportView;
import com.actionloop.shared.web.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 数据导出（指导书 11.7 / WP13）。 */
@RestController
@RequestMapping("/api/v1/exports")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    private String reqId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "req_unknown";
    }

    @PostMapping
    public ApiResponse<ExportView> create(@AuthenticationPrincipal UserPrincipal me,
                                          @Valid @RequestBody CreateExportRequest request) {
        return ApiResponse.of(exportService.create(me.userId(), request), reqId());
    }

    @GetMapping
    public ApiResponse<List<ExportView>> mine(@AuthenticationPrincipal UserPrincipal me) {
        return ApiResponse.of(exportService.listMine(me.userId()), reqId());
    }

    @GetMapping("/{exportId}")
    public ApiResponse<ExportView> get(@AuthenticationPrincipal UserPrincipal me,
                                       @PathVariable UUID exportId) {
        return ApiResponse.of(exportService.get(me.userId(), exportId), reqId());
    }

    @GetMapping("/{exportId}/download")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal UserPrincipal me,
                                           @PathVariable UUID exportId) {
        byte[] content = exportService.download(me.userId(), exportId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"actionloop-export-" + exportId + ".json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(content);
    }
}
