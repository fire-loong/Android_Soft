package com.actionloop.api.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 创建协助会话。scopes 只允许最小集合；任何控制类能力（点击/滑动/输入/支付）都会被拒绝。
 */
public record CreateAssistSessionRequest(
        @NotNull UUID workspaceId,
        UUID taskStepId,
        UUID helperId,
        @Size(max = 40) String mode,
        @Size(max = 10) List<String> scopes,
        Integer expiresMinutes
) {
}
