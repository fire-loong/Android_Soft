package com.actionloop.api.repository;

import com.actionloop.api.domain.EvidenceAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface EvidenceAssetRepository extends JpaRepository<EvidenceAsset, UUID> {

    Optional<EvidenceAsset> findByWorkspaceIdAndSha256(UUID workspaceId, String sha256);

    Optional<EvidenceAsset> findByWorkspaceIdAndId(UUID workspaceId, UUID id);

    @Query("select coalesce(sum(e.sizeBytes), 0) from EvidenceAsset e where e.workspaceId = :workspaceId")
    long sumSizeBytesByWorkspaceId(@Param("workspaceId") UUID workspaceId);
}
