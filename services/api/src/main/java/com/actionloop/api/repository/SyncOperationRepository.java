package com.actionloop.api.repository;

import com.actionloop.api.domain.SyncOperation;
import com.actionloop.api.domain.SyncState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SyncOperationRepository extends JpaRepository<SyncOperation, UUID> {
    List<SyncOperation> findByDeviceIdAndState(UUID deviceId, SyncState state);

    /** 游标拉取：opId 为 UUIDv7，按 opId 升序等价于按创建时间升序，支持断点补洞。 */
    Page<SyncOperation> findByUserIdOrderByOpIdAsc(UUID userId, Pageable pageable);

    Page<SyncOperation> findByUserIdAndOpIdGreaterThanOrderByOpIdAsc(UUID userId, UUID opId, Pageable pageable);
}
