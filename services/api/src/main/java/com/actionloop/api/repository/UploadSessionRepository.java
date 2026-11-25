package com.actionloop.api.repository;

import com.actionloop.api.domain.UploadSession;
import com.actionloop.api.domain.UploadSessionState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {

    Optional<UploadSession> findByIdAndCreatedBy(UUID id, UUID createdBy);

    List<UploadSession> findByStateAndExpiresAtBefore(UploadSessionState state, Instant now);

    List<UploadSession> findByWorkspaceId(UUID workspaceId);
}
