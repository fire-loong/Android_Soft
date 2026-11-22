package com.actionloop.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** 完成上传：服务端 HeadObject 校验长度与哈希后生成 EvidenceAsset。 */
public record CompleteUploadRequest(
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String sha256,
        @NotNull @Positive Long sizeBytes
) {
}
