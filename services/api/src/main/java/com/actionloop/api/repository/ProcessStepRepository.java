package com.actionloop.api.repository;

import com.actionloop.api.domain.ProcessStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProcessStepRepository extends JpaRepository<ProcessStep, UUID> {

    List<ProcessStep> findByVersionIdOrderByPositionAsc(UUID versionId);

    void deleteByVersionId(UUID versionId);
}
