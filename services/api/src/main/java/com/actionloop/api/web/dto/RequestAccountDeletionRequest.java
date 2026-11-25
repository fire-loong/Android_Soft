package com.actionloop.api.web.dto;

import com.actionloop.api.domain.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注销请求（指导书 13.2 DELETE /me、15.3 高敏操作需近期重新认证）：
 * 必须再次输入当前密码；冷却期内可取消，到期由 worker 清除数据。
 */
public record RequestAccountDeletionRequest(
        @NotBlank String password,
        @Size(max = 500) String reason
) {
}
