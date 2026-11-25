package com.actionloop.api.repository;

import com.actionloop.api.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByIdAsc();

    /** outbox 的 workspace_id 无外键，删除空间时需显式清理，避免遗留事件载荷。 */
    @Modifying
    @Query("delete from OutboxEvent o where o.workspaceId = :workspaceId")
    int deleteByWorkspaceId(@Param("workspaceId") UUID workspaceId);
}
