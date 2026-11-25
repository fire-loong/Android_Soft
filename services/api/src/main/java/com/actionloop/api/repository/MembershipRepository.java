package com.actionloop.api.repository;

import com.actionloop.api.domain.Membership;
import com.actionloop.api.domain.MembershipStatus;
import com.actionloop.api.domain.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    Optional<Membership> findByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Membership m where m.workspaceId = :workspaceId and m.userId = :userId")
    Optional<Membership> findLockedByWorkspaceIdAndUserId(@Param("workspaceId") UUID workspaceId,
                                                          @Param("userId") UUID userId);

    List<Membership> findByUserIdAndStatus(UUID userId, MembershipStatus status);

    List<Membership> findByWorkspaceIdAndStatus(UUID workspaceId, MembershipStatus status);

    boolean existsByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    long countByWorkspaceIdAndRoleAndStatus(UUID workspaceId, Role role, MembershipStatus status);

    long countByWorkspaceIdAndStatusAndUserIdNot(UUID workspaceId, MembershipStatus status, UUID userId);

    List<Membership> findByUserIdAndRoleAndStatus(UUID userId, Role role, MembershipStatus status);
}
