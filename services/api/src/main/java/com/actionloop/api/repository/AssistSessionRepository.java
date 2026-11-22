package com.actionloop.api.repository;

import com.actionloop.api.domain.AssistSession;
import com.actionloop.api.domain.AssistSessionState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssistSessionRepository extends JpaRepository<AssistSession, UUID> {

    List<AssistSession> findByRequesterIdOrHelperIdOrderByCreatedAtDesc(UUID requesterId, UUID helperId);

    List<AssistSession> findByStateInAndExpiresAtBefore(List<AssistSessionState> states, Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AssistSession s where s.id = :id")
    Optional<AssistSession> findWithLockById(@Param("id") UUID id);
}
