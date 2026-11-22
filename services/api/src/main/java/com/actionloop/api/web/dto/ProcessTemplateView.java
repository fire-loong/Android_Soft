package com.actionloop.api.web.dto;

import com.actionloop.api.domain.ProcessTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProcessTemplateView(
        UUID id,
        UUID workspaceId,
        String title,
        String regionCode,
        String scopeJson,
        UUID currentVersionId,
        Integer version,
        Instant createdAt,
        List<ProcessVersionView> versions
) {
    public static ProcessTemplateView of(ProcessTemplate t, List<ProcessVersionView> versions) {
        return new ProcessTemplateView(t.getId(), t.getOwnerWorkspaceId(), t.getTitle(), t.getRegionCode(),
                t.getScopeJson(), t.getCurrentVersionId(), t.getVersion(), t.getCreatedAt(), versions);
    }
}
