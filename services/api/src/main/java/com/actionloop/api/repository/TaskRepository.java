package com.actionloop.api.repository;

import com.actionloop.api.domain.Task;
import com.actionloop.api.domain.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    Optional<Task> findByWorkspaceIdAndId(UUID workspaceId, UUID id);

    /** 仅取 id 列，供工作区级批量子资源查询使用（避免加载整行）。 */
    @Query("select t.id from Task t where t.workspaceId = :workspaceId")
    List<UUID> findIdsByWorkspaceId(@Param("workspaceId") UUID workspaceId);

    Page<Task> findByWorkspaceId(UUID workspaceId, Pageable pageable);

    Page<Task> findByWorkspaceIdAndStatus(UUID workspaceId, TaskStatus status, Pageable pageable);

    Page<Task> findByAssigneeId(UUID assigneeId, Pageable pageable);
}
