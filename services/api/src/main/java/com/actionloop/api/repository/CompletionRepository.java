package com.actionloop.api.repository;

import com.actionloop.api.domain.Completion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CompletionRepository extends JpaRepository<Completion, UUID> {
    List<Completion> findByTaskStepIdOrderBySubmittedAtDesc(UUID taskStepId);

    /** 一次查出空间内所有待核验凭证，避免按任务/步骤逐层 N+1 且不受“最近 200 个事项”截断影响。*/
    @Query("SELECT c FROM Completion c, TaskStep s, Task t "
            + "WHERE c.taskStepId = s.id AND s.taskId = t.id AND t.workspaceId = :workspaceId "
            + "AND c.reviewState = com.actionloop.api.domain.ReviewState.PENDING "
            + "AND s.status = com.actionloop.api.domain.TaskStepStatus.PENDING_REVIEW "
            + "ORDER BY c.submittedAt DESC")
    List<Completion> findPendingForWorkspace(@Param("workspaceId") UUID workspaceId, Pageable pageable);
}
