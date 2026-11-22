package com.actionloop.api.repository;

import com.actionloop.api.domain.TaskStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskStepRepository extends JpaRepository<TaskStep, UUID> {

    List<TaskStep> findByTaskIdOrderByPositionAsc(UUID taskId);

    Optional<TaskStep> findByTaskIdAndId(UUID taskId, UUID id);

    boolean existsByTaskIdAndPosition(UUID taskId, Integer position);

    @Query("select coalesce(max(s.position), -1) from TaskStep s where s.taskId = :taskId")
    int maxPositionByTaskId(@Param("taskId") UUID taskId);

    /** #16b：事项软删时级联软删其步骤，避免孤儿子实体泄漏。 */
    @Modifying
    @Query("update TaskStep s set s.deletedAt = :now where s.taskId = :taskId and s.deletedAt is null")
    int softDeleteByTaskId(@Param("taskId") UUID taskId, @Param("now") Instant now);
}
