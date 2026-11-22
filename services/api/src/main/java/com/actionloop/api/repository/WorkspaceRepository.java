package com.actionloop.api.repository;

import com.actionloop.api.domain.Workspace;
import com.actionloop.api.domain.WorkspaceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    Optional<Workspace> findByOwnerIdAndType(UUID ownerId, WorkspaceType type);
}
