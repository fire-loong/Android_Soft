package com.actionloop.api.repository;

import com.actionloop.api.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID>, JpaSpecificationExecutor<AuditLog> {

    Page<AuditLog> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId, Pageable pageable);

    Page<AuditLog> findByWorkspaceIdAndObjectTypeOrderByCreatedAtDesc(UUID workspaceId, String objectType,
                                                                     Pageable pageable);

    Page<AuditLog> findByWorkspaceIdAndObjectTypeAndObjectIdOrderByCreatedAtDesc(UUID workspaceId, String objectType,
                                                                                String objectId, Pageable pageable);

    Page<AuditLog> findByWorkspaceIdAndObjectIdOrderByCreatedAtDesc(UUID workspaceId, String objectId,
                                                                   Pageable pageable);
}
