package com.actionloop.api.repository;

import com.actionloop.api.domain.ProcessTemplate;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProcessTemplateRepository extends JpaRepository<ProcessTemplate, UUID> {

    org.springframework.data.domain.Page<ProcessTemplate> findByOwnerWorkspaceId(
            UUID ownerWorkspaceId, org.springframework.data.domain.Pageable pageable);

    Optional<ProcessTemplate> findByIdAndOwnerWorkspaceId(UUID id, UUID ownerWorkspaceId);

    /** 创建版本时锁模板行，避免并发 max(version_no)+1 撞唯一索引。*/
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ProcessTemplate t where t.id = :id")
    Optional<ProcessTemplate> findWithLockById(@Param("id") UUID id);
}
