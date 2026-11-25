package com.actionloop.api.repository;

import com.actionloop.api.domain.ExportJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExportJobRepository extends JpaRepository<ExportJob, UUID> {

    Optional<ExportJob> findByIdAndRequestedBy(UUID id, UUID requestedBy);

    List<ExportJob> findByRequestedByOrderByCreatedAtDesc(UUID requestedBy);

    List<ExportJob> findByStateAndCreatedAtBefore(String state, Instant before);

    List<ExportJob> findByStateAndExpiresAtBefore(String state, Instant before);

    List<ExportJob> findByWorkspaceId(UUID workspaceId);

    /** 避免两个 worker 同时处理同一导出（租约过期重投时尤其重要）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from ExportJob e where e.id = :id")
    Optional<ExportJob> findWithLockById(@Param("id") UUID id);
}
