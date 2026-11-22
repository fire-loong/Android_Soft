package com.actionloop.api.web.dto;

import com.actionloop.api.domain.TaskStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 状态转换请求。服务端按状态机校验合法性。 */
public record TransitionRequest(
        @NotNull TaskStatus status,
        @NotNull Integer baseVersion,
        @Size(max = 500) String reason
) {
}
