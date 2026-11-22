package com.actionloop.api.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * 客户端离线操作输入（指导书 11.4 / 7.4）。
 * opId 由客户端生成（UUIDv7），全局唯一，服务端据此幂等。
 * baseVersion 为客户端编辑时的聚合版本，用于并发冲突检测。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SyncOpInput(
        @NotNull UUID opId,
        @NotBlank String aggregateType,
        @NotNull UUID aggregateId,
        @NotBlank String operationType,
        Integer baseVersion,
        Map<String, Object> payload
) {
}
