package com.actionloop.api.repository;

import com.actionloop.api.domain.CompletionAsset;
import com.actionloop.api.domain.CompletionAssetId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CompletionAssetRepository extends JpaRepository<CompletionAsset, CompletionAssetId> {
    List<CompletionAsset> findByCompletionId(UUID completionId);
}
