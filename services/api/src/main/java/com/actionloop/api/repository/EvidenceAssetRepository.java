package com.actionloop.api.repository;

import com.actionloop.api.domain.EvidenceAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvidenceAssetRepository extends JpaRepository<EvidenceAsset, UUID> {

    Optional<EvidenceAsset> findByWorkspaceIdAndSha256(UUID workspaceId, String sha256);

    Optional<EvidenceAsset> findByWorkspaceIdAndId(UUID workspaceId, UUID id);

    /** 注销清除：包含已软删资产的对象键，避免对象存储残留（指导书 15.5）。 */
    @Query(value = "select storage_key from evidence_assets where workspace_id = :workspaceId", nativeQuery = true)
    List<String> findStorageKeysIncludingDeleted(@Param("workspaceId") UUID workspaceId);

    @Query("select coalesce(sum(e.sizeBytes), 0) from EvidenceAsset e where e.workspaceId = :workspaceId")
    long sumSizeBytesByWorkspaceId(@Param("workspaceId") UUID workspaceId);
}
