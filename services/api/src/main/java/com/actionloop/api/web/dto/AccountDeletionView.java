package com.actionloop.api.web.dto;

import com.actionloop.api.domain.User;

import java.time.Instant;

/**
 * 注销状态视图：NONE 表示无待执行注销；PENDING 表示已进入冷却期，到期自动清除。
 * 仅包含时间点与冷却天数，不返回密码、令牌或内部原因。
 */
public record AccountDeletionView(
        String state,
        Instant requestedAt,
        Instant scheduledAt,
        int cooldownDays
) {

    public static final String STATE_NONE = "NONE";
    public static final String STATE_PENDING = "PENDING";

    public static AccountDeletionView none(int cooldownDays) {
        return new AccountDeletionView(STATE_NONE, null, null, cooldownDays);
    }

    public static AccountDeletionView of(User user, int cooldownDays) {
        if (user.getDeletionScheduledAt() == null) {
            return none(cooldownDays);
        }
        return new AccountDeletionView(STATE_PENDING, user.getDeletionRequestedAt(),
                user.getDeletionScheduledAt(), cooldownDays);
    }
}
