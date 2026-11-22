package com.actionloop.api.repository;

import com.actionloop.api.domain.ServerEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ServerEventRepository extends JpaRepository<ServerEvent, Long> {

    @Query("select coalesce(max(e.seq), 0) from ServerEvent e where e.workspaceId = :workspaceId")
    long maxSeq(@Param("workspaceId") UUID workspaceId);

    List<ServerEvent> findByWorkspaceIdAndSeqGreaterThanOrderBySeqAsc(UUID workspaceId, long afterSeq,
                                                                      org.springframework.data.domain.Pageable pageable);
}
