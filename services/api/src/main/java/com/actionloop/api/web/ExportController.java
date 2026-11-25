package com.actionloop.api.web;

import com.actionloop.api.export.ExportRequestService;
import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.ExportService;
import com.actionloop.api.web.dto.CreateExportRequest;
import com.actionloop.api.web.dto.ExportView;
import com.actionloop.shared.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
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

/**
 * 数据导出（指导书 11.7 / 13.2 / WP13）：异步生成、短期下载、二次认证。
 */
@RestController
@RequestMapping("/api/v1/exports")
public class ExportController {

    private final ExportService exportService;
    private final ExportRequestService exportRequestService;

    public ExportController(ExportService exportService, ExportRequestService exportRequestService) {
        this.exportService = exportService;
        this.exportRequestService = exportRequestService;
    }

    private String reqId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "req_unknown";
    }

    @PostMapping
    @Operation(summary = "创建空间导出（需密码重新认证，异步生成）")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "已入队，state=PENDING"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "密码错误或账户不可用"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "非空间管理员")
    })
    public ResponseEntity<ApiResponse<ExportView>> create(@AuthenticationPrincipal UserPrincipal me,
                                                          @Valid @RequestBody CreateExportRequest request) {
        ExportView view = exportRequestService.request(me, request);
        return ResponseEntity.accepted().body(ApiResponse.of(view, reqId()));
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
