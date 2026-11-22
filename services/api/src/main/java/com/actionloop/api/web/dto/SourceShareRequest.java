package com.actionloop.api.web.dto;

import com.actionloop.api.domain.PrivacyLevel;
import com.actionloop.api.domain.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * 分享/通知/截图导入来源（指导书 13.2 POST /sources/share）。
 * 客户端可先上传资产再携带 assetId 与本地 OCR 文本；云端只在用户确认范围内解析。
 */
public record SourceShareRequest(
        @NotNull UUID workspaceId,
        @NotNull SourceType sourceType,
        @Size(max = 128) String sourceApp,
        @Size(max = 256) String sourceKey,
        @Size(max = 256) String title,
        @NotBlank @Size(max = 20000) String text,
        @Size(max = 2048) String linkUrl,
        Instant receivedAt,
        @Size(max = 64) String timezone,
        PrivacyLevel privacyLevel,
        UUID assetId,
        @Size(max = 40) String pageRef,
        @Size(max = 2000) String bboxJson,
        Boolean localOnly
) {
}
