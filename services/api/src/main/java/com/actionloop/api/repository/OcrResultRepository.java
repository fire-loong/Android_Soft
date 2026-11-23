package com.actionloop.api.repository;

import com.actionloop.api.domain.OcrResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OcrResultRepository extends JpaRepository<OcrResult, UUID> {

    Optional<OcrResult> findByAssetIdAndProviderAndModelAndPromptVersion(
            UUID assetId, String provider, String model, String promptVersion);
}
