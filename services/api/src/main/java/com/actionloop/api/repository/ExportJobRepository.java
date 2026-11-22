package com.actionloop.api.repository;

import com.actionloop.api.domain.ExportJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExportJobRepository extends JpaRepository<ExportJob, UUID> {

    Optional<ExportJob> findByIdAndRequestedBy(UUID id, UUID requestedBy);

    List<ExportJob> findByRequestedByOrderByCreatedAtDesc(UUID requestedBy);

    List<ExportJob> findByStateAndCreatedAtBefore(String state, java.time.Instant before);
}
