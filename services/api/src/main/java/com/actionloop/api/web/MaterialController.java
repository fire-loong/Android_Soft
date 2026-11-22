package com.actionloop.api.web;

import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.MaterialService;
import com.actionloop.api.web.dto.CreateMaterialRequest;
import com.actionloop.api.web.dto.MaterialView;
import com.actionloop.api.web.dto.UpdateMaterialRequest;
import com.actionloop.shared.web.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class MaterialController {

    private final MaterialService materialService;

    public MaterialController(MaterialService materialService) {
        this.materialService = materialService;
    }

    private String reqId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "req_unknown";
    }

    @GetMapping("/tasks/{taskId}/materials")
    public ApiResponse<List<MaterialView>> list(@AuthenticationPrincipal UserPrincipal me,
                                                @RequestParam UUID workspaceId,
                                                @PathVariable UUID taskId) {
        return ApiResponse.of(materialService.list(workspaceId, taskId, me.userId()), reqId());
    }

    @PostMapping("/tasks/{taskId}/materials")
    public ApiResponse<MaterialView> create(@AuthenticationPrincipal UserPrincipal me,
                                            @RequestParam UUID workspaceId,
                                            @PathVariable UUID taskId,
                                            @Valid @RequestBody CreateMaterialRequest request) {
        return ApiResponse.of(materialService.create(workspaceId, taskId, me.userId(), request), reqId());
    }

    @PatchMapping("/tasks/{taskId}/materials/{id}")
    public ApiResponse<MaterialView> update(@AuthenticationPrincipal UserPrincipal me,
                                            @RequestParam UUID workspaceId,
                                            @PathVariable UUID taskId,
                                            @PathVariable UUID id,
                                            @Valid @RequestBody UpdateMaterialRequest request) {
        return ApiResponse.of(materialService.update(workspaceId, taskId, id, me.userId(), request), reqId());
    }

    @DeleteMapping("/tasks/{taskId}/materials/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UserPrincipal me,
                                    @RequestParam UUID workspaceId,
                                    @PathVariable UUID taskId,
                                    @PathVariable UUID id) {
        materialService.delete(workspaceId, taskId, id, me.userId());
        return ApiResponse.of(null, reqId());
    }
}
