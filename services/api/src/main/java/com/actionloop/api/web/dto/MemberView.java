package com.actionloop.api.web.dto;

import com.actionloop.api.domain.Membership;
import com.actionloop.api.domain.MembershipStatus;
import com.actionloop.api.domain.Role;
import com.actionloop.api.domain.User;

import java.time.Instant;
import java.util.UUID;

/** 空间成员视图。 */
public record MemberView(
        UUID userId,
        String username,
        String displayName,
        Role role,
        MembershipStatus status,
        Instant joinedAt
) {
    public static MemberView of(Membership membership, User user) {
        if (user == null) {
            return null;
        }
        return new MemberView(user.getId(), user.getUsername(), user.getDisplayName(),
                membership.getRole(), membership.getStatus(), membership.getJoinedAt());
    }
}
