package com.actionloop.api.repository;

import com.actionloop.api.domain.SourceItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SourceItemRepository extends JpaRepository<SourceItem, UUID> {

    Optional<SourceItem> findByWorkspaceIdAndId(UUID workspaceId, UUID id);

    Page<SourceItem> findByWorkspaceId(UUID workspaceId, Pageable pageable);

    Optional<SourceItem> findFirstByWorkspaceIdAndSourceAppAndSourceKeyOrderByReceivedAtDesc(
            UUID workspaceId, String sourceApp, String sourceKey);

    @Query("""
            select s from SourceItem s
            where s.workspaceId = :workspaceId
              and coalesce(s.sourceApp, '') = coalesce(:sourceApp, '')
              and coalesce(s.sourceKey, '') = coalesce(:sourceKey, '')
              and s.contentHash = :contentHash
            """)
    Optional<SourceItem> findFingerprint(@Param("workspaceId") UUID workspaceId,
                                         @Param("sourceApp") String sourceApp,
                                         @Param("sourceKey") String sourceKey,
                                         @Param("contentHash") String contentHash);
}
