package com.actionloop.api.repository;

import com.actionloop.api.domain.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID> {
    List<Comment> findByTaskIdOrderByCreatedAtAsc(UUID taskId);

    /** #16b：事项软删时级联软删其评论。 */
    @Modifying
    @Query("update Comment c set c.deletedAt = :now where c.taskId = :taskId and c.deletedAt is null")
    int softDeleteByTaskId(@Param("taskId") UUID taskId, @Param("now") Instant now);
}
