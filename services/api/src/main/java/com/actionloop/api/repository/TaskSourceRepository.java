package com.actionloop.api.repository;

import com.actionloop.api.domain.TaskSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TaskSourceRepository extends JpaRepository<TaskSource, UUID> {
    List<TaskSource> findByTaskId(UUID taskId);

    List<TaskSource> findBySourceItemId(UUID sourceItemId);
}
