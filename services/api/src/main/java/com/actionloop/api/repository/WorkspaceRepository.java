package com.actionloop.api.repository;

import com.actionloop.api.domain.Workspace;
import com.actionloop.api.domain.WorkspaceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    Optional<Workspace> findByOwnerIdAndType(UUID ownerId, WorkspaceType type);

    /** 用户名下全部有效空间（含团队），用于注销前的所有权校验与到期清除。 */
    List<Workspace> findByOwnerId(UUID ownerId);
}
