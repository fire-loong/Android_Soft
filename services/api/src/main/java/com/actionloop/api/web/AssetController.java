package com.actionloop.api.web;

import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.AssetService;
import com.actionloop.api.web.dto.AssetView;
import com.actionloop.api.web.dto.CompleteUploadRequest;
import com.actionloop.api.web.dto.InitUploadRequest;
import com.actionloop.api.web.dto.InitUploadResponse;
import com.actionloop.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.util.UUID;

/**
 * 证据文件上传与下载（指导书 13.2 / 11.6）。
 * 私有桶不公开；下载走短期签名 URL，上传完成由服务端校验长度与哈希。
 */
@RestController
@RequestMapping("/api/v1")
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    private String reqId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "req_unknown";
    }

    private String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }

    @PostMapping("/assets/uploads")
    public ApiResponse<InitUploadResponse> init(@AuthenticationPrincipal UserPrincipal me,
                                                @Valid @RequestBody InitUploadRequest request) {
        return ApiResponse.of(assetService.init(me.userId(), request, baseUrl()), reqId());
    }

    @PutMapping(value = "/assets/uploads/{id}/content", consumes = MediaType.ALL_VALUE)
    public ApiResponse<Void> putContent(@AuthenticationPrincipal UserPrincipal me,
                                        @PathVariable UUID id,
                                        HttpServletRequest request) throws IOException {
        assetService.putContent(id, me.userId(), request.getInputStream(), request.getContentLengthLong());
        return ApiResponse.of(null, reqId());
    }

    @PostMapping("/assets/uploads/{id}/complete")
    public ApiResponse<AssetView> complete(@AuthenticationPrincipal UserPrincipal me,
                                           @PathVariable UUID id,
                                           @Valid @RequestBody CompleteUploadRequest request) {
        return ApiResponse.of(assetService.complete(id, me.userId(), request), reqId());
    }

    @GetMapping("/assets/{id}")
    public ApiResponse<AssetView> get(@AuthenticationPrincipal UserPrincipal me,
                                      @RequestParam UUID workspaceId,
                                      @PathVariable UUID id) {
        return ApiResponse.of(assetService.getAsset(workspaceId, id, me.userId()), reqId());
    }

    @GetMapping("/assets/{id}/download-url")
    public ApiResponse<String> downloadUrl(@AuthenticationPrincipal UserPrincipal me,
                                           @RequestParam UUID workspaceId,
                                           @PathVariable UUID id) {
        return ApiResponse.of(assetService.downloadUrl(workspaceId, id, me.userId(), baseUrl()), reqId());
    }

    @GetMapping("/assets/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID id,
                                                        @RequestParam long expires,
                                                        @RequestParam UUID workspaceId,
                                                        @RequestParam UUID userId,
                                                        @RequestParam String token) {
        AssetService.DownloadContent content = assetService.openDownload(id, expires, workspaceId, userId, token);
        InputStreamResource resource = new InputStreamResource(content.stream());
        // 强制附件下载 + 禁止 MIME 嗅探，防止声明为 text/html/svg 的文件在浏览器内联渲染造成存储型 XSS（指导书 11.6）。
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.contentLength())
                .body(resource);
    }
}
