package com.actionloop.api.repository;

import com.actionloop.api.domain.Membership;
import com.actionloop.api.domain.MembershipStatus;
import com.actionloop.api.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    Optional<Membership> findByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    List<Membership> findByUserIdAndStatus(UUID userId, MembershipStatus status);

    List<Membership> findByWorkspaceIdAndStatus(UUID workspaceId, MembershipStatus status);

    boolean existsByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    long countByWorkspaceIdAndRoleAndStatus(UUID workspaceId, Role role, MembershipStatus status);
}
