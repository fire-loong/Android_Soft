package com.actionloop.api.repository;

import com.actionloop.api.domain.Device;
import com.actionloop.api.domain.DeviceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    List<Device> findByUserIdAndStatus(UUID userId, DeviceStatus status);

    List<Device> findByUserId(UUID userId);

    /**
     * 刷新轮换必须在设备行上串行化：并发使用同一 refresh token 时，
     * 只有第一个事务能通过 jti 校验，其余视为重用并撤销会话（指导书 15.3）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Device d where d.id = :id")
    Optional<Device> findWithLockById(@Param("id") UUID id);
}
