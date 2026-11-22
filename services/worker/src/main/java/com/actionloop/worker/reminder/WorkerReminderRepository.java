package com.actionloop.worker.reminder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WorkerReminderRepository extends JpaRepository<WorkerReminder, UUID> {

    List<WorkerReminder> findByStateAndTriggerAtBefore(String state, Instant triggerAt);

    /** 条件更新：仅当仍为 SCHEDULED 时标记 SENT，返回受影响行数（0 表示被并发取消/已处理）。 */
    @Modifying
    @Query("update WorkerReminder r set r.state = 'SENT', r.updatedAt = :now where r.id = :id and r.state = 'SCHEDULED'")
    int markSent(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("update WorkerReminder r set r.state = 'FAILED', r.updatedAt = :now where r.id = :id and r.state = 'SCHEDULED'")
    int markFailed(@Param("id") UUID id, @Param("now") Instant now);
}
