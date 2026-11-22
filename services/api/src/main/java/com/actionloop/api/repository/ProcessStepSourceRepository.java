package com.actionloop.api.repository;

import com.actionloop.api.domain.ProcessStepSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProcessStepSourceRepository extends JpaRepository<ProcessStepSource, ProcessStepSource.Key> {

    List<ProcessStepSource> findByProcessStepId(UUID processStepId);

    void deleteByProcessStepId(UUID processStepId);
}
