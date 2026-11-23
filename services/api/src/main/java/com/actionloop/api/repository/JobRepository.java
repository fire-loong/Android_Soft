package com.actionloop.api.repository;

import com.actionloop.api.domain.Job;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    Optional<Job> findByIdempotencyKey(String idempotencyKey);

    Optional<Job> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    Optional<Job> findFirstByPayloadRefOrderByCreatedAtDesc(String payloadRef);
}
