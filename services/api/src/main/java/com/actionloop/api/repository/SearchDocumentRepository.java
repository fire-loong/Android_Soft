package com.actionloop.api.repository;

import com.actionloop.api.domain.SearchDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SearchDocumentRepository extends JpaRepository<SearchDocument, UUID> {

    Optional<SearchDocument> findByWorkspaceIdAndSourceItemId(UUID workspaceId, UUID sourceItemId);
}
