package com.actionloop.api.web.dto;

import com.actionloop.api.domain.User;
import com.actionloop.api.domain.UserStatus;

import java.time.Instant;
import java.util.UUID;

/** 用户公开视图，不含密码哈希、内部时间以外的敏感字段。 */
public record UserResponse(
        UUID id,
        String email,
        String username,
        String displayName,
        UserStatus status,
        String timezone,
        String locale,
        Instant createdAt
) {
    public static UserResponse of(User u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getUsername(), u.getDisplayName(),
                u.getStatus(), u.getTimezone(), u.getLocale(), u.getCreatedAt());
    }
}
