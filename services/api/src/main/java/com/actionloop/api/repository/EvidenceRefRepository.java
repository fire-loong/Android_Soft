package com.actionloop.api.repository;

import com.actionloop.api.domain.EvidenceRef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvidenceRefRepository extends JpaRepository<EvidenceRef, UUID> {

    List<EvidenceRef> findByWorkspaceId(UUID workspaceId);

    List<EvidenceRef> findBySourceItemId(UUID sourceItemId);

    List<EvidenceRef> findByAssetId(UUID assetId);

    Optional<EvidenceRef> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
}
