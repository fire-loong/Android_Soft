package com.actionloop.api.repository;

import com.actionloop.api.domain.CandidateStatus;
import com.actionloop.api.domain.CandidateTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CandidateTaskRepository extends JpaRepository<CandidateTask, UUID> {

    Optional<CandidateTask> findByWorkspaceIdAndId(UUID workspaceId, UUID id);

    Page<CandidateTask> findByWorkspaceIdAndStatus(UUID workspaceId, CandidateStatus status, Pageable pageable);

    Page<CandidateTask> findByWorkspaceId(UUID workspaceId, Pageable pageable);

    List<CandidateTask> findBySourceItemIdOrderByCreatedAtDesc(UUID sourceItemId);

    long countBySourceItemId(UUID sourceItemId);

    boolean existsByWorkspaceIdAndSourceItemIdAndStatusAndTitleIgnoreCase(
            UUID workspaceId, UUID sourceItemId, CandidateStatus status, String title);
}
