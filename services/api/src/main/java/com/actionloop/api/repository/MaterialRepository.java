package com.actionloop.api.repository;

import com.actionloop.api.domain.Material;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MaterialRepository extends JpaRepository<Material, UUID> {

    List<Material> findByTaskId(UUID taskId);

    Optional<Material> findByTaskIdAndId(UUID taskId, UUID id);

    /** #16b：事项软删时级联软删其材料。 */
    @Modifying
    @Query("update Material m set m.deletedAt = :now where m.taskId = :taskId and m.deletedAt is null")
    int softDeleteByTaskId(@Param("taskId") UUID taskId, @Param("now") Instant now);
}
