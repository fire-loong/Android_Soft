package com.actionloop.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** 协助信令。eventType 必须在服务端白名单内；payload 只允许最小化标注信息。 */
public record AssistSignalRequest(
        @NotBlank @Size(max = 40) String eventType,
        @NotNull @Size(max = 64) Map<String, Object> payload
) {
}
